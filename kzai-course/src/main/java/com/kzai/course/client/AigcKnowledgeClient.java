package com.kzai.course.client;

import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/** 教师资料进入 aigc 知识库。只传资源 ID，不传文件路径。 */
@FeignClient(name = "kzai-aigc", path = "/internal/ai/knowledge",
        configuration = InternalAuthFeignConfig.class)
public interface AigcKnowledgeClient {

    @PostMapping("/submit-kb")
    R<Map<String, Object>> submitKb(@RequestBody Map<String, Object> body);

    @PostMapping("/withdraw-resource")
    R<Map<String, Object>> withdraw(@RequestBody Map<String, Object> body);

    @PostMapping("/retry-resource")
    R<Map<String, Object>> retry(@RequestBody Map<String, Object> body);

    @GetMapping("/source-status")
    R<List<Map<String, Object>>> sourceStatus(@RequestParam("ids") String ids);
}
