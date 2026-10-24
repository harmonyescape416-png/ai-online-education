package com.kzai.trade.payment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.constant.Constants;
import com.kzai.trade.entity.OrderDetail;
import com.kzai.trade.mapper.OrderDetailMapper;
import com.kzai.trade.vo.OrderRefundNoticeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 订单列表只展示已通过和已驳回。驳回附带管理员填写的原因。
 */
@Service
@RequiredArgsConstructor
public class OrderRefundNoticeService {

    private final TradeRefundMapper refundMapper;
    private final OrderDetailMapper detailMapper;

    /** 渠道退款成功，或明细已标记退完。订单号仍保持原状态，列表文案用这个结果。 */
    public Set<Long> successfullyRefunded(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        refundMapper.selectList(Wrappers.<TradeRefund>lambdaQuery()
                        .in(TradeRefund::getOrderId, orderIds)
                        .eq(TradeRefund::getStatus, TradeRefund.SUCCESS))
                .forEach(refund -> ids.add(refund.getOrderId()));
        detailMapper.selectList(Wrappers.<OrderDetail>lambdaQuery()
                        .in(OrderDetail::getOrderId, orderIds)
                        .eq(OrderDetail::getRefundStatus, Constants.REFUND_SUCCESS))
                .forEach(detail -> ids.add(detail.getOrderId()));
        return ids;
    }

    public Map<Long, List<OrderRefundNoticeVO>> forOrders(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        List<TradeRefund> refunds = refundMapper.selectList(Wrappers.<TradeRefund>lambdaQuery()
                .in(TradeRefund::getOrderId, orderIds)
                .in(TradeRefund::getStatus, TradeRefund.SUCCESS, TradeRefund.REJECTED)
                .orderByDesc(TradeRefund::getUpdateTime)
                .orderByDesc(TradeRefund::getId));
        Map<Long, List<OrderRefundNoticeVO>> grouped = new LinkedHashMap<>();
        for (TradeRefund refund : refunds) {
            if (grouped.containsKey(refund.getOrderId())) {
                continue;
            }
            OrderRefundNoticeVO notice = toNotice(refund);
            if (notice != null) {
                grouped.put(refund.getOrderId(), new ArrayList<>(List.of(notice)));
            }
        }
        return grouped;
    }

    static OrderRefundNoticeVO toNotice(TradeRefund refund) {
        if (refund == null || refund.getStatus() == null) {
            return null;
        }
        if (TradeRefund.REJECTED.equals(refund.getStatus())) {
            String reason = refund.getRejectReason() == null ? "" : refund.getRejectReason().trim();
            return OrderRefundNoticeVO.builder()
                    .refundId(String.valueOf(refund.getId()))
                    .detailId(String.valueOf(refund.getDetailId()))
                    .status(refund.getStatus())
                    .outcome("REJECTED")
                    .statusText("已驳回")
                    .tag("warning")
                    .message(reason)
                    .amount(refund.getAmount())
                    .build();
        }
        if (TradeRefund.SUCCESS.equals(refund.getStatus())) {
            return OrderRefundNoticeVO.builder()
                    .refundId(String.valueOf(refund.getId()))
                    .detailId(String.valueOf(refund.getDetailId()))
                    .status(refund.getStatus())
                    .outcome("PASSED")
                    .statusText("已通过")
                    .tag("success")
                    .message("")
                    .amount(refund.getAmount())
                    .build();
        }
        return null;
    }
}
