package com.kzai.aigc.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.client.CourseAiItem;
import com.kzai.aigc.client.CourseClient;
import com.kzai.aigc.client.PrePlaceRequest;
import com.kzai.aigc.client.PrePlaceResult;
import com.kzai.aigc.client.TradeClient;
import com.kzai.aigc.chat.StopRegistry;
import com.kzai.common.context.UserContext;
import com.kzai.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 购买预下单工具（M7，唯一允许的写操作）。
 * <p>
 * <b>确认门禁</b>：AI 收到购买意图后，必须先向用户确认具体课程与价格；
 * 只有当前会话存在「已确认且包含该课程、未过期」的购买提案时，本工具才执行真实预下单。
 * 课程不明确 / 价格未核实 / 用户未确认 → 只生成待确认提案，绝不写库。
 * <p>
 * <b>身份与安全</b>：
 * <ul>
 *   <li>userId 一律取自 ToolContext 的可信认证上下文（网关验证后传播），
 *       模型参数无法指定用户身份——用户 A 不可能替用户 B 下单；</li>
 *   <li>requestId 由已确认提案派生且固定不变：重试（含交易超时重试）复用同一幂等号，
 *       由交易服务 order_request_idempotent 保证只产生一笔订单；
 *       相同 requestId 不同课程内容会被交易服务判定冲突；</li>
 *   <li>课程状态、价格、有效期、是否已购全部由交易服务重新校验；</li>
 *   <li>只创建待支付订单；不调用支付接口，不修改订单为已支付，不伪造支付回调；</li>
 *   <li>停止生成后立即拒绝执行（写操作授权随生成终止而失效）；</li>
 *   <li>Prompt Injection 无法改变工具权限：确认状态在服务端，模型不可写。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTools {

    public static final String NAME_CREATE = "createPreOrder";
    public static final String CARD_ORDER = "ORDER_CARD";

    private final CourseClient courseClient;
    private final TradeClient tradeClient;
    private final PurchaseProposalStore proposalStore;
    private final StopRegistry stopRegistry;
    private final ToolAudit audit;
    private final ObjectMapper objectMapper;

    public List<FunctionCallback> callbacks() {
        return List.of(FunctionCallback.builder()
                .function(NAME_CREATE,
                        (BiFunction<PreOrderRequest, ToolContext, String>) this::createPreOrder)
                .description("为用户创建课程待支付订单（预下单）。调用前提：用户已明确确认购买该课程；"
                        + "若尚未确认，工具会返回课程真实价格并要求先向用户确认。"
                        + "本工具只创建待支付订单，支付需用户在订单页自行完成。courseId 为字符串数字。")
                .inputType(PreOrderRequest.class)
                .build());
    }

    /** 预下单（模型回调入口，MOCK 脚本亦复用） */
    public String createPreOrder(PreOrderRequest request, ToolContext toolContext) {
        long t0 = System.currentTimeMillis();
        String requestId = CourseTools.strOf(toolContext, ToolContextKeys.REQUEST_ID);
        String sessionId = CourseTools.strOf(toolContext, ToolContextKeys.SESSION_ID);
        ToolResultHolder round = CourseTools.holderOf(toolContext);
        Long userId = CourseTools.userIdOf(toolContext);
        if (userId == null) {
            // 无可信身份：拒绝执行（绝不允许模型参数决定用户身份）
            audit.record(NAME_CREATE, requestId, sessionId, since(t0), false, 0, ToolAudit.ERR_NO_IDENTITY);
            return json(Map.of("status", "NO_IDENTITY",
                    "message", "缺少可信用户身份，已拒绝下单。请让用户重新登录后再试"));
        }
        String courseId = request == null ? null : request.getCourseId();
        Long id = CourseTools.parseCourseId(courseId);
        if (round != null && !round.tryAcquireToolCall(NAME_CREATE)) {
            // 预下单预算超限：绝不放行写操作，要求模型直接作答
            audit.record(NAME_CREATE, requestId, sessionId, since(t0), false, 0, "TOOL_BUDGET_EXCEEDED");
            return json(Map.of("status", "TOOL_BUDGET_EXCEEDED",
                    "message", "本轮工具调用次数已达上限，已拒绝继续下单。请基于已有结果直接回答"));
        }
        if (id == null) {
            audit.record(NAME_CREATE, requestId, sessionId, since(t0), true, 0, ToolAudit.ERR_BAD_REQUEST);
            return json(Map.of("status", "BAD_REQUEST",
                    "message", "courseId 非法。请先调用 getCourseDetail 或 searchCourses 获取真实课程ID"));
        }
        // 停止生成：未授权的写操作必须终止
        if (sessionId != null && stopRegistry.isStopRequested(sessionId)) {
            audit.record(NAME_CREATE, requestId, sessionId, since(t0), true, 0, ToolAudit.ERR_STOP_REQUESTED);
            return json(Map.of("status", "STOPPED",
                    "message", "用户已停止生成，本次未创建任何订单"));
        }
        try {
            List<String> roles = rolesOf(toolContext);
            PurchaseProposalStore.Proposal proposal = proposalStore.get(userId, sessionId);
            boolean confirmed = proposal != null && proposal.confirmed && proposal.contains(String.valueOf(id));
            if (!confirmed) {
                return requireConfirmation(userId, roles, sessionId, requestId, id, t0);
            }
            return executePreOrder(userId, roles, sessionId, requestId, id, proposal, round, t0);
        } catch (RuntimeException ex) {
            log.warn("[tool] createPreOrder 异常: {}", ex.getMessage());
            audit.record(NAME_CREATE, requestId, sessionId, since(t0), false, 0, ToolAudit.ERR_SERVICE_UNAVAILABLE);
            return json(Map.of("status", "TRADE_SERVICE_UNAVAILABLE",
                    "message", "交易服务暂时不可用，未创建订单。请如实告知用户稍后重试"));
        } finally {
            UserContext.clear();
        }
    }

    /** 未确认路径：查询真实课程 → 生成待确认提案 → 要求模型向用户确认（不写库） */
    private String requireConfirmation(long userId, List<String> roles, String sessionId, String requestId,
                                       Long courseId, long t0) {
        try {
            restoreUserContext(userId, roles, requestId);
            R<CourseAiItem> resp = courseClient.aiDetail(courseId);
            CourseAiItem course = resp == null || !resp.isSuccess() ? null : resp.getData();
            if (course == null) {
                audit.record(NAME_CREATE, requestId, sessionId, since(t0), true, 0, ToolAudit.ERR_NOT_FOUND);
                return json(Map.of("status", "NOT_FOUND",
                        "message", "未找到该课程或课程暂不可购买。请如实告知用户，不得编造课程或价格"));
            }
            proposalStore.storePending(userId, sessionId, List.of(String.valueOf(courseId)));
            audit.record(NAME_CREATE, requestId, sessionId, since(t0), true, 0, ToolAudit.ERR_CONFIRM_REQUIRED);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "CONFIRM_REQUIRED");
            body.put("course", CourseTools.courseCardPayload(course));
            body.put("message", "购买需要用户对具体课程与价格显式确认。"
                    + "请向用户复述该课程的名称与价格（以 course 字段为准），"
                    + "询问是否创建待支付订单；在用户明确确认前不得再次调用本工具");
            return json(body);
        } finally {
            UserContext.clear();
        }
    }

    /** 已确认路径：稳定 requestId 真实预下单；结果写入 ToolResultHolder 作为 1003 卡片 */
    private String executePreOrder(long userId, List<String> roles, String sessionId, String requestId,
                                   Long courseId, PurchaseProposalStore.Proposal proposal,
                                   ToolResultHolder round, long t0) {
        try {
            restoreUserContext(userId, roles, requestId);
            // 交易服务重新校验课程状态/价格/有效期/是否已购；幂等由 order_request_idempotent 保证
            R<PrePlaceResult> resp = tradeClient.prePlace(
                    new PrePlaceRequest(List.of(courseId), proposal.requestId));
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                String bizMsg = resp == null ? "交易服务无响应" : String.valueOf(resp.getMsg());
                // 关键：失败不消费提案、requestId 不变——稍后重试仍是同一幂等号，不会产生第二笔订单
                audit.record(NAME_CREATE, requestId, sessionId, since(t0), false, 0, ToolAudit.ERR_TRADE_REJECTED);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", "TRADE_ERROR");
                body.put("requestId", proposal.requestId);
                body.put("message", "预下单未成功：" + bizMsg + "。请告知用户稍后重试；"
                        + "请勿更换 requestId 或直接判定订单已创建");
                return json(body);
            }
            PrePlaceResult vo = resp.getData();
            boolean hit = Boolean.TRUE.equals(vo.getIdempotentHit());
            if (round != null) {
                round.addCard(CARD_ORDER, "order:" + vo.getOrderId(), orderCardPayload(vo));
            }
            // 下单成功（含幂等命中）即消费提案，防止同一确认被复用
            proposalStore.consume(userId, sessionId);
            audit.record(NAME_CREATE, requestId, sessionId, since(t0), true, 1, hit ? "IDEMPOTENT_HIT" : null);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "OK");
            body.put("orderId", vo.getOrderId());
            body.put("orderStatus", vo.getStatus());
            body.put("statusText", statusTextOf(vo));
            body.put("totalAmount", vo.getTotalAmount());
            body.put("totalAmountYuan", vo.getTotalAmountYuan());
            body.put("idempotentHit", hit);
            body.put("requestId", vo.getRequestId());
            body.put("message", "待支付订单已创建（未支付）。请如实告知用户前往订单页完成支付，"
                    + "不得宣称购买成功或已支付");
            return json(body);
        } finally {
            UserContext.clear();
        }
    }

    /** 订单卡片载荷（1003 ORDER_CARD，全部来自交易服务真实返回） */
    public static Map<String, Object> orderCardPayload(PrePlaceResult vo) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("orderId", vo.getOrderId());
        p.put("status", vo.getStatus() == null ? 0 : vo.getStatus());
        p.put("statusText", statusTextOf(vo));
        p.put("totalAmount", vo.getTotalAmount() == null ? 0 : vo.getTotalAmount());
        p.put("totalAmountYuan", vo.getTotalAmountYuan() == null ? "0.00" : vo.getTotalAmountYuan());
        p.put("requestId", vo.getRequestId());
        p.put("idempotentHit", Boolean.TRUE.equals(vo.getIdempotentHit()));
        List<Map<String, Object>> items = vo.getItems() == null ? List.of() : vo.getItems().stream().map(item -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("courseId", item.getCourseId());
            m.put("name", item.getName());
            m.put("coverUrl", item.getCoverUrl());
            m.put("price", item.getPrice());
            m.put("priceYuan", item.getPriceYuan());
            m.put("statusText", item.getStatusText());
            return m;
        }).toList();
        p.put("items", items);
        return p;
    }

    private static String statusTextOf(PrePlaceResult vo) {
        if (vo.getStatusText() != null && !vo.getStatusText().isBlank()) {
            return vo.getStatusText();
        }
        return switch (vo.getStatus() == null ? 0 : vo.getStatus()) {
            case 1 -> "待支付";
            case 2 -> "已支付";
            case 3 -> "已关闭";
            case 4 -> "已完成";
            case 5 -> "已报名";
            case 6 -> "已申请退款";
            default -> "未知状态";
        };
    }

    private static void restoreUserContext(long userId, List<String> roles, String requestId) {
        // 交易侧 pre-place 有 @RequireRoles(STUDENT/ADMIN)：角色必须随签名头透传（来自可信上下文）
        UserContext.set(userId, null, null, roles == null ? List.of() : roles, requestId);
    }

    @SuppressWarnings("unchecked")
    private static List<String> rolesOf(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return List.of();
        }
        Object roles = toolContext.getContext().get(ToolContextKeys.ROLES);
        return roles instanceof List<?> l ? (List<String>) l : List.of();
    }

    private String json(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            return "{\"status\":\"ERROR\",\"message\":\"工具结果序列化失败\"}";
        }
    }

    private static long since(long t0) {
        return System.currentTimeMillis() - t0;
    }
}
