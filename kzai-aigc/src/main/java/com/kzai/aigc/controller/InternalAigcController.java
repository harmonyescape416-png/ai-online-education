package com.kzai.aigc.controller;

import com.kzai.aigc.session.ChatSessionService;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * aigc 内部接口（M6 删除校验 + M10 课程问答生成）。
 * 必须由网关注入的 X-Internal-Sign 校验通过才能访问，否则由 UserContextWebConfig 直接拒绝
 * ——不开放任何无鉴权内部 AI 接口。
 */
@RestController
@RequestMapping("/internal/ai")
@RequiredArgsConstructor
public class InternalAigcController {

    private final ChatSessionService sessionService;
    private final com.kzai.aigc.service.CourseAnswerGenerateService generateService;

    @GetMapping("/sessions/count-by-user/{userId}")
    public R<Map<String, Object>> countByUser(@PathVariable("userId") Long userId) {
        long count = sessionService.countByUser(userId);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("userId", String.valueOf(userId));
        data.put("count", count);
        return R.ok(data).requestId(UserContext.getRequestId());
    }

    /**
     * M10：课程问答 AI 生成（learning 异步 Worker 经可信 Feign 调用）。
     * 以【提问学员】身份做 RAG 权益过滤；MOCK 显著标识；失败抛出明确异常由调用方落任务状态。
     */
    @PostMapping("/generate")
    public R<Map<String, Object>> generate(@RequestBody Map<String, Object> body) {
        long askerUserId = Long.parseLong(String.valueOf(body.get("askerUserId")));
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) body.getOrDefault("askerRoles", List.of());
        Map<String, Object> out = generateService.generate(
                askerUserId,
                roles,
                str(body.get("title")),
                str(body.get("description")),
                str(body.get("courseName")),
                str(body.get("chapterTitle")));
        return R.ok(out).requestId(UserContext.getRequestId());
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
