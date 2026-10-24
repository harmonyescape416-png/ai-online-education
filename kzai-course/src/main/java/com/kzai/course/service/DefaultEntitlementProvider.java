package com.kzai.course.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认权益实现：未接入交易服务时（如课程服务独立测试），
 * 付费课程一律视为"未取得权益"（fail-closed），不对普通学员开放付费资源。
 */
@Component
@ConditionalOnProperty(name = "kzai.trade.enabled", havingValue = "false")
public class DefaultEntitlementProvider implements EntitlementProvider {

    @Override
    public boolean hasPaidEntitlement(Long userId, Long courseId) {
        return false;
    }
}
