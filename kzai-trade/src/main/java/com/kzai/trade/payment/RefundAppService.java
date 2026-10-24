package com.kzai.trade.payment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.constant.Constants;
import com.kzai.common.exception.BizException;
import com.kzai.trade.coupon.CouponOrderService;
import com.kzai.trade.entity.Order;
import com.kzai.trade.entity.OrderDetail;
import com.kzai.trade.mapper.OrderDetailMapper;
import com.kzai.trade.mapper.OrderMapper;
import com.kzai.trade.service.OrderService;
import com.kzai.trade.service.OrderSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 按课程明细退款。金额用明细实付减去已成功退款，不按课程原价。
 * 已核销优惠券在部分退款和全额退款后都不退回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundAppService {

    private final TradeRefundMapper refundMapper;
    private final OrderDetailMapper detailMapper;
    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final PaymentProperties properties;
    private final CouponOrderService couponOrderService;

    @Transactional
    public Map<String, Object> apply(long userId, long orderId, long detailId, String reason, String requestId, Integer amount) {
        if (reason == null || reason.isBlank()) {
            throw new BizException("请填写退款原因");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new BizException("缺少退款请求号");
        }
        orderService.requireOwnedOrder(userId, orderId, false);
        OrderDetail detail = requireDetail(orderId, detailId, userId);
        if (!OrderSupport.paid(detail.getStatus())) {
            throw new BizException("只有已支付的课程明细可以申请退款");
        }
        int refundable = refundable(detail);
        if (refundable <= 0) {
            throw new BizException("该课程明细已无可退金额");
        }
        int applyAmount = amount == null ? refundable : amount;
        if (applyAmount <= 0 || applyAmount > refundable) {
            throw new BizException("退款金额必须在 1 分到剩余可退金额之间");
        }
        TradeRefund existing = refundMapper.selectOne(Wrappers.<TradeRefund>lambdaQuery()
                .eq(TradeRefund::getUserId, userId)
                .eq(TradeRefund::getDetailId, detailId)
                .eq(TradeRefund::getRequestId, requestId));
        if (existing != null) {
            return view(existing);
        }
        TradeRefund refund = new TradeRefund();
        refund.setRefundNo(UUID.randomUUID().toString().replace("-", ""));
        refund.setRequestId(requestId);
        refund.setOrderId(orderId);
        refund.setDetailId(detailId);
        refund.setUserId(userId);
        refund.setAmount(applyAmount);
        refund.setReason(reason.trim());
        refund.setStatus(TradeRefund.APPLYING);
        refund.setOpenFlag(1);
        LocalDateTime now = LocalDateTime.now();
        refund.setCreateTime(now);
        refund.setUpdateTime(now);
        try {
            refundMapper.insert(refund);
        } catch (DuplicateKeyException dup) {
            TradeRefund raced = refundMapper.selectOne(Wrappers.<TradeRefund>lambdaQuery()
                    .eq(TradeRefund::getUserId, userId)
                    .eq(TradeRefund::getDetailId, detailId)
                    .eq(TradeRefund::getRequestId, requestId));
            if (raced != null) {
                return view(raced);
            }
            throw new BizException("该课程明细已有处理中的退款申请");
        }
        detailMapper.update(null, Wrappers.<OrderDetail>lambdaUpdate()
                .set(OrderDetail::getRefundStatus, Constants.REFUND_APPLYING)
                .set(OrderDetail::getUpdateTime, now)
                .eq(OrderDetail::getId, detailId));
        return view(refund);
    }

    public List<Map<String, Object>> listForOrder(long userId, long orderId, boolean admin) {
        orderService.requireOwnedOrder(userId, orderId, admin);
        return refundMapper.selectList(Wrappers.<TradeRefund>lambdaQuery()
                        .eq(TradeRefund::getOrderId, orderId)
                        .orderByDesc(TradeRefund::getId))
                .stream().map(this::view).toList();
    }

    public List<Map<String, Object>> adminList() {
        return refundMapper.selectList(Wrappers.<TradeRefund>lambdaQuery()
                        .orderByDesc(TradeRefund::getId)
                        .last("LIMIT 100"))
                .stream().map(this::view).toList();
    }

    @Transactional
    public Map<String, Object> reject(long reviewer, long refundId, String reason) {
        TradeRefund refund = require(refundId);
        if (!TradeRefund.APPLYING.equals(refund.getStatus())) {
            throw new BizException("只有申请中的退款可以驳回");
        }
        if (reason == null || reason.isBlank()) {
            throw new BizException("请填写驳回原因");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = refundMapper.update(null, Wrappers.<TradeRefund>lambdaUpdate()
                .set(TradeRefund::getStatus, TradeRefund.REJECTED)
                .set(TradeRefund::getOpenFlag, null)
                .set(TradeRefund::getRejectReason, reason == null || reason.isBlank() ? "不符合退款规则" : reason.trim())
                .set(TradeRefund::getReviewer, reviewer)
                .set(TradeRefund::getReviewTime, now)
                .set(TradeRefund::getUpdateTime, now)
                .eq(TradeRefund::getId, refundId)
                .eq(TradeRefund::getStatus, TradeRefund.APPLYING));
        if (rows != 1) {
            throw new BizException("退款状态已变化，请刷新");
        }
        detailMapper.update(null, Wrappers.<OrderDetail>lambdaUpdate()
                .set(OrderDetail::getRefundStatus, Constants.REFUND_REJECTED)
                .set(OrderDetail::getUpdateTime, now)
                .eq(OrderDetail::getId, refund.getDetailId()));
        return view(refundMapper.selectById(refundId));
    }

    public Map<String, Object> approveAndExecute(long reviewer, long refundId) {
        TradeRefund approved = approve(reviewer, refundId);
        return execute(approved.getId());
    }

    @Transactional
    public TradeRefund approve(long reviewer, long refundId) {
        TradeRefund refund = require(refundId);
        if (TradeRefund.SUCCESS.equals(refund.getStatus())) {
            return refund;
        }
        if (!TradeRefund.APPLYING.equals(refund.getStatus()) && !TradeRefund.APPROVED.equals(refund.getStatus())
                && !TradeRefund.REFUNDING.equals(refund.getStatus())) {
            throw new BizException("当前退款状态不能批准");
        }
        if (TradeRefund.APPLYING.equals(refund.getStatus())) {
            LocalDateTime now = LocalDateTime.now();
            int rows = refundMapper.update(null, Wrappers.<TradeRefund>lambdaUpdate()
                    .set(TradeRefund::getStatus, TradeRefund.APPROVED)
                    .set(TradeRefund::getReviewer, reviewer)
                    .set(TradeRefund::getReviewTime, now)
                    .set(TradeRefund::getUpdateTime, now)
                    .eq(TradeRefund::getId, refundId)
                    .eq(TradeRefund::getStatus, TradeRefund.APPLYING));
            if (rows != 1) {
                throw new BizException("退款状态已变化，请刷新");
            }
        }
        return refundMapper.selectById(refundId);
    }

    public Map<String, Object> execute(long refundId) {
        TradeRefund refund = require(refundId);
        if (TradeRefund.SUCCESS.equals(refund.getStatus())) {
            return view(refund);
        }
        if (!TradeRefund.APPROVED.equals(refund.getStatus()) && !TradeRefund.REFUNDING.equals(refund.getStatus())) {
            throw new BizException("退款尚未批准");
        }
        mark(refundId, TradeRefund.REFUNDING, null, null);
        try {
            String channelNo = "MOCK-REFUND-" + refund.getRefundNo();
            if (properties.alipaySandboxReady()) {
                log.info("[trade.refund] 沙箱密钥已配置，但本次环境未发起真实沙箱退款调用 refundNo={}", refund.getRefundNo());
            }
            finishSuccess(refundId, channelNo);
        } catch (RuntimeException ex) {
            log.error("[trade.refund] 渠道退款失败 refundId={}", refundId, ex);
            finishFail(refundId, ex.getMessage());
        }
        return view(refundMapper.selectById(refundId));
    }

    @Transactional
    public void finishSuccess(long refundId, String channelNo) {
        TradeRefund refund = require(refundId);
        if (TradeRefund.SUCCESS.equals(refund.getStatus())) {
            return;
        }
        OrderDetail detail = detailMapper.selectById(refund.getDetailId());
        int already = refundedSuccess(detail.getId());
        if (already + refund.getAmount() > detail.getRealPayAmount()) {
            throw new BizException("累计退款超过明细实付");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = refundMapper.update(null, Wrappers.<TradeRefund>lambdaUpdate()
                .set(TradeRefund::getStatus, TradeRefund.SUCCESS)
                .set(TradeRefund::getOpenFlag, null)
                .set(TradeRefund::getChannelRefundNo, channelNo)
                .set(TradeRefund::getUpdateTime, now)
                .eq(TradeRefund::getId, refundId)
                .in(TradeRefund::getStatus, TradeRefund.APPROVED, TradeRefund.REFUNDING));
        if (rows != 1) {
            return;
        }
        int refunded = already + refund.getAmount();
        int detailStatus = refunded >= detail.getRealPayAmount()
                ? Constants.REFUND_SUCCESS : Constants.REFUND_NONE;
        detailMapper.update(null, Wrappers.<OrderDetail>lambdaUpdate()
                .set(OrderDetail::getRefundStatus, detailStatus)
                .set(OrderDetail::getUpdateTime, now)
                .eq(OrderDetail::getId, detail.getId()));
        orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getRefundTime, now)
                .set(Order::getMessage, "课程明细 " + detail.getId() + " 已退款 " + refund.getAmount() + " 分")
                .set(Order::getUpdateTime, now)
                .eq(Order::getId, refund.getOrderId()));
        couponOrderService.keepAfterRefund(refund.getOrderId());
    }

    @Transactional
    public void finishFail(long refundId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        refundMapper.update(null, Wrappers.<TradeRefund>lambdaUpdate()
                .set(TradeRefund::getStatus, TradeRefund.FAILED)
                .set(TradeRefund::getOpenFlag, null)
                .set(TradeRefund::getFailReason, reason == null ? "渠道退款失败" : reason)
                .set(TradeRefund::getUpdateTime, now)
                .eq(TradeRefund::getId, refundId)
                .in(TradeRefund::getStatus, TradeRefund.APPROVED, TradeRefund.REFUNDING));
        TradeRefund refund = refundMapper.selectById(refundId);
        if (refund != null && TradeRefund.FAILED.equals(refund.getStatus())) {
            detailMapper.update(null, Wrappers.<OrderDetail>lambdaUpdate()
                    .set(OrderDetail::getRefundStatus, Constants.REFUND_FAILED)
                    .set(OrderDetail::getUpdateTime, now)
                    .eq(OrderDetail::getId, refund.getDetailId()));
        }
    }

    private int refundable(OrderDetail detail) {
        int paid = detail.getRealPayAmount() == null ? 0 : detail.getRealPayAmount();
        return Math.max(0, paid - refundedSuccess(detail.getId()));
    }

    private int refundedSuccess(long detailId) {
        return refundMapper.selectList(Wrappers.<TradeRefund>lambdaQuery()
                        .eq(TradeRefund::getDetailId, detailId)
                        .eq(TradeRefund::getStatus, TradeRefund.SUCCESS))
                .stream().mapToInt(item -> item.getAmount() == null ? 0 : item.getAmount()).sum();
    }

    private OrderDetail requireDetail(long orderId, long detailId, long userId) {
        OrderDetail detail = detailMapper.selectById(detailId);
        if (detail == null || !Long.valueOf(orderId).equals(detail.getOrderId())
                || !Long.valueOf(userId).equals(detail.getUserId())) {
            throw new BizException("订单明细不存在");
        }
        return detail;
    }

    private TradeRefund require(long refundId) {
        TradeRefund refund = refundMapper.selectById(refundId);
        if (refund == null) {
            throw new BizException("退款单不存在");
        }
        return refund;
    }

    private void mark(long refundId, String status, String channelNo, String fail) {
        refundMapper.update(null, Wrappers.<TradeRefund>lambdaUpdate()
                .set(TradeRefund::getStatus, status)
                .set(TradeRefund::getChannelRefundNo, channelNo)
                .set(TradeRefund::getFailReason, fail)
                .set(TradeRefund::getUpdateTime, LocalDateTime.now())
                .eq(TradeRefund::getId, refundId));
    }

    private Map<String, Object> view(TradeRefund refund) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", String.valueOf(refund.getId()));
        view.put("refundNo", refund.getRefundNo());
        view.put("orderId", String.valueOf(refund.getOrderId()));
        view.put("detailId", String.valueOf(refund.getDetailId()));
        view.put("amount", refund.getAmount());
        view.put("reason", refund.getReason());
        view.put("status", refund.getStatus());
        view.put("statusText", statusText(refund.getStatus()));
        view.put("rejectReason", refund.getRejectReason());
        view.put("failReason", refund.getFailReason());
        view.put("mode", properties.alipaySandboxReady() ? "ALIPAY_SANDBOX" : "MOCK");
        view.put("sandboxAccepted", false);
        return view;
    }

    private static String statusText(String status) {
        return switch (status) {
            case TradeRefund.APPLYING -> "申请中";
            case TradeRefund.REJECTED -> "已驳回";
            case TradeRefund.APPROVED -> "已批准";
            case TradeRefund.REFUNDING -> "退款中";
            case TradeRefund.SUCCESS -> "退款成功";
            case TradeRefund.FAILED -> "退款失败";
            default -> status;
        };
    }
}
