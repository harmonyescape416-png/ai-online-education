package com.kzai.aigc.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.client.CourseAiItem;
import com.kzai.aigc.client.CourseClient;
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
 * 课程查询工具（M7，只读）。
 * <p>
 * 两个工具：{@code searchCourses}（关键词搜索）、{@code getCourseDetail}（按 ID 查详情）。
 * <ul>
 *   <li>真实数据一律来自 kzai-course 服务（OpenFeign + 内部签名），绝不使用模型记忆、
 *       硬编码课程或静态数组伪造结果；模型自行生成的课程名称/价格不作为数据库事实；</li>
 *   <li>课程可见性由课程服务判定（已上架、分类启用、未逻辑删除）；
 *       未上架/禁用分类课程不会被返回，更不会被推荐为可购买；</li>
 *   <li>金额按分返回并提供精确元展示值；课程 ID 按字符串传输；</li>
 *   <li>查询为空明确告知未找到；课程服务不可用返回受控错误（不抛出到 SSE 主流程）；</li>
 *   <li>执行线程先从 ToolContext 恢复可信 UserContext（Feign 签名需要），结束必清理，
 *       防止 ThreadLocal 在线程池复用时泄露身份。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseTools {

    public static final String NAME_SEARCH = "searchCourses";
    public static final String NAME_DETAIL = "getCourseDetail";

    public static final String CARD_COURSE = "COURSE_CARD";

    private final CourseClient courseClient;
    private final ToolAudit audit;
    private final ObjectMapper objectMapper;

    /** Spring AI 1.0.0-M6：FunctionCallback.builder().function(name, BiFunction) 构建 */
    public List<FunctionCallback> callbacks() {
        return List.of(
                FunctionCallback.builder()
                        .function(NAME_SEARCH,
                                (BiFunction<CourseSearchRequest, ToolContext, String>) this::searchCourses)
                        .description("按关键词搜索在线教育平台已上架课程，返回真实课程列表"
                                + "（课程ID、名称、价格（分）、元展示价、封面、简介、章节数、是否可购买）。"
                                + "适合\"推荐/找/有哪些 XX 课程\"类问题。价格以数据库为准，禁止编造。")
                        .inputType(CourseSearchRequest.class)
                        .build(),
                FunctionCallback.builder()
                        .function(NAME_DETAIL,
                                (BiFunction<CourseDetailRequest, ToolContext, String>) this::getCourseDetail)
                        .description("按课程ID查询课程详情（名称、价格、简介、章节数、章节标题列表、是否可购买）。"
                                + "当用户问某门课的价格、章节或购买前核实时使用。"
                                + "M15 约束：章节标题只能引用返回的 chapters 数组；"
                                + "chapters 缺失时只说明章节数量，严禁自行编写章节名称。courseId 为字符串数字。")
                        .inputType(CourseDetailRequest.class)
                        .build());
    }

    /** 课程搜索（模型回调入口，MOCK 脚本亦复用） */
    public String searchCourses(CourseSearchRequest request, ToolContext toolContext) {
        long t0 = System.currentTimeMillis();
        ToolResultHolder round = holderOf(toolContext);
        String requestId = strOf(toolContext, ToolContextKeys.REQUEST_ID);
        String sessionId = strOf(toolContext, ToolContextKeys.SESSION_ID);
        String keyword = request == null ? null : trimToNull(request.getKeyword());
        if (round != null && !round.tryAcquireToolCall(NAME_SEARCH)) {
            audit.record(NAME_SEARCH, requestId, sessionId, since(t0), true, 0, "TOOL_BUDGET_EXCEEDED");
            return json(Map.of("status", "TOOL_BUDGET_EXCEEDED",
                    "message", "本轮工具调用次数已达上限，请基于已有搜索结果直接回答用户"));
        }
        Integer limit = request == null ? null : request.getLimit();
        if (keyword == null) {
            audit.record(NAME_SEARCH, requestId, sessionId, since(t0), true, 0, ToolAudit.ERR_BAD_REQUEST);
            return json(Map.of("status", "EMPTY_KEYWORD",
                    "message", "缺少课程关键词，请向用户询问想找的课程主题，例如 Java、Spring Boot、AI 大模型"));
        }
        try {
            restoreUserContext(toolContext);
            R<List<CourseAiItem>> resp = courseClient.aiSearch(keyword, limit);
            if (resp == null || !resp.isSuccess()) {
                return controlledUnavailable(NAME_SEARCH, requestId, sessionId, t0, resp);
            }
            List<CourseAiItem> items = resp.getData() == null ? List.of() : resp.getData();
            if (items.isEmpty()) {
                audit.record(NAME_SEARCH, requestId, sessionId, since(t0), true, 0, ToolAudit.ERR_NOT_FOUND);
                return json(Map.of("status", "NOT_FOUND",
                        "message", "未找到与「" + keyword + "」相关的已上架课程。请如实告知用户未找到，"
                                + "可建议更换关键词，不得编造课程。"));
            }
            int cards = 0;
            for (CourseAiItem item : items) {
                if (round != null && round.addCard(CARD_COURSE, "course:" + item.getId(), courseCardPayload(item))) {
                    cards++;
                }
            }
            audit.record(NAME_SEARCH, requestId, sessionId, since(t0), true, items.size(), null);
            List<Map<String, Object>> courses = items.stream().map(CourseTools::modelCourse).toList();
            return json(Map.of("status", "OK", "keyword", keyword, "count", items.size(), "courses", courses,
                    "note", "金额：priceCents 为分，priceYuan 为元展示值；purchasable=false 时请说明 reason，不要推荐其购买"));
        } catch (RuntimeException ex) {
            log.warn("[tool] searchCourses 异常: {}", ex.getMessage(), ex);
            audit.record(NAME_SEARCH, requestId, sessionId, since(t0), false, 0, ToolAudit.ERR_SERVICE_UNAVAILABLE);
            return json(Map.of("status", "COURSE_SERVICE_UNAVAILABLE",
                    "message", "课程服务暂时不可用，请如实告知用户稍后重试"));
        } finally {
            UserContext.clear();
        }
    }

    /** 课程详情（模型回调入口，MOCK 脚本亦复用） */
    public String getCourseDetail(CourseDetailRequest request, ToolContext toolContext) {
        long t0 = System.currentTimeMillis();
        ToolResultHolder round = holderOf(toolContext);
        String requestId = strOf(toolContext, ToolContextKeys.REQUEST_ID);
        String sessionId = strOf(toolContext, ToolContextKeys.SESSION_ID);
        String courseId = request == null ? null : trimToNull(request.getCourseId());
        Long id = parseCourseId(courseId);
        if (round != null && !round.tryAcquireToolCall(NAME_DETAIL)) {
            audit.record(NAME_DETAIL, requestId, sessionId, since(t0), true, 0, "TOOL_BUDGET_EXCEEDED");
            return json(Map.of("status", "TOOL_BUDGET_EXCEEDED",
                    "message", "本轮工具调用次数已达上限，请基于已有结果直接回答用户"));
        }
        if (id == null) {
            audit.record(NAME_DETAIL, requestId, sessionId, since(t0), true, 0, ToolAudit.ERR_BAD_REQUEST);
            return json(Map.of("status", "BAD_REQUEST",
                    "message", "courseId 非法。可先调用 searchCourses 获取真实课程ID"));
        }
        try {
            restoreUserContext(toolContext);
            R<CourseAiItem> resp = courseClient.aiDetail(id);
            if (resp == null || !resp.isSuccess()) {
                return controlledUnavailable(NAME_DETAIL, requestId, sessionId, t0, resp);
            }
            CourseAiItem course = resp.getData();
            if (course == null) {
                audit.record(NAME_DETAIL, requestId, sessionId, since(t0), true, 0, ToolAudit.ERR_NOT_FOUND);
                return json(Map.of("status", "NOT_FOUND",
                        "message", "未找到该课程，或课程未上架/分类不可见。请如实告知，不得编造课程信息"));
            }
            if (round != null) {
                round.addCard(CARD_COURSE, "course:" + course.getId(), courseCardPayload(course));
            }
            audit.record(NAME_DETAIL, requestId, sessionId, since(t0), true, 1, null);
            Map<String, Object> body = new LinkedHashMap<>(modelCourse(course));
            body.put("status", "OK");
            body.put("introduce", course.getIntroduce());
            if (course.getChapterCount() != null) {
                body.put("chapterCount", course.getChapterCount());
            }
            // M15 REAL 防编造约束：章节标题唯一合法来源——工具返回的真实 chapters。
            // 无 chapters（或为空）时模型只允许说「共 N 章」，不得自行编写任何章节名称。
            if (course.getChapters() != null && !course.getChapters().isEmpty()) {
                body.put("chapters", course.getChapters());
            }
            body.put("note", "价格与章节数均为数据库事实；章节标题以 chapters 数组为准，"
                    + "chapters 缺失时只说明章节数量，禁止自行编写章节名称；purchasable=false 时说明 reason");
            return json(body);
        } catch (RuntimeException ex) {
            log.warn("[tool] getCourseDetail 异常: {}", ex.getMessage());
            audit.record(NAME_DETAIL, requestId, sessionId, since(t0), false, 0, ToolAudit.ERR_SERVICE_UNAVAILABLE);
            return json(Map.of("status", "COURSE_SERVICE_UNAVAILABLE",
                    "message", "课程服务暂时不可用，请如实告知用户稍后重试"));
        } finally {
            UserContext.clear();
        }
    }

    // ------------------------------------------------------------------

    /** 1003 COURSE_CARD 载荷（与前端组件字段一一对应，全部来自课程服务真实数据） */
    public static Map<String, Object> courseCardPayload(CourseAiItem c) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("courseId", c.getId());
        p.put("name", c.getName());
        p.put("coverUrl", c.getCoverUrl());
        p.put("price", c.getPrice() == null ? 0 : c.getPrice());
        p.put("priceYuan", c.getPriceYuan() == null ? "0.00" : c.getPriceYuan());
        p.put("introduce", c.getIntroduce());
        p.put("chapterCount", c.getChapterCount() == null ? 0 : c.getChapterCount());
        p.put("free", c.getFree());
        p.put("purchasable", Boolean.TRUE.equals(c.getPurchasable()));
        p.put("reason", c.getReason());
        return p;
    }

    /** 给模型的紧凑摘要（不含封面等渲染字段） */
    private static Map<String, Object> modelCourse(CourseAiItem c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("courseId", c.getId());
        m.put("name", c.getName());
        m.put("priceCents", c.getPrice());
        m.put("priceYuan", c.getPriceYuan());
        m.put("free", c.getFree());
        m.put("chapterCount", c.getChapterCount());
        m.put("purchasable", Boolean.TRUE.equals(c.getPurchasable()));
        m.put("reason", c.getReason());
        return m;
    }

    private <T> String controlledUnavailable(String tool, String requestId, String sessionId,
                                             long t0, R<T> resp) {
        String bizMsg = resp == null ? null : resp.getMsg();
        log.warn("[tool] {} 课程服务响应异常: {}", tool, bizMsg);
        audit.record(tool, requestId, sessionId, since(t0), false, 0, ToolAudit.ERR_SERVICE_UNAVAILABLE);
        return json(Map.of("status", "COURSE_SERVICE_UNAVAILABLE",
                "message", "课程服务暂时不可用，请如实告知用户稍后重试"));
    }

    /** 从 ToolContext 恢复可信用户上下文（身份只来自网关验证后的认证上下文） */
    public static void restoreUserContext(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return;
        }
        Object uid = toolContext.getContext().get(ToolContextKeys.USER_ID);
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) toolContext.getContext().get(ToolContextKeys.ROLES);
        String requestId = strOf(toolContext, ToolContextKeys.REQUEST_ID);
        if (uid instanceof Long l) {
            UserContext.set(l, null, null, roles == null ? List.of() : roles, requestId);
        }
    }

    public static ToolResultHolder holderOf(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object round = toolContext.getContext().get(ToolContextKeys.ROUND);
        return round instanceof ToolResultHolder h ? h : null;
    }

    public static Long userIdOf(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object uid = toolContext.getContext().get(ToolContextKeys.USER_ID);
        return uid instanceof Long l ? l : null;
    }

    public static String strOf(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object v = toolContext.getContext().get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static Long parseCourseId(String courseId) {
        if (courseId == null) {
            return null;
        }
        String s = courseId.trim();
        if (s.isEmpty() || s.length() > 20 || !s.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String json(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            return "{\"status\":\"ERROR\",\"message\":\"工具结果序列化失败\"}";
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static long since(long t0) {
        return System.currentTimeMillis() - t0;
    }
}
