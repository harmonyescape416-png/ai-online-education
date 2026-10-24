package com.kzai.learning.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.result.R;
import com.kzai.common.result.ResultCode;
import com.kzai.common.security.RequireRoles;
import com.kzai.learning.dto.LessonQuery;
import com.kzai.learning.dto.ProgressReportRequest;
import com.kzai.learning.service.LearningService;
import com.kzai.learning.vo.LessonDetailVO;
import com.kzai.learning.vo.LessonVO;
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
 * 我的学习（M5）。
 * <p>
 * 用户身份只取自服务端认证上下文，接口不接受 userId 入参，前端无法伪造身份或学习权限。
 */
@RestController
@RequestMapping("/learning/lessons")
@RequireRoles({Constants.ROLE_STUDENT, Constants.ROLE_ADMIN})
@RequiredArgsConstructor
public class LessonController {

    private final LearningService learningService;

    /** 我的学习：分页 + 学习状态筛选 + 课程名关键字 */
    @GetMapping
    public R<IPage<LessonVO>> list(LessonQuery query) {
        return R.ok(learningService.page(requireUserId(), query))
                .requestId(UserContext.getRequestId());
    }

    /**
     * 加入学习：免费课程直接入课（不生成任何订单）；付费课程需已取得真实学习权益。
     * 重复入课返回既有记录，不产生重复行。
     */
    @PostMapping
    public R<LessonVO> enroll(@RequestBody java.util.Map<String, Object> body) {
        Object courseId = body == null ? null : body.get("courseId");
        if (courseId == null) {
            throw new com.kzai.common.exception.BizException("课程ID不能为空");
        }
        return R.ok(learningService.enroll(requireUserId(), Long.valueOf(String.valueOf(courseId))))
                .requestId(UserContext.getRequestId());
    }

    /** 课程学习详情：进度、最近章节、章节目录（含锁定状态） */
    @GetMapping("/{courseId}")
    public R<LessonDetailVO> detail(@PathVariable("courseId") Long courseId) {
        return R.ok(learningService.detail(requireUserId(), courseId))
                .requestId(UserContext.getRequestId());
    }

    /**
     * 学习进度上报：真实写入 MySQL，课程完成百分比由服务端按已完成章节数计算。
     * 重复上报不倒退、不重复累计学习时长。
     */
    @PutMapping("/{courseId}/progress")
    public R<LessonDetailVO> progress(@PathVariable("courseId") Long courseId,
                                      @Valid @RequestBody ProgressReportRequest request) {
        return R.ok(learningService.reportProgress(requireUserId(), courseId, request))
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
