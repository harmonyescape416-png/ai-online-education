package com.kzai.learning.client;

import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/** 用户服务内部接口（M5 学名 / M10 统一审计）。 */
@FeignClient(name = "kzai-user", path = "/internal/users",
        configuration = InternalAuthFeignConfig.class)
public interface UserClient {

    @GetMapping("/names")
    R<Map<Long, String>> names(@RequestParam("ids") List<Long> ids);

    /** M10：跨服务写入统一操作审计（sys_operation_log 时间线） */
    @PostMapping("/operation-logs")
    R<Boolean> writeOperationLog(@RequestBody Map<String, Object> body);
}
