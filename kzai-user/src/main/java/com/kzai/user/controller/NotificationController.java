package com.kzai.user.controller;

import com.kzai.common.context.UserContext;
import com.kzai.common.result.R;
import com.kzai.user.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 站内通知（M11 对外接口）。
 * <p>
 * 身份一律取自 JWT（UserContext），不接收任何 recipientId 参数；
 * 越权访问返回受控 404（"通知不存在或无权访问"），不泄露他人通知是否存在。
 * 未登录由 Gateway 认证过滤器返回 401。
 */
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** 未读数量（真实后端统计，前端不得写死） */
    @GetMapping("/unread-count")
    public R<Map<String, Object>> unreadCount() {
        long userId = UserContext.getUserId();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("count", String.valueOf(notificationService.unreadCount(userId)));
        return R.ok(data).requestId(UserContext.getRequestId());
    }

    /** 通知列表：创建时间倒序 + 分页 + 类型/已读筛选 */
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(required = false) String type,
                                       @RequestParam(required = false) Integer readStatus,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size) {
        long userId = UserContext.getUserId();
        return R.ok(notificationService.list(userId, type, readStatus, page, size))
                .requestId(UserContext.getRequestId());
    }

    /** 详情（首次打开自动已读） */
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable("id") long id) {
        return R.ok(notificationService.detail(UserContext.getUserId(), id))
                .requestId(UserContext.getRequestId());
    }

    /** 单条标记已读（幂等） */
    @PutMapping("/{id}/read")
    public R<Map<String, Object>> read(@PathVariable("id") long id) {
        notificationService.markRead(UserContext.getUserId(), id);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", String.valueOf(id));
        data.put("readStatus", 1);
        return R.ok(data)
                .requestId(UserContext.getRequestId());
    }

    /** 全部标记已读（仅本人未读且未删除） */
    @PutMapping("/read-all")
    public R<Map<String, Object>> readAll() {
        long updated = notificationService.markAllRead(UserContext.getUserId());
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("updated", String.valueOf(updated));
        return R.ok(data)
                .requestId(UserContext.getRequestId());
    }

    /** 删除（接收者维度逻辑删除，幂等） */
    @DeleteMapping("/{id}")
    public R<Map<String, Object>> delete(@PathVariable("id") long id) {
        notificationService.delete(UserContext.getUserId(), id);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", String.valueOf(id));
        data.put("deleted", true);
        return R.ok(data)
                .requestId(UserContext.getRequestId());
    }
}
