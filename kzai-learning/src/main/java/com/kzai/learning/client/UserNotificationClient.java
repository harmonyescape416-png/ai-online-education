package com.kzai.learning.client;

import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * 通知服务内部接口（M11）：投递站内通知事件（服务端身份 + 内部签名）。
 * <p>
 * 幂等语义：重试必须复用相同 {@code eventKey}，user 侧唯一约束去重。
 */
@FeignClient(name = "kzai-user", contextId = "userNotificationClient", path = "/internal/notifications",
        configuration = InternalAuthFeignConfig.class)
public interface UserNotificationClient {

    @PostMapping
    R<Map<String, Object>> publish(@RequestBody Map<String, Object> body);
}
