package com.kzai.learning.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.result.*;
import com.kzai.learning.entity.LearningLesson;
import com.kzai.learning.mapper.LearningLessonMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/internal/learning/recommendation-context")
@RequiredArgsConstructor
public class RecommendationContextController {
    private final LearningLessonMapper lessons;
    @GetMapping
    public R<Map<String, Object>> current() {
        Long id = UserContext.getUserId();
        if (id == null) throw new AuthException(ResultCode.UNAUTHORIZED, "未登录");
        var rows = lessons.selectList(Wrappers.<LearningLesson>lambdaQuery().eq(LearningLesson::getUserId, id));
        return R.ok(Map.of("enrolled", rows.stream().map(r -> String.valueOf(r.getCourseId())).distinct().toList(),
                "completed", rows.stream().filter(r -> Integer.valueOf(2).equals(r.getStatus()))
                        .map(r -> String.valueOf(r.getCourseId())).distinct().toList()));
    }
}
