package com.kzai.course.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kzai.common.constant.Constants;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import com.kzai.course.dto.AuditHandleRequest;
import com.kzai.course.service.AuditService;
import com.kzai.course.vo.AuditPreviewVO;
import com.kzai.course.vo.TeacherCourseVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员课程审核（M3）：待审核列表、预览、通过、驳回、强制下架。
 * <p>
 * 通知闭环属 M11 范围，本阶段审核结果仅体现在 course_audit 与接口返回中。
 */
@RestController
@RequestMapping("/admin/courses")
@RequireRoles({Constants.ROLE_ADMIN, Constants.ROLE_STAFF})
@RequiredArgsConstructor
public class AdminCourseAuditController {

    private final AuditService auditService;

    @GetMapping("/audits")
    public R<IPage<TeacherCourseVO>> pendingList(
            @RequestParam(value = "pageNum", required = false) Integer pageNum,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "name", required = false) String name) {
        return R.ok(auditService.pendingList(pageNum, pageSize, name));
    }

    @GetMapping("/{id}/preview")
    public R<AuditPreviewVO> preview(@PathVariable("id") Long id) {
        return R.ok(auditService.preview(id));
    }

    @PostMapping("/{id}/audit/approve")
    public R<Void> approve(@PathVariable("id") Long id) {
        auditService.approve(id);
        return R.ok();
    }

    @PostMapping("/{id}/audit/reject")
    public R<Void> reject(@PathVariable("id") Long id, @Valid @RequestBody AuditHandleRequest request) {
        auditService.reject(id, request.getReason());
        return R.ok();
    }

    @PostMapping("/{id}/offline")
    public R<Void> offline(@PathVariable("id") Long id, @Valid @RequestBody AuditHandleRequest request) {
        auditService.offline(id, request.getReason());
        return R.ok();
    }
}
