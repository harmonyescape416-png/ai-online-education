package com.kzai.user.service;

import com.kzai.common.constant.Constants;
import com.kzai.common.result.R;
import com.kzai.user.client.TradeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 订单关联校验：用户存在订单（含已关闭）时禁止删除账号。
 * <p>
 * 通过交易服务内部接口统计，用户服务不访问订单表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeAssociationGuard implements UserDeletionGuard {

    private final TradeClient tradeClient;

    @Override
    public String bindingName() {
        return "订单";
    }

    @Override
    public String bindingType() {
        return Constants.ASSOC_ORDER;
    }

    @Override
    public long count(Long userId) {
        try {
            R<Long> result = tradeClient.countOrdersByUser(userId);
            Long count = result == null ? null : result.getData();
            return count == null ? 0L : count;
        } catch (Exception e) {
            // 交易服务不可用时返回负数，由上层保守拒绝删除
            log.warn("[user] trade association check unavailable: {}", e.getMessage());
            return -1L;
        }
    }
}
