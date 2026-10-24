package com.kzai.trade.coupon;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.exception.BizException;
import com.kzai.trade.entity.Order;
import com.kzai.trade.entity.OrderDetail;
import com.kzai.trade.mapper.OrderDetailMapper;
import com.kzai.trade.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 下单锁定、支付核销、取消或超时释放。退款不退券。
 * 核销失败写入补偿表，不回滚已成功的支付。
 */
@Service
@RequiredArgsConstructor
public class CouponOrderService {

    private final MktCouponUserMapper userMapper;
    private final MktOrderCouponMapper orderCouponMapper;
    private final MktCouponTemplateMapper templateMapper;
    private final MktCouponCompensateMapper compensateMapper;
    private final OrderMapper orderMapper;
    private final OrderDetailMapper detailMapper;
    private final CouponClock clock;

    @Transactional
    public void attach(long userId, long userCouponId, Order order, List<OrderDetail> details) {
        MktCouponUser coupon = userMapper.selectById(userCouponId);
        if (coupon == null || !Long.valueOf(userId).equals(coupon.getUserId())) {
            throw new BizException("优惠券不存在");
        }
        if (!MktCouponUser.UNUSED.equals(coupon.getStatus())) {
            throw new BizException(MktCouponUser.USED.equals(coupon.getStatus()) ? "优惠券已使用" : "优惠券使用中");
        }
        List<CouponPricing.Line> lines = details.stream()
                .map(detail -> new CouponPricing.Line(detail.getCourseId(), detail.getPrice(), detail.getId()))
                .toList();
        CouponPricing.Decision decision = CouponPricing.decide(coupon.getCouponType(), coupon.getThresholdAmount(),
                coupon.getReduceAmount(), coupon.getPayRatio(), coupon.getCourseScope(), coupon.getCourseIds(),
                coupon.getValidStart(), coupon.getValidEnd(), lines, clock.now());
        if (!decision.usable()) {
            throw new BizException(decision.reason());
        }
        LocalDateTime now = clock.now();
        int locked = userMapper.update(null, Wrappers.<MktCouponUser>lambdaUpdate()
                .set(MktCouponUser::getStatus, MktCouponUser.LOCKED)
                .set(MktCouponUser::getLockOrderId, order.getId())
                .set(MktCouponUser::getUpdateTime, now)
                .eq(MktCouponUser::getId, coupon.getId())
                .eq(MktCouponUser::getUserId, userId)
                .eq(MktCouponUser::getStatus, MktCouponUser.UNUSED));
        if (locked != 1) {
            throw new BizException("优惠券不可用");
        }
        order.setDiscountAmount(decision.discount());
        order.setRealAmount(order.getTotalAmount() - decision.discount());
        orderMapper.updateById(order);
        StringBuilder allocation = new StringBuilder("[");
        for (int i = 0; i < details.size(); i++) {
            OrderDetail detail = details.get(i);
            CouponPricing.Share share = decision.shares().get(i);
            detail.setDiscountAmount(share.discount());
            detail.setRealPayAmount(share.payable());
            detailMapper.updateById(detail);
            if (i > 0) {
                allocation.append(',');
            }
            allocation.append("{\"detailId\":").append(detail.getId())
                    .append(",\"discount\":").append(share.discount()).append('}');
        }
        allocation.append(']');
        MktOrderCoupon link = new MktOrderCoupon();
        link.setOrderId(order.getId());
        link.setUserCouponId(coupon.getId());
        link.setDiscountAmount(decision.discount());
        link.setAllocationJson(allocation.toString());
        link.setStatus(MktOrderCoupon.LOCKED);
        link.setCreateTime(now);
        link.setUpdateTime(now);
        orderCouponMapper.insert(link);
    }

    @Transactional
    public void releaseByOrder(long orderId) {
        MktOrderCoupon link = orderCouponMapper.selectOne(Wrappers.<MktOrderCoupon>lambdaQuery()
                .eq(MktOrderCoupon::getOrderId, orderId));
        if (link == null || MktOrderCoupon.RELEASED.equals(link.getStatus()) || MktOrderCoupon.USED.equals(link.getStatus())) {
            return;
        }
        LocalDateTime now = clock.now();
        int rows = orderCouponMapper.update(null, Wrappers.<MktOrderCoupon>lambdaUpdate()
                .set(MktOrderCoupon::getStatus, MktOrderCoupon.RELEASED)
                .set(MktOrderCoupon::getUpdateTime, now)
                .eq(MktOrderCoupon::getId, link.getId())
                .eq(MktOrderCoupon::getStatus, MktOrderCoupon.LOCKED));
        if (rows != 1) {
            return;
        }
        userMapper.update(null, Wrappers.<MktCouponUser>lambdaUpdate()
                .set(MktCouponUser::getStatus, MktCouponUser.UNUSED)
                .set(MktCouponUser::getLockOrderId, null)
                .set(MktCouponUser::getUpdateTime, now)
                .eq(MktCouponUser::getId, link.getUserCouponId())
                .eq(MktCouponUser::getStatus, MktCouponUser.LOCKED)
                .eq(MktCouponUser::getLockOrderId, orderId));
    }

