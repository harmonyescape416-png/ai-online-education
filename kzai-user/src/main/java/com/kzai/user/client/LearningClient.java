package com.kzai.user.client;

import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 学习服务内部接口（M5：删除用户前的学习记录关联校验）。
 * 遵守微服务边界：用户服务不直接访问学习表。
 */
@FeignClient(name = "kzai-learning", path = "/internal/learning",
        configuration = InternalAuthFeignConfig.class)
public interface LearningClient {

    @GetMapping("/count-by-user/{userId}")
    R<Long> countLearningByUser(@PathVariable("userId") Long userId);

    /** M10：互动关联计数（interaction_question + interaction_reply，含 AI 系统回复归属） */
    @GetMapping("/interactions/count-by-user/{userId}")
    R<Long> countInteractionsByUser(@PathVariable("userId") Long userId);
}
