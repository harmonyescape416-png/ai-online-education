package com.kzai.aigc.controller;

import com.kzai.aigc.supervision.SessionSupervisionService;
import com.kzai.aigc.supervision.entity.SysSessionReview;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * M13 AI 会话监管（管理员专用，/admin/supervision/sessions → kzai-aigc）。
 * 权限：后端 @RequireRoles(ADMIN) 强制；STUDENT/TEACHER 一律 403。
 */
@RestController
@RequestMapping("/admin/supervision/sessions")
@RequiredArgsConstructor
@RequireRoles({Constants.ROLE_ADMIN})
public class SessionSupervisionController {

    private final SessionSupervisionService service;

    /** 会话分页：userId/时间区间（SQL 级）+ keyword/agent（消息级，限定扫描窗口） */
    @GetMapping
    public R<Map<String, Object>> page(@RequestParam(required = false) Long userId,
                                       @RequestParam(required = false) String startTime,
                                       @RequestParam(required = false) String endTime,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String agent,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size) {
        return R.ok(service.pageSessions(userId, parseTime(startTime), parseTime(endTime),
                keyword, agent, page, size));
    }

    /** 会话详情：元数据 + 消息（过期如实标注）+ 卡片 + 复核历史 */
    @GetMapping("/{sessionId}")
    public R<Map<String, Object>> detail(@PathVariable String sessionId) {
        return R.ok(service.detail(sessionId));
    }

    /** 标记待复核（原因必填） */
    public record MarkRequest(String sessionId, String reason) { }

    @PostMapping("/{sessionId}/mark")
    public R<Map<String, Object>> mark(@PathVariable String sessionId, @RequestBody MarkRequest req) {
        if (req.reason() == null || req.reason().isBlank()) {
            throw new com.kzai.common.exception.BizException("标记原因不能为空");
        }
        long id = service.mark(sessionId, req.reason().trim(),
                UserContext.getUserId(), UserContext.getUsername());
        return R.ok(Map.of("id", String.valueOf(id)));
    }

    /** 处理复核（status=1无违规/2确认违规/3其他；仅待复核行可处理） */
    public record HandleRequest(Integer status, String handleNote) { }

    @PostMapping("/reviews/{reviewId}/handle")
    public R<Map<String, Object>> handle(@PathVariable long reviewId, @RequestBody HandleRequest req) {
        service.handle(reviewId, req.status() == null ? 0 : req.status(),
                req.handleNote(), UserContext.getUserId(), UserContext.getUsername());
        return R.ok(Map.of("handled", true));
    }

    /** 复核处理历史 */
    @GetMapping("/{sessionId}/reviews")
    public R<List<SysSessionReview>> history(@PathVariable String sessionId) {
        return R.ok(service.history(sessionId));
    }

    private static LocalDateTime parseTime(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(s.length() == 10 ? s + "T00:00:00" : s);
    }
}
