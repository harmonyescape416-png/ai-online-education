package com.kzai.trade.client;

import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import com.kzai.trade.dto.CourseSnapshot;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 课程服务内部接口（M4：预下单时获取实时课程数据）。
 * <p>
 * 携带内部签名，网关拒绝对外暴露 /internal/**，前端无法冒充内部服务调用。
 */
@FeignClient(name = "kzai-course", path = "/internal/courses",
        configuration = InternalAuthFeignConfig.class)
public interface CourseClient {

    /** 批量查询课程快照，ids 为逗号分隔的课程ID */
    @GetMapping("/batch")
    R<List<CourseSnapshot>> batch(@RequestParam("ids") String ids);
}
