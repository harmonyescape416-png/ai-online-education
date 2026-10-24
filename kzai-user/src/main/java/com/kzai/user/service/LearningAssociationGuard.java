package com.kzai.user.service;

import com.kzai.common.constant.Constants;
import com.kzai.common.result.R;
import com.kzai.user.client.LearningClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 学习记录关联校验（M5）：用户存在学习记录时禁止删除账号。
 * <p>
 * 通过学习服务内部接口统计，用户服务不访问学习表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LearningAssociationGuard implements UserDeletionGuard {

    private final LearningClient learningClient;

    @Override
    public String bindingName() {
        return "学习记录";
    }

    @Override
    public String bindingType() {
        return Constants.ASSOC_LEARNING;
    }

    @Override
    public long count(Long userId) {
        try {
            R<Long> result = learningClient.countLearningByUser(userId);
            Long count = result == null ? null : result.getData();
            return count == null ? 0L : count;
        } catch (Exception e) {
            // 学习服务不可用时返回负数，由上层保守拒绝删除
            log.warn("[user] learning association check unavailable: {}", e.getMessage());
            return -1L;
        }
    }
}
