package com.kzai.aigc.controller;

import com.kzai.aigc.knowledge.KnowledgeAdminService;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 课程服务把教师资料送入知识库。只接受资源 ID，文件字节由课程服务的内部接口提供。
 */
@RestController
@RequestMapping("/internal/ai/knowledge")
@RequiredArgsConstructor
public class InternalKnowledgeController {

    private final KnowledgeAdminService adminService;

    @PostMapping("/submit-kb")
    public R<Map<String, Object>> submitKb(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("resourceId");
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new BizException("缺少 resourceId");
        }
        long resourceId = Long.parseLong(String.valueOf(raw));
        return R.ok(adminService.importTeacherResource(resourceId)).requestId(UserContext.getRequestId());
    }

    @PostMapping("/retry-resource")
    public R<Map<String, Object>> retry(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("resourceId");
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new BizException("缺少 resourceId");
        }
        return R.ok(adminService.retryTeacherResource(Long.parseLong(String.valueOf(raw))))
                .requestId(UserContext.getRequestId());
    }

    @PostMapping("/withdraw-resource")
    public R<Map<String, Object>> withdraw(@RequestBody Map<String, Object> body) {
        long resourceId = Long.parseLong(String.valueOf(body.get("resourceId")));
        int n = adminService.withdrawByResource(resourceId);
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("withdrawn", n);
        return R.ok(data).requestId(UserContext.getRequestId());
    }

    @GetMapping("/source-status")
    public R<List<Map<String, Object>>> sourceStatus(@RequestParam("ids") String ids) {
        List<Long> resourceIds = Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();
        return R.ok(adminService.sourceStatus(resourceIds)).requestId(UserContext.getRequestId());
    }
}
