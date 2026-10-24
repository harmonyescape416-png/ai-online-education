package com.kzai.course.client;

import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/** 通知服务内部接口（M11）：投递站内通知事件（内部签名 + 服务端身份） */
@FeignClient(name = "kzai-user", contextId = "courseNotificationClient", path = "/internal/notifications",
        configuration = InternalAuthFeignConfig.class)
public interface UserNotificationClient {

    @PostMapping
    R<Map<String, Object>> publish(@RequestBody Map<String, Object> body);
}
