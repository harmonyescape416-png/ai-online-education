package com.kzai.trade.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import com.kzai.trade.dto.OrderQuery;
import com.kzai.trade.dto.PrePlaceRequest;
import com.kzai.trade.service.OrderService;
import com.kzai.trade.vo.OrderDetailVO;
import com.kzai.trade.vo.OrderVO;
import com.kzai.trade.vo.PrePlaceVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import java.util.Map;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口（M4）。
 * <p>
 * 用户身份只取自网关注入的上下文，禁止从请求参数读取 userId。
 * 原文接口：POST /orders/pre-place（预下单）；本阶段新增列表/详情/取消。
 */
@RestController
@RequestMapping("/orders")
@RequireRoles({Constants.ROLE_STUDENT, Constants.ROLE_ADMIN})
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final com.kzai.trade.compensation.EnrollCompensationService enrollCompensationService;
    private final com.kzai.trade.payment.PaymentAppService paymentAppService;
    private final com.kzai.trade.payment.RefundAppService refundAppService;

    /** 购买预下单：同一 requestId 重复提交返回同一订单 */
    @PostMapping("/pre-place")
    public R<PrePlaceVO> prePlace(@Valid @RequestBody PrePlaceRequest request) {
        return R.ok(orderService.prePlace(UserContext.getUserId(), request));
    }

    /** 我的订单：分页 + 状态筛选 + 创建时间排序 */
    @GetMapping
    public R<IPage<OrderVO>> list(OrderQuery query) {
        return R.ok(orderService.page(UserContext.getUserId(), query));
    }

    /** 订单详情：仅订单所有者可查看 */
    @GetMapping("/{id}")
    public R<java.util.Map<String, Object>> detail(@PathVariable("id") Long id) {
        OrderDetailVO vo = orderService.detail(UserContext.getUserId(), id,
                UserContext.hasRole(Constants.ROLE_ADMIN) || UserContext.hasRole(Constants.ROLE_STAFF));
        // M15.21：结构化开课状态（不靠备注文本判断）——enrollStatuses 四态：PENDING 等待重试/RUNNING 处理中/SUCCESS 已开课/FAILED_MANUAL 重试耗尽
        var comps = enrollCompensationService.statusOf(id);
        java.util.List<java.util.Map<String, Object>> enrollStatuses = comps.stream()
                .map(c -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("courseId", String.valueOf(c.getCourseId()));
                    m.put("status", c.getStatus());
                    m.put("attempts", c.getAttempts());
                    m.put("lastError", c.getLastError());
                    return m;
                }).toList();
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("order", vo);
        data.put("enrollStatuses", enrollStatuses);
        data.put("enrollAllSuccess", enrollStatuses.isEmpty()
                || enrollStatuses.stream().allMatch(m -> "SUCCESS".equals(m.get("status"))));
        return R.ok(data).requestId(UserContext.getRequestId());
    }

    private R<OrderDetailVO> legacyDetail(@org.springframework.web.bind.annotation.PathVariable("id") Long id) {
        boolean admin = UserContext.hasRole(Constants.ROLE_ADMIN) || UserContext.hasRole(Constants.ROLE_STAFF);
        return R.ok(orderService.detail(UserContext.getUserId(), id, admin));
    }

    /** 取消订单：仅所有者、仅待支付；重复取消按幂等处理 */
    @PostMapping("/{id}/cancel")
    public R<String> cancel(@PathVariable("id") Long id) {
        boolean admin = UserContext.hasRole(Constants.ROLE_ADMIN) || UserContext.hasRole(Constants.ROLE_STAFF);
        return R.ok(orderService.cancel(UserContext.getUserId(), id, admin));
    }

    /**
     * 创建支付。模拟渠道会在服务端生成带签名的成功通知并走同一入账方法。
     * 返回里的 paymentStatus 才是结果，前端跳转或按钮本身不是支付成功。
     */
    @PostMapping("/{id}/pay")
    public R<Map<String, Object>> pay(@PathVariable("id") Long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        boolean admin = UserContext.hasRole(Constants.ROLE_ADMIN) || UserContext.hasRole(Constants.ROLE_STAFF);
        String channel = body == null ? "" : String.valueOf(body.getOrDefault("channel", ""));
        Long userId = UserContext.getUserId();
        Map<String, Object> paid = paymentAppService.start(userId, id, channel, admin);
        if ("SUCCESS".equals(paid.get("paymentStatus"))) {
            enrollCompensationService.executeNow(userId, id);
        }
        var comps = enrollCompensationService.statusOf(id);
        paid.put("enrollStatuses", comps.stream().map(c -> java.util.Map.of(
                "courseId", String.valueOf(c.getCourseId()),
                "status", c.getStatus(),
                "attempts", c.getAttempts())).toList());
        boolean allOk = !comps.isEmpty() && comps.stream().allMatch(c -> "SUCCESS".equals(c.getStatus()));
        paid.put("enrollAllSuccess", allOk);
        if ("SUCCESS".equals(paid.get("paymentStatus"))) {
            paid.put("enrollMessage", allOk ? "课程已加入我的学习" : "开课失败，系统将自动重试；也可在订单详情手动重试");
        }
        return R.ok(paid);
    }

    @GetMapping("/{id}/payment")
    public R<Map<String, Object>> payment(@PathVariable("id") Long id) {
        boolean admin = UserContext.hasRole(Constants.ROLE_ADMIN) || UserContext.hasRole(Constants.ROLE_STAFF);
        return R.ok(paymentAppService.query(UserContext.getUserId(), id, admin));
    }

    @PostMapping("/{id}/details/{detailId}/refunds")
    public R<Map<String, Object>> applyRefund(@PathVariable("id") Long id,
                                              @PathVariable("detailId") Long detailId,
                                              @RequestBody Map<String, Object> body) {
        String reason = body == null || body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        String requestId = body == null || body.get("requestId") == null ? "" : String.valueOf(body.get("requestId"));
        Integer amount = null;
        if (body != null && body.get("amount") != null && !String.valueOf(body.get("amount")).isBlank()) {
            amount = Integer.valueOf(String.valueOf(body.get("amount")));
        }
        return R.ok(refundAppService.apply(UserContext.getUserId(), id, detailId, reason, requestId, amount));
    }

    @GetMapping("/{id}/refunds")
    public R<java.util.List<Map<String, Object>>> refunds(@PathVariable("id") Long id) {
        boolean admin = UserContext.hasRole(Constants.ROLE_ADMIN) || UserContext.hasRole(Constants.ROLE_STAFF);
        return R.ok(refundAppService.listForOrder(UserContext.getUserId(), id, admin));
    }

    /** M15.20-C：重试开课（所有者/管理员；开课失败补偿；幂等） */
    @PostMapping("/{id}/retry-enroll")
    public R<Map<String, Object>> retryEnroll(@PathVariable("id") Long id) {
        boolean admin = UserContext.hasRole(Constants.ROLE_ADMIN) || UserContext.hasRole(Constants.ROLE_STAFF);
        enrollCompensationService.manualRetry(UserContext.getUserId(), id, admin);
        var comps = enrollCompensationService.statusOf(id);
        boolean allOk = comps.stream().allMatch(c -> "SUCCESS".equals(c.getStatus()));
        return R.ok(java.util.Map.of("orderId", String.valueOf(id),
                "allSuccess", allOk,
                "message", allOk ? "开课成功，课程已加入我的学习" : "部分课程仍未开课，系统将继续自动重试"));
    }
}
