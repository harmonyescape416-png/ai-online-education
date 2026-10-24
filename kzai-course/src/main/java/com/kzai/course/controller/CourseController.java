package com.kzai.course.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kzai.common.result.R;
import com.kzai.course.dto.CourseQuery;
import com.kzai.course.entity.CourseCategory;
import com.kzai.course.mapper.CourseCategoryMapper;
import com.kzai.course.service.CourseService;
import com.kzai.course.vo.CourseDetailVO;
import com.kzai.course.vo.CourseVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课程接口（系统设计说明书 5.2.3）：GET /courses、/courses/{id}、/courses/search。
 */
@RestController
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;
    private final CourseCategoryMapper categoryMapper;

    @GetMapping("/courses")
    public R<IPage<CourseVO>> list(CourseQuery query) {
        return R.ok(courseService.page(query));
    }

    @GetMapping("/courses/search")
    public R<List<CourseVO>> search(@RequestParam("keyword") String keyword) {
        return R.ok(courseService.search(keyword));
    }

    @GetMapping("/courses/categories")
    public R<List<CourseCategory>> categories() {
        return R.ok(categoryMapper.selectList(
                Wrappers.<CourseCategory>lambdaQuery()
                        .eq(CourseCategory::getStatus, 1)
                        .orderByAsc(CourseCategory::getLevel)
                        .orderByAsc(CourseCategory::getSort)));
    }

    @GetMapping("/courses/{id}")
    public R<CourseDetailVO> detail(@PathVariable("id") Long id) {
        return R.ok(courseService.detail(id));
    }
}
