package com.kzai.course.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kzai.common.constant.Constants;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import com.kzai.course.dto.CourseSaveRequest;
import com.kzai.course.dto.TeacherCourseQuery;
import com.kzai.course.service.TeacherCourseService;
import com.kzai.course.vo.TeacherCourseDetailVO;
import com.kzai.course.vo.TeacherCourseVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教师课程管理（M3）：列表、创建、编辑、详情、提交审核、审核结果。
 * 归属校验在 Service 层完成：非本人课程返回 403。
 */
@RestController
@RequestMapping("/teacher/courses")
@RequireRoles({Constants.ROLE_TEACHER, Constants.ROLE_ADMIN})
@RequiredArgsConstructor
public class TeacherCourseController {

    private final TeacherCourseService teacherCourseService;

    @GetMapping
    public R<IPage<TeacherCourseVO>> list(TeacherCourseQuery query) {
        return R.ok(teacherCourseService.page(query));
    }

    @PostMapping
    public R<Long> create(@Valid @RequestBody CourseSaveRequest request) {
        return R.ok(teacherCourseService.create(request));
    }

    @GetMapping("/{id}")
    public R<TeacherCourseDetailVO> detail(@PathVariable("id") Long id) {
        return R.ok(teacherCourseService.detail(id));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody CourseSaveRequest request) {
        teacherCourseService.update(id, request);
        return R.ok();
    }

    @PostMapping("/{id}/submit")
    public R<Void> submit(@PathVariable("id") Long id) {
        teacherCourseService.submit(id);
        return R.ok();
    }

    @GetMapping("/{id}/audit")
    public R<TeacherCourseVO> audit(@PathVariable("id") Long id) {
        return R.ok(teacherCourseService.auditStatus(id));
    }
}