    @Transactional
    public void consumeByOrder(long orderId) {
        MktOrderCoupon link = orderCouponMapper.selectOne(Wrappers.<MktOrderCoupon>lambdaQuery()
                .eq(MktOrderCoupon::getOrderId, orderId));
        if (link == null || MktOrderCoupon.USED.equals(link.getStatus())) {
            markCompensateDone(orderId);
            return;
        }
        LocalDateTime now = clock.now();
        if (MktOrderCoupon.RELEASED.equals(link.getStatus())) {
            int recovered = userMapper.update(null, Wrappers.<MktCouponUser>lambdaUpdate()
                    .set(MktCouponUser::getStatus, MktCouponUser.USED)
                    .set(MktCouponUser::getUsedOrderId, orderId)
                    .set(MktCouponUser::getLockOrderId, orderId)
                    .set(MktCouponUser::getUpdateTime, now)
                    .eq(MktCouponUser::getId, link.getUserCouponId())
                    .eq(MktCouponUser::getStatus, MktCouponUser.UNUSED));
            if (recovered != 1) {
                saveCompensate(orderId, link.getUserCouponId(), "支付成功时优惠券已不能核销");
                return;
            }
            orderCouponMapper.update(null, Wrappers.<MktOrderCoupon>lambdaUpdate()
                    .set(MktOrderCoupon::getStatus, MktOrderCoupon.USED)
                    .set(MktOrderCoupon::getUpdateTime, now)
                    .eq(MktOrderCoupon::getId, link.getId())
                    .eq(MktOrderCoupon::getStatus, MktOrderCoupon.RELEASED));
            MktCouponUser coupon = userMapper.selectById(link.getUserCouponId());
            templateMapper.markUsed(coupon.getTemplateId(), now);
            markCompensateDone(orderId);
            return;
        }
        int rows = orderCouponMapper.update(null, Wrappers.<MktOrderCoupon>lambdaUpdate()
                .set(MktOrderCoupon::getStatus, MktOrderCoupon.USED)
                .set(MktOrderCoupon::getUpdateTime, now)
                .eq(MktOrderCoupon::getId, link.getId())
                .eq(MktOrderCoupon::getStatus, MktOrderCoupon.LOCKED));
        if (rows != 1) {
            return;
        }
        int used = userMapper.update(null, Wrappers.<MktCouponUser>lambdaUpdate()
                .set(MktCouponUser::getStatus, MktCouponUser.USED)
                .set(MktCouponUser::getUsedOrderId, orderId)
                .set(MktCouponUser::getUpdateTime, now)
                .eq(MktCouponUser::getId, link.getUserCouponId())
                .eq(MktCouponUser::getLockOrderId, orderId)
                .eq(MktCouponUser::getStatus, MktCouponUser.LOCKED));
        if (used != 1) {
            saveCompensate(orderId, link.getUserCouponId(), "优惠券锁定状态与订单不一致");
            return;
        }
        MktCouponUser coupon = userMapper.selectById(link.getUserCouponId());
        templateMapper.markUsed(coupon.getTemplateId(), now);
    }

    /** 退款不退券。部分退款和全额退款都不把已核销优惠券改回未使用。 */
    public void keepAfterRefund(long orderId) {
        MktOrderCoupon link = orderCouponMapper.selectOne(Wrappers.<MktOrderCoupon>lambdaQuery()
                .eq(MktOrderCoupon::getOrderId, orderId));
        if (link == null) {
            return;
        }
        MktCouponUser coupon = userMapper.selectById(link.getUserCouponId());
        if (coupon != null && !MktCouponUser.USED.equals(coupon.getStatus())) {
            saveCompensate(orderId, coupon.getId(), "退款时优惠券不是已使用，保持不退券");
        }
    }

    @Transactional
    public int retryCompensate() {
        List<MktCouponCompensate> pending = compensateMapper.selectList(Wrappers.<MktCouponCompensate>lambdaQuery()
                .eq(MktCouponCompensate::getStatus, MktCouponCompensate.PENDING)
                .eq(MktCouponCompensate::getAction, MktCouponCompensate.CONSUME)
                .last("LIMIT 20"));
        int done = 0;
        for (MktCouponCompensate row : pending) {
            consumeByOrder(row.getOrderId());
            MktCouponCompensate current = compensateMapper.selectById(row.getId());
            if (current != null && MktCouponCompensate.DONE.equals(current.getStatus())) {
                done++;
            }
        }
        return done;
    }

    private void saveCompensate(long orderId, long userCouponId, String reason) {
        MktCouponCompensate existing = compensateMapper.selectOne(Wrappers.<MktCouponCompensate>lambdaQuery()
                .eq(MktCouponCompensate::getOrderId, orderId)
                .eq(MktCouponCompensate::getAction, MktCouponCompensate.CONSUME));
        if (existing != null) {
            return;
        }
        MktCouponCompensate row = new MktCouponCompensate();
        row.setOrderId(orderId);
        row.setUserCouponId(userCouponId);
        row.setAction(MktCouponCompensate.CONSUME);
        row.setStatus(MktCouponCompensate.PENDING);
        row.setReason(reason);
        LocalDateTime now = clock.now();
        row.setCreateTime(now);
        row.setUpdateTime(now);
        try {
            compensateMapper.insert(row);
        } catch (DuplicateKeyException ignored) {
            // 同一订单的核销补偿只保留一条
        }
    }

    private void markCompensateDone(long orderId) {
        compensateMapper.update(null, Wrappers.<MktCouponCompensate>lambdaUpdate()
                .set(MktCouponCompensate::getStatus, MktCouponCompensate.DONE)
                .set(MktCouponCompensate::getUpdateTime, clock.now())
                .eq(MktCouponCompensate::getOrderId, orderId)
                .eq(MktCouponCompensate::getAction, MktCouponCompensate.CONSUME)
                .eq(MktCouponCompensate::getStatus, MktCouponCompensate.PENDING));
    }
}
