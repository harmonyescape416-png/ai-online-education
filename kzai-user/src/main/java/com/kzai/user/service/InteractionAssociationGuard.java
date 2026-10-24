package com.kzai.user.service;

import com.kzai.common.constant.Constants;
import com.kzai.common.result.R;
import com.kzai.user.client.LearningClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 互动关联校验（M10，任务书第十部分 InteractionAssociationGuard）：
 * 用户存在课程提问或回答（含 AI 系统回复归属核验）时禁止删除账号。
 * <p>
 * 由 kzai-user 经可信内部 Feign 调用 kzai-learning
 * （GET /internal/learning/interactions/count-by-user/{userId}，内部签名保护），
 * 用户服务不直接访问互动表；learning 不可用时返回负数 → 上层 fail-closed 拒绝删除。
 * 口径：提问数 + 回答数（学员追问、教师回答、AI 系统回复按 user_id 归属，不遗漏）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InteractionAssociationGuard implements UserDeletionGuard {

    private final LearningClient learningClient;

    @Override
    public String bindingName() {
        return "课程问答互动";
    }

    @Override
    public String bindingType() {
        return Constants.ASSOC_INTERACTION;
    }

    @Override
    public long count(Long userId) {
        try {
            R<Long> result = learningClient.countInteractionsByUser(userId);
            Long count = result == null ? null : result.getData();
            return count == null ? 0L : count;
        } catch (Exception e) {
            log.warn("[user] interaction association check unavailable: {}", e.getMessage());
            return -1L;
        }
    }
}
