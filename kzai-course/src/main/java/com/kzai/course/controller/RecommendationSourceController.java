package com.kzai.course.controller;

import com.kzai.common.result.R;
import com.kzai.course.mapper.CourseMapper;
import com.kzai.course.service.CourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 仅内部签名可访问。只导出公开课程字段，不读取课程附件、知识库或付费正文。 */
@RestController
@RequestMapping("/internal/courses/recommendation-source")
@RequiredArgsConstructor
public class RecommendationSourceController {
    private final CourseMapper mapper;
    private final CourseService courses;

    @GetMapping
    public R<List<Map<String, Object>>> page(@RequestParam(defaultValue="0") long afterId,
                                            @RequestParam(defaultValue="50") int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : mapper.recommendationIds(Math.max(0, afterId), Math.max(1, Math.min(100, limit)))) {
            var course = courses.aiDetail(id);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("courseId", String.valueOf(id));
            row.put("visible", course != null);
            if (course != null) row.put("course", course);
            out.add(row);
        }
        return R.ok(out);
    }
}
