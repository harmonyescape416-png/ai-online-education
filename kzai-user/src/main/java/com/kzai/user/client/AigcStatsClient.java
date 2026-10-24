package com.kzai.user.client;

import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/** M13 看板：aigc 会话/模型指标/趋势（内部签名）。 */
@FeignClient(contextId = "AigcStatsClient", name = "kzai-aigc", path = "/internal/ai/stats",
        configuration = InternalAuthFeignConfig.class)
public interface AigcStatsClient {

    @GetMapping("/overview")
    R<Map<String, Object>> overview(@RequestParam("days") int days);
}
