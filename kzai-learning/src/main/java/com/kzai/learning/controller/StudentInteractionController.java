package com.kzai.learning.controller;

import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.result.R;
import com.kzai.common.result.ResultCode;
import com.kzai.learning.service.ReplyExtraOpsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M15.21：学员侧互动操作（采纳标记——学员可访问接口；不开放教师 Controller）。
 */
@RestController
@RequestMapping("/questions")
@RequiredArgsConstructor
public class StudentInteractionController {

    private final ReplyExtraOpsService replyExtraOpsService;

    private long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new AuthException(ResultCode.UNAUTHORIZED, "未登录或 token 无效");
        }
        return userId;
    }

    /** 学员：AI 回复标记「有帮助」（唯一记录幂等） */
    @PostMapping("/{replyId}/helpful")
    public R<Map<String, Object>> markHelpful(@PathVariable("replyId") Long replyId) {
        long userId = requireUserId();
        long count = replyExtraOpsService.markHelpful(replyId, userId);
        return R.ok(Map.of("helpfulCount", count, "marked", true));
    }

    /** 学员：撤销「有帮助」（仅本人记录） */
    @PostMapping("/{replyId}/helpful/undo")
    public R<Map<String, Object>> unmarkHelpful(@PathVariable("replyId") Long replyId) {
        long userId = requireUserId();
        long count = replyExtraOpsService.unmarkHelpful(replyId, userId);
        return R.ok(Map.of("helpfulCount", count, "marked", false));
    }

    /** 学员：查询标记状态（刷新后保持） */
    @org.springframework.web.bind.annotation.GetMapping("/{replyId}/helpful/status")
    public R<Map<String, Object>> helpfulStatus(@PathVariable("replyId") Long replyId) {
        long userId = requireUserId();
        return R.ok(Map.of("helpfulCount", replyExtraOpsService.countHelpful(replyId),
                "marked", replyExtraOpsService.isMarkedBy(replyId, userId)));
    }
}
