package com.kzai.course.controller;

import com.kzai.common.constant.Constants;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import com.kzai.course.dto.ChapterOrderRequest;
import com.kzai.course.dto.ChapterPreviewRequest;
import com.kzai.course.dto.ChapterSaveRequest;
import com.kzai.course.service.ChapterService;
import com.kzai.course.vo.ChapterManageVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 章节管理（M3）：列表、新增、编辑、删除（逻辑删除）、排序。
 * 章节归属课程，课程归属教师，越权返回 403。
 */
@RestController
@RequireRoles({Constants.ROLE_TEACHER, Constants.ROLE_ADMIN})
@RequiredArgsConstructor
public class TeacherChapterController {

    private final ChapterService chapterService;

    @GetMapping("/teacher/courses/{courseId}/chapters")
    public R<List<ChapterManageVO>> list(@PathVariable("courseId") Long courseId) {
        return R.ok(chapterService.list(courseId));
    }

    @PostMapping("/teacher/courses/{courseId}/chapters")
    public R<Long> add(@PathVariable("courseId") Long courseId, @Valid @RequestBody ChapterSaveRequest request) {
        return R.ok(chapterService.add(courseId, request));
    }

    @PutMapping("/teacher/chapters/{id}")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody ChapterSaveRequest request) {
        chapterService.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/teacher/chapters/{id}")
    public R<Void> delete(@PathVariable("id") Long id) {
        chapterService.delete(id);
        return R.ok();
    }

    @PutMapping("/teacher/courses/{courseId}/chapters/order")
    public R<Void> reorder(@PathVariable("courseId") Long courseId,
                           @Valid @RequestBody ChapterOrderRequest request) {
        chapterService.reorder(courseId, request);
        return R.ok();
    }

    /**
     * M5：设置章节是否可试看。
     * 试看必须由教师显式设置并落库，不做"第一章永远免费"的硬编码推断；
     * 未购学员只能访问明确标记为可试看的章节。
     */
    @PutMapping("/teacher/chapters/{id}/preview")
    public R<Void> preview(@PathVariable("id") Long id,
                           @Valid @RequestBody ChapterPreviewRequest request) {
        chapterService.setPreview(id, request.getFreePreview(), request.getVideoDurationSec());
        return R.ok();
    }
}
