package com.kzai.user.client;

import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/** M13 看板：learning 提问/待处理/趋势（内部签名）。 */
@FeignClient(contextId = "LearningStatsClient", name = "kzai-learning", path = "/internal/learning/stats",
        configuration = InternalAuthFeignConfig.class)
public interface LearningStatsClient {

    @GetMapping("/questions")
    R<Map<String, Object>> questions(@RequestParam("days") int days);
}
