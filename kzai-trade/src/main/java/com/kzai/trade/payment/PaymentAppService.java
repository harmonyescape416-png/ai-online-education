package com.kzai.trade.payment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.constant.Constants;
import com.kzai.common.exception.BizException;
import com.kzai.trade.coupon.CouponOrderService;
import com.kzai.trade.entity.Order;
import com.kzai.trade.mapper.OrderMapper;
import com.kzai.trade.service.OrderService;
import com.kzai.trade.service.OrderSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 支付只在通知验签通过后改订单。按钮和跳转本身不把订单标成已支付。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentAppService {

    private final PaymentProperties properties;
    private final TradePaymentMapper paymentMapper;
    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final CouponOrderService couponOrderService;

    public Map<String, Object> start(long userId, long orderId, String requestedChannel, boolean admin) {
        Order order = orderService.requireOwnedOrder(userId, orderId, admin);
        if (OrderSupport.paid(order.getStatus())) {
            return view(existingSuccess(orderId), order, false);
        }
        if (!Integer.valueOf(Constants.ORDER_STATUS_PENDING).equals(order.getStatus())) {
            throw new BizException("仅待支付订单可支付，当前状态：" + OrderSupport.statusText(order.getStatus()));
        }
        String channel = resolveChannel(requestedChannel);
        TradePayment open = paymentMapper.selectOne(Wrappers.<TradePayment>lambdaQuery()
                .eq(TradePayment::getOrderId, orderId)
                .eq(TradePayment::getStatus, TradePayment.WAIT_PAY)
                .last("LIMIT 1"));
        if (open == null) {
            open = insertPayment(order, channel);
        }
        if (channel.startsWith("MOCK_")) {
            Map<String, String> notice = successNotice(open);
            boolean first = acceptNotify("MOCK", notice);
            TradePayment latest = paymentMapper.selectById(open.getId());
            return view(latest, orderMapper.selectById(orderId), first);
        }
        Map<String, Object> waiting = view(open, order, false);
        waiting.put("sandboxAccepted", false);
        waiting.put("message", "已创建支付宝沙箱支付单。未收到验签成功通知前，订单仍是待支付");
        return waiting;
    }

    public boolean acceptNotify(String channel, Map<String, String> params) {
        if ("MOCK".equals(channel)) {
            if (!MockPayCodec.verify(params, properties.getMockSecret())) {
                log.warn("[trade.pay] 模拟通知验签失败 paymentNo={}", params.get("paymentNo"));
                throw new BizException("支付通知验签失败");
            }
            if (!properties.getMerchantId().equals(params.get("merchantId"))) {
                throw new BizException("支付通知商户不匹配");
            }
        } else if ("ALIPAY".equals(channel)) {
            if (!properties.alipaySandboxReady()) {
                log.warn("[trade.pay] 收到支付宝通知，但沙箱密钥未配置");
                throw new BizException("支付宝沙箱未配置，通知已拒绝");
            }
            if (!AlipaySandboxCodec.verify(params, properties.getAlipay().getAlipayPublicKey())) {
                log.warn("[trade.pay] 支付宝通知验签失败 outTradeNo={}", params.get("out_trade_no"));
                throw new BizException("支付通知验签失败");
            }
            if (!properties.getAlipay().getAppId().equals(params.get("app_id"))) {
                throw new BizException("支付通知商户不匹配");
            }
        } else {
            throw new BizException("不支持的支付通知渠道");
        }
        return settle(channel, params);
    }

    public Map<String, Object> query(long userId, long orderId, boolean admin) {
        orderService.requireOwnedOrder(userId, orderId, admin);
        TradePayment payment = latest(orderId);
        Order order = orderMapper.selectById(orderId);
        if (payment == null) {
            return Map.of("orderId", String.valueOf(orderId), "paymentStatus", "NONE",
                    "orderStatus", order.getStatus());
        }
        return view(payment, order, false);
    }

    @Transactional
    public int closeExpired() {
        LocalDateTime now = LocalDateTime.now();
        List<Order> due = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getStatus, Constants.ORDER_STATUS_PENDING)
                .lt(Order::getCreateTime, now.minusMinutes(properties.getExpireMinutes()))
                .last("LIMIT 50"));
        int closed = 0;
        for (Order order : due) {
            int rows = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                    .set(Order::getStatus, Constants.ORDER_STATUS_CLOSED)
                    .set(Order::getCloseTime, now)
                    .set(Order::getUpdateTime, now)
                    .set(Order::getMessage, "超时未支付，订单已关闭")
                    .eq(Order::getId, order.getId())
                    .eq(Order::getStatus, Constants.ORDER_STATUS_PENDING));
            if (rows == 1) {
                paymentMapper.update(null, Wrappers.<TradePayment>lambdaUpdate()
                        .set(TradePayment::getStatus, TradePayment.CLOSED)
                        .set(TradePayment::getOpenFlag, null)
                        .set(TradePayment::getUpdateTime, now)
                        .eq(TradePayment::getOrderId, order.getId())
                        .eq(TradePayment::getStatus, TradePayment.WAIT_PAY));
                couponOrderService.releaseByOrder(order.getId());
                closed++;
            }
        }
        return closed;
    }

    private boolean settle(String channel, Map<String, String> params) {
        String paymentNo = "ALIPAY".equals(channel) ? params.get("out_trade_no") : params.get("paymentNo");
        String tradeStatus = "ALIPAY".equals(channel) ? params.get("trade_status") : params.get("tradeStatus");
        String currency = "ALIPAY".equals(channel) ? "CNY" : params.get("currency");
        int amount = "ALIPAY".equals(channel) ? yuanToFen(params.get("total_amount")) : Integer.parseInt(params.get("amount"));
        TradePayment payment = paymentMapper.selectOne(Wrappers.<TradePayment>lambdaQuery()
                .eq(TradePayment::getPaymentNo, paymentNo));
        if (payment == null) {
            throw new BizException("支付单不存在");
        }
        if (!"TRADE_SUCCESS".equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
            log.info("[trade.pay] 通知不是成功状态 paymentNo={} status={}", paymentNo, tradeStatus);
            return false;
        }
        if (TradePayment.SUCCESS.equals(payment.getStatus())) {
            return false;
        }
        if (amount != payment.getAmount() || !"CNY".equals(currency)) {
            mark(payment, TradePayment.IGNORED, null);
            throw new BizException("支付通知金额或币种与支付单不一致");
        }
        String storedChannel = "ALIPAY".equals(channel) ? "ALIPAY_SANDBOX" : payment.getChannel();
        boolean first = orderService.settleVerifiedPayment(payment.getUserId(), payment.getOrderId(),
                storedChannel, amount, currency);
        mark(payment, TradePayment.SUCCESS, params.getOrDefault("trade_no", params.get("channelTradeNo")));
        return first;
    }

    private void mark(TradePayment payment, String status, String channelTradeNo) {
        paymentMapper.update(null, Wrappers.<TradePayment>lambdaUpdate()
                .set(TradePayment::getStatus, status)
                .set(TradePayment::getOpenFlag, null)
                .set(TradePayment::getChannelTradeNo, channelTradeNo)
                .set(TradePayment::getSuccessTime, TradePayment.SUCCESS.equals(status) ? LocalDateTime.now() : null)
                .set(TradePayment::getUpdateTime, LocalDateTime.now())
                .eq(TradePayment::getId, payment.getId())
                .ne(TradePayment::getStatus, TradePayment.SUCCESS));
    }

    private TradePayment insertPayment(Order order, String channel) {
        LocalDateTime now = LocalDateTime.now();
        TradePayment payment = new TradePayment();
        payment.setPaymentNo(UUID.randomUUID().toString().replace("-", ""));
        payment.setOrderId(order.getId());
        payment.setUserId(order.getUserId());
        payment.setChannel(channel);
        payment.setAmount(order.getRealAmount());
        payment.setCurrency("CNY");
        payment.setStatus(TradePayment.WAIT_PAY);
        payment.setMerchantId(properties.alipaySandboxReady() && "ALIPAY_SANDBOX".equals(channel)
                ? properties.getAlipay().getAppId() : properties.getMerchantId());
        payment.setExpireTime(now.plusMinutes(properties.getExpireMinutes()));
        payment.setCreateTime(now);
        payment.setUpdateTime(now);
        payment.setOpenFlag(1);
        paymentMapper.insert(payment);
        return payment;
    }

    private Map<String, String> successNotice(TradePayment payment) {
        Map<String, String> notice = new LinkedHashMap<>();
        notice.put("merchantId", payment.getMerchantId());
        notice.put("paymentNo", payment.getPaymentNo());
        notice.put("orderId", String.valueOf(payment.getOrderId()));
        notice.put("amount", String.valueOf(payment.getAmount()));
        notice.put("currency", payment.getCurrency());
        notice.put("tradeStatus", "TRADE_SUCCESS");
        notice.put("channelTradeNo", "MOCK-" + payment.getPaymentNo());
        notice.put("sign", MockPayCodec.sign(notice, properties.getMockSecret()));
        return notice;
    }

    private String resolveChannel(String requested) {
        if ("ALIPAY".equals(requested) && properties.alipaySandboxReady()) {
            return "ALIPAY_SANDBOX";
        }
        if ("ALIPAY".equals(requested)) {
            return "MOCK_ALIPAY";
        }
        return "MOCK_WECHAT";
    }

    private TradePayment existingSuccess(long orderId) {
        return paymentMapper.selectOne(Wrappers.<TradePayment>lambdaQuery()
                .eq(TradePayment::getOrderId, orderId)
                .eq(TradePayment::getStatus, TradePayment.SUCCESS)
                .last("LIMIT 1"));
    }

    private TradePayment latest(long orderId) {
        return paymentMapper.selectOne(Wrappers.<TradePayment>lambdaQuery()
                .eq(TradePayment::getOrderId, orderId)
                .orderByDesc(TradePayment::getId)
                .last("LIMIT 1"));
    }

    private static int yuanToFen(String yuan) {
        if (yuan == null || yuan.isBlank()) {
            return -1;
        }
        return new java.math.BigDecimal(yuan).movePointRight(2).intValueExact();
    }

    private Map<String, Object> view(TradePayment payment, Order order, boolean enrollNeeded) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("orderId", String.valueOf(order.getId()));
        view.put("status", order.getStatus());
        view.put("paymentStatus", payment == null ? "NONE" : payment.getStatus());
        view.put("paymentNo", payment == null ? null : payment.getPaymentNo());
        view.put("channel", payment == null ? order.getPayChannel() : payment.getChannel());
        view.put("mode", payment != null && "ALIPAY_SANDBOX".equals(payment.getChannel()) ? "ALIPAY_SANDBOX" : "MOCK");
        view.put("sandboxAccepted", false);
        view.put("alreadyPaid", OrderSupport.paid(order.getStatus()) && !enrollNeeded);
        view.put("enrollNeeded", enrollNeeded);
        return view;
    }
}
