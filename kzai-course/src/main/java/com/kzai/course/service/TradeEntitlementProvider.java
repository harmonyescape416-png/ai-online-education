package com.kzai.course.service;

import com.kzai.common.constant.Constants;
import com.kzai.course.client.TradeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 学习权益校验：付费课程内容仅对"已支付"用户开放（M4）。
 * <p>
 * 通过交易服务内部接口判定，课程服务不直接访问订单表，保持微服务边界。
 * 交易服务不可用时按 fail-closed 处理（视为未取得权益）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "kzai.trade.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class TradeEntitlementProvider implements EntitlementProvider {

    private final TradeClient tradeClient;

    @Override
    public boolean hasPaidEntitlement(Long userId, Long courseId) {
        if (userId == null || courseId == null) {
            return false;
        }
        try {
            var result = tradeClient.hasPaidEntitlement(userId, courseId);
            Boolean data = result == null ? null : result.getData();
            return Boolean.TRUE.equals(data);
        } catch (Exception e) {
            log.warn("[course] entitlement check unavailable, deny by default: {}", e.getMessage());
            return false;
        }
    }

    /** 仅管理员/上传人可绕过权益校验（用于审核预览等场景） */
    static boolean adminBypass() {
        return com.kzai.common.context.UserContext.hasRole(Constants.ROLE_ADMIN);
    }
}
