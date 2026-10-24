package com.kzai.user.controller;

import com.kzai.common.result.R;
import com.kzai.user.entity.SysNotification;
import com.kzai.user.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * user 内部接口（M11）：业务服务（learning / course）投递站内通知事件。
 * <p>
 * 仅允许服务端内部签名调用（{@code X-Internal-Sign}），网关拒绝外部访问 /internal/**。
 * 幂等由 {@code UNIQUE(recipient_id, event_key)} 保证：Feign 超时重试使用相同 eventKey，
 * 不会产生第二条通知。
 * <p>
 * 注意：本接口<b>不</b>接受任何业务正文以外的受保护内容，也不由调用方指定通知可见性。
 */
@Slf4j
@RestController
@RequestMapping("/internal/notifications")
@RequiredArgsConstructor
public class InternalNotificationController {

    private final NotificationService notificationService;

    /**
     * 投递通知事件。
     *
     * @param body eventKey / recipientId / type / title / content / bizType / bizId / requestId
     */
    @PostMapping
    public R<Map<String, Object>> publish(@RequestBody Map<String, Object> body) {
        String eventKey = str(body.get("eventKey"));
        long recipientId = body.get("recipientId") == null ? 0
                : Long.parseLong(String.valueOf(body.get("recipientId")));
        String type = str(body.get("type"));
        if (type == null || type.isBlank()) {
            type = SysNotification.TYPE_QUESTION_REPLY;
        }
        String bizType = str(body.get("bizType"));
        String bizId = str(body.get("bizId"));
        long id = notificationService.publish(eventKey, recipientId, type,
                str(body.get("title")), str(body.get("content")), bizType, bizId);
        return R.ok(Map.of("id", String.valueOf(id), "eventKey", String.valueOf(eventKey)));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
