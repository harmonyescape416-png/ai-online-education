package com.kzai.user.client;

import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * AI 服务内部接口（M6：删除用户前的会话关联校验）。
 * 遵守微服务边界：用户服务不直接访问 chat_session 表。
 */
@FeignClient(name = "kzai-aigc", path = "/internal/ai",
        configuration = InternalAuthFeignConfig.class)
public interface AigcClient {

    @GetMapping("/sessions/count-by-user/{userId}")
    R<Map<String, Object>> countSessionsByUser(@PathVariable("userId") Long userId);
}
