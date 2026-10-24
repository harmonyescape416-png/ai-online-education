package com.kzai.aigc.client;
import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.Map;

@FeignClient(contextId="RecommendationLearningClient", name="kzai-learning", configuration=InternalAuthFeignConfig.class)
public interface RecommendationLearningClient {
    @GetMapping("/internal/learning/recommendation-context")
    R<Map<String, Object>> current();
}
