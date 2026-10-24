package com.kzai.learning.controller;

import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.common.result.ResultCode;
import com.kzai.common.security.RequireRoles;
import com.kzai.learning.service.LearningPlanService;
import com.kzai.learning.vo.LearningPlanBoardVO;
import com.kzai.learning.vo.LearningPlanItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 学员学习计划。身份只取认证上下文，不接受前端传入的 userId。
 */
@RestController
@RequestMapping("/learning/plans")
@RequireRoles({Constants.ROLE_STUDENT, Constants.ROLE_ADMIN})
@RequiredArgsConstructor
public class LearningPlanController {

    private final LearningPlanService planService;

    @GetMapping
    public R<LearningPlanBoardVO> board() {
        return R.ok(planService.board(requireUserId())).requestId(UserContext.getRequestId());
    }

    @PostMapping
    public R<LearningPlanItemVO> join(@RequestBody Map<String, Object> body) {
        long courseId = requiredLong(body, "courseId", "课程ID不能为空");
        int goal = requiredInt(body, "weeklyGoal", "每周目标不能为空");
        return R.ok(planService.join(requireUserId(), courseId, goal)).requestId(UserContext.getRequestId());
    }

    @PutMapping("/{id}/goal")
    public R<LearningPlanItemVO> goal(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        int goal = requiredInt(body, "weeklyGoal", "每周目标不能为空");
        return R.ok(planService.changeGoal(requireUserId(), id, goal)).requestId(UserContext.getRequestId());
    }

    @PostMapping("/{id}/pause")
    public R<LearningPlanItemVO> pause(@PathVariable("id") Long id) {
        return R.ok(planService.pause(requireUserId(), id)).requestId(UserContext.getRequestId());
    }

    @PostMapping("/{id}/resume")
    public R<LearningPlanItemVO> resume(@PathVariable("id") Long id) {
        return R.ok(planService.resume(requireUserId(), id)).requestId(UserContext.getRequestId());
    }

    @PostMapping("/{id}/quit")
    public R<LearningPlanItemVO> quit(@PathVariable("id") Long id) {
        return R.ok(planService.quit(requireUserId(), id)).requestId(UserContext.getRequestId());
    }

    private static long requiredLong(Map<String, Object> body, String key, String message) {
        Object raw = body == null ? null : body.get(key);
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new BizException(message);
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            throw new BizException(message);
        }
    }

    private static int requiredInt(Map<String, Object> body, String key, String message) {
        Object raw = body == null ? null : body.get(key);
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new BizException(message);
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            throw new BizException(message);
        }
    }

    private static long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new AuthException(ResultCode.UNAUTHORIZED, "未登录或 token 无效");
        }
        return userId;
    }
}
