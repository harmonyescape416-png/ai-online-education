package com.kzai.learning.controller;

import com.kzai.common.context.UserContext;
import com.kzai.common.result.R;
import com.kzai.learning.dto.QuestionCreateRequest;
import com.kzai.learning.dto.QuestionReplyRequest;
import com.kzai.learning.service.QuestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 学员课程问答（M10）。身份一律取网关注入的可信登录用户；
 * 匿名仅影响展示；原始 /questions API 语义保留。
 */
@RestController
@RequestMapping("/questions")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;

    /** 提问（POST /questions，原始 API） */
    @PostMapping
    public R<Map<String, Object>> create(@Valid @RequestBody QuestionCreateRequest req) {
        return R.ok(questionService.createQuestion(UserContext.getUserId(), req))
                .requestId(UserContext.getRequestId());
    }

    /** 课程问答区（GET /questions?courseId=，原始 API 保留 query 形式） */
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam("courseId") long courseId,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size) {
        return R.ok(questionService.courseQuestions(UserContext.getUserId(),
                        UserContext.getRoles(), courseId, page, size))
                .requestId(UserContext.getRequestId());
    }

    /** 我的问答（扩展） */
    @GetMapping("/mine")
    public R<Map<String, Object>> mine(@RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size) {
        return R.ok(questionService.mine(UserContext.getUserId(), page, size))
                .requestId(UserContext.getRequestId());
    }

    /** 问题详情 + 回复树 + AI 任务状态（GET /questions/{id}，原始 API） */
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable("id") long id) {
        return R.ok(questionService.detail(UserContext.getUserId(), UserContext.getRoles(), id))
                .requestId(UserContext.getRequestId());
    }

    /** 追问/回复（POST /questions/{id}/replies，原始 API） */
    @PostMapping("/{id}/replies")
    public R<Map<String, Object>> reply(@PathVariable("id") long id,
                                        @Valid @RequestBody QuestionReplyRequest req) {
        return R.ok(questionService.reply(UserContext.getUserId(), id, req))
                .requestId(UserContext.getRequestId());
    }
}
