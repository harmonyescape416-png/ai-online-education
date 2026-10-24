package com.kzai.learning.controller;

import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.result.R;
import com.kzai.common.result.ResultCode;
import com.kzai.common.security.RequireRoles;
import com.kzai.learning.service.LearningService;
import com.kzai.learning.vo.AccessVO;
import com.kzai.learning.vo.ChapterLearnVO;
import com.kzai.learning.vo.LessonDetailVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课程学习（M5）：权益查询、章节目录、章节内容、标记完成。
 * <p>
 * 所有资源访问都在服务端重新校验权益，未授权章节一律 403。
 */
@RestController
@RequestMapping("/learning/courses")
@RequireRoles({Constants.ROLE_STUDENT, Constants.ROLE_ADMIN})
@RequiredArgsConstructor
public class LearnCourseController {

    private final LearningService learningService;

    /** 学习权益查询：付费课程按真实订单判定，免费课程直接可用 */
    @GetMapping("/{courseId}/access")
    public R<AccessVO> access(@PathVariable("courseId") Long courseId) {
        return R.ok(learningService.access(requireUserId(), courseId))
                .requestId(UserContext.getRequestId());
    }

    /** 章节目录（含试看/锁定状态），取自课程学习详情 */
    @GetMapping("/{courseId}/chapters")
    public R<List<ChapterLearnVO>> chapters(@PathVariable("courseId") Long courseId) {
        LessonDetailVO detail = learningService.detail(requireUserId(), courseId);
        return R.ok(detail.getChapters()).requestId(UserContext.getRequestId());
    }

    /** 章节学习详情：服务端校验权益与章节归属，未授权章节 403 */
    @GetMapping("/{courseId}/chapters/{chapterId}")
    public R<ChapterLearnVO> chapter(@PathVariable("courseId") Long courseId,
                                     @PathVariable("chapterId") Long chapterId) {
        return R.ok(learningService.chapterDetail(requireUserId(), courseId, chapterId))
                .requestId(UserContext.getRequestId());
    }

    /** 标记章节完成：仍需服务端校验归属与权益，课程进度由服务端重算 */
    @PostMapping("/{courseId}/chapters/{chapterId}/complete")
    public R<LessonDetailVO> complete(@PathVariable("courseId") Long courseId,
                                      @PathVariable("chapterId") Long chapterId) {
        return R.ok(learningService.completeChapter(requireUserId(), courseId, chapterId))
                .requestId(UserContext.getRequestId());
    }

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new AuthException(ResultCode.UNAUTHORIZED, "未登录或 token 无效");
        }
        return userId;
    }
}
