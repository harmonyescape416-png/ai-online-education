package com.kzai.learning.controller;

import com.kzai.common.result.R;
import com.kzai.learning.service.LearningService;
import lombok.RequiredArgsConstructor;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学习服务内部接口：供用户服务在删除账号前校验学习/互动关联。
 * 网关拒绝外部访问 /internal/**，且必须携带内部签名。
 */
@RestController
@RequestMapping("/internal/learning")
@RequiredArgsConstructor
public class InternalLearningController {

    private final LearningService learningService;
    private final com.kzai.learning.mapper.InteractionQuestionMapper interactionQuestionMapper;
    private final com.kzai.learning.mapper.InteractionReplyMapper interactionReplyMapper;

    @GetMapping("/count-by-user/{userId}")
    public R<Long> countByUser(@PathVariable("userId") Long userId) {
        return R.ok(learningService.countByUser(userId));
    }

    /**
     * M15.16：支付成功后开课（trade 支付回调查询）——为学员创建学习档案（learning_lesson）。
     * 调用方：kzai-trade（支付状态已置为已支付后调用）；enroll 内部走权益 resolve（已支付订单=允许），
     * 幂等：重复调用返回既有档案。
     */
    @PostMapping("/enroll-for-paid")
    public R<Map<String, Object>> enrollForPaid(@RequestBody Map<String, Object> body) {
        Long userId = Long.valueOf(String.valueOf(body.get("userId")));
        Long courseId = Long.valueOf(String.valueOf(body.get("courseId")));
        com.kzai.learning.vo.LessonVO vo = learningService.enroll(userId, courseId);
        return R.ok(Map.of("lessonId", String.valueOf(vo.getId()),
                "courseId", String.valueOf(vo.getCourseId()),
                "status", vo.getStatus() == null ? 0 : vo.getStatus()));
    }

    /**
     * M10：用户互动关联计数（提问数 + 回答数，含系统 AI 回复归属）——用户删除 Guard 用。
     * fail-closed 由调用方（kzai-user InteractionAssociationGuard）承担。
     */
    @GetMapping("/interactions/count-by-user/{userId}")
    public R<Long> countInteractions(@PathVariable("userId") Long userId) {
        Long q = interactionQuestionMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.kzai.learning.entity.InteractionQuestion>()
                        .eq(com.kzai.learning.entity.InteractionQuestion::getUserId, userId));
        Long r = interactionReplyMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.kzai.learning.entity.InteractionReply>()
                        .eq(com.kzai.learning.entity.InteractionReply::getUserId, userId));
        long total = (q == null ? 0 : q) + (r == null ? 0 : r);
        return R.ok(total);
    }
}
