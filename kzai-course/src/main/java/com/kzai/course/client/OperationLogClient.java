package com.kzai.course.client;

import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/** M13：course → user 统一操作审计（sys_operation_log；内部签名）。 */
@FeignClient(contextId = "OpLog-course", name = "kzai-user", path = "/internal/users",
        configuration = InternalAuthFeignConfig.class)
public interface OperationLogClient {

    @PostMapping("/operation-logs")
    R<Boolean> writeOperationLog(@RequestBody Map<String, Object> body);
}
