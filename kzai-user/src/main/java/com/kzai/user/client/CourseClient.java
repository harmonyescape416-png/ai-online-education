package com.kzai.user.client;

import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 课程服务内部接口（M2：删除用户前的关联校验）。
 * 遵守微服务边界：不直接操作课程服务的 Mapper，仅通过服务间调用。
 */
@FeignClient(name = "kzai-course", path = "/internal/courses",
        configuration = InternalAuthFeignConfig.class)
public interface CourseClient {

    @GetMapping("/count-by-creator/{userId}")
    R<Long> countByCreator(@PathVariable("userId") Long userId);
}
