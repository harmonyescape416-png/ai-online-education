package com.kzai.user.client;

import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/** M13 看板：course 课程总数（内部签名）。 */
@FeignClient(contextId = "CourseStatsClient", name = "kzai-course", path = "/internal/courses",
        configuration = InternalAuthFeignConfig.class)
public interface CourseStatsClient {

    @GetMapping("/stats/count")
    R<Long> courseCount();
}
