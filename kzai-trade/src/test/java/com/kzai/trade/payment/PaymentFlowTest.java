package com.kzai.trade.payment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.trade.client.CourseClient;
import com.kzai.trade.compensation.EnrollCompensationEntity;
import com.kzai.trade.compensation.EnrollCompensationMapper;
import com.kzai.trade.dto.CourseSnapshot;
import com.kzai.trade.dto.PrePlaceRequest;
import com.kzai.trade.entity.Order;
import com.kzai.trade.mapper.OrderMapper;
import com.kzai.trade.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:trade_pay;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "spring.sql.init.data-locations=classpath:data-test.sql",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.task.scheduling.enabled=false",
        "kzai.trade.payment.provider=mock",
        "kzai.trade.payment.mock-secret=test-mock-secret",
        "kzai.trade.payment.merchant-id=kzai-mock-merchant",
        "kzai.trade.payment.expire-minutes=30"
})
@Transactional
class PaymentFlowTest {

    @Autowired
    private PaymentAppService payments;
    @Autowired
    private RefundAppService refunds;
    @Autowired
    private OrderService orders;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private EnrollCompensationMapper compensationMapper;
    @Autowired
    private TradePaymentMapper paymentMapper;
    @MockBean
    private CourseClient courseClient;

    @BeforeEach
    void user() {
        UserContext.set(3L, "student01", Constants.USER_TYPE_STUDENT, List.of(Constants.ROLE_STUDENT), "pay");
        when(courseClient.batch(anyString())).thenAnswer(invocation -> {
            String ids = invocation.getArgument(0);
            long id = Long.parseLong(ids.contains(",") ? ids.substring(0, ids.indexOf(',')) : ids);
            return R.ok(List.of(snapshot(id, "支付课" + id, 10000)));
        });
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    void mockPaySettlesOnce() {
        long orderId = place("pay-1");
        Map<String, Object> paid = payments.start(3L, orderId, "WECHAT", false);
        assertEquals("SUCCESS", paid.get("paymentStatus"));
        assertEquals("MOCK", paid.get("mode"));
        assertEquals(Boolean.FALSE, paid.get("sandboxAccepted"));
        assertEquals(Constants.ORDER_STATUS_PAID, orderMapper.selectById(orderId).getStatus());
        assertEquals(1L, compensationMapper.selectCount(Wrappers.<EnrollCompensationEntity>lambdaQuery()
                .eq(EnrollCompensationEntity::getOrderId, orderId)));
        Map<String, Object> again = payments.start(3L, orderId, "WECHAT", false);
        assertEquals(Boolean.TRUE, again.get("alreadyPaid"));
        assertEquals(1L, compensationMapper.selectCount(Wrappers.<EnrollCompensationEntity>lambdaQuery()
                .eq(EnrollCompensationEntity::getOrderId, orderId)));
        assertEquals("MOCK_ALIPAY", payments.start(3L, place("pay-2"), "ALIPAY", false).get("channel"));
    }

    @Test
    void invalidSignDoesNotSettle() {
        long orderId = place("pay-bad");
        Map<String, String> notice = new LinkedHashMap<>();
        notice.put("merchantId", "kzai-mock-merchant");
        notice.put("paymentNo", "missing");
        notice.put("orderId", String.valueOf(orderId));
        notice.put("amount", "10000");
        notice.put("currency", "CNY");
        notice.put("tradeStatus", "TRADE_SUCCESS");
        notice.put("sign", "bad");
        assertThrows(BizException.class, () -> payments.acceptNotify("MOCK", notice));
        assertEquals(Constants.ORDER_STATUS_PENDING, orderMapper.selectById(orderId).getStatus());
    }

    @Test
    void amountMismatchDoesNotMarkPaid() {
        long orderId = place("pay-amount");
        payments.start(3L, orderId, "WECHAT", false);
        orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getStatus, Constants.ORDER_STATUS_PENDING)
                .eq(Order::getId, orderId));
        TradePayment payment = paymentMapper.selectOne(Wrappers.<TradePayment>lambdaQuery()
                .eq(TradePayment::getOrderId, orderId));
        paymentMapper.update(null, Wrappers.<TradePayment>lambdaUpdate()
                .set(TradePayment::getStatus, TradePayment.WAIT_PAY)
                .set(TradePayment::getOpenFlag, 1)
                .eq(TradePayment::getId, payment.getId()));
        Map<String, String> notice = new LinkedHashMap<>();
        notice.put("merchantId", "kzai-mock-merchant");
        notice.put("paymentNo", payment.getPaymentNo());
        notice.put("orderId", String.valueOf(orderId));
        notice.put("amount", "1");
        notice.put("currency", "CNY");
        notice.put("tradeStatus", "TRADE_SUCCESS");
        notice.put("sign", MockPayCodec.sign(notice, "test-mock-secret"));
        assertThrows(BizException.class, () -> payments.acceptNotify("MOCK", notice));
        assertEquals(Constants.ORDER_STATUS_PENDING, orderMapper.selectById(orderId).getStatus());
    }

    @Test
    void timeoutCloseThenVerifiedSuccessReopensWithoutDoubleEnroll() {
        long orderId = place("pay-race");
        orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getCreateTime, LocalDateTime.now().minusHours(2))
                .eq(Order::getId, orderId));
        assertEquals(1, payments.closeExpired());
        assertTrue(orderMapper.selectById(orderId).getMessage().startsWith("超时未支付"));
        TradePayment payment = new TradePayment();
        payment.setPaymentNo("race-" + orderId);
        payment.setOrderId(orderId);
        payment.setUserId(3L);
        payment.setChannel("MOCK_WECHAT");
        payment.setAmount(10000);
        payment.setCurrency("CNY");
        payment.setStatus(TradePayment.WAIT_PAY);
        payment.setMerchantId("kzai-mock-merchant");
        payment.setExpireTime(LocalDateTime.now().plusMinutes(5));
        payment.setCreateTime(LocalDateTime.now());
        payment.setUpdateTime(LocalDateTime.now());
        payment.setOpenFlag(1);
        paymentMapper.insert(payment);
        Map<String, String> notice = signed(payment);
        assertTrue(payments.acceptNotify("MOCK", notice));
        assertEquals(Constants.ORDER_STATUS_PAID, orderMapper.selectById(orderId).getStatus());
        assertFalse(payments.acceptNotify("MOCK", notice));
        assertEquals(1L, compensationMapper.selectCount(Wrappers.<EnrollCompensationEntity>lambdaQuery()
                .eq(EnrollCompensationEntity::getOrderId, orderId)));
    }

    @Test
    void refundIsIdempotentAndPartialKeepsAccessUntilFullyRefunded() {
        assertTrue(orders.hasPaidEntitlement(3L, 4001L));
        Map<String, Object> first = refunds.apply(3L, 5001L, 6001L, "买错了", "r1", 1000);
        Map<String, Object> same = refunds.apply(3L, 5001L, 6001L, "买错了", "r1", 1000);
        assertEquals(first.get("id"), same.get("id"));
        assertFalse(orders.hasPaidEntitlement(3L, 4001L));
        refunds.reject(1L, Long.parseLong(String.valueOf(first.get("id"))), "不符合");
        assertTrue(orders.hasPaidEntitlement(3L, 4001L));
        Map<String, Object> partial = refunds.apply(3L, 5001L, 6001L, "部分", "r2", 1000);
        refunds.approveAndExecute(1L, Long.parseLong(String.valueOf(partial.get("id"))));
        assertTrue(orders.hasPaidEntitlement(3L, 4001L));
        Map<String, Object> rest = refunds.apply(3L, 5001L, 6001L, "剩余", "r3", null);
        refunds.approveAndExecute(1L, Long.parseLong(String.valueOf(rest.get("id"))));
        assertFalse(orders.hasPaidEntitlement(3L, 4001L));
        assertThrows(BizException.class, () -> refunds.apply(3L, 5001L, 6001L, "再退", "r4", 1));
    }

    @Test
    void alipaySandboxCodecVerifiesRsa2AndRejectsTamper() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", "sandbox");
        params.put("out_trade_no", "p1");
        params.put("total_amount", "10.00");
        params.put("trade_status", "TRADE_SUCCESS");
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keys.getPrivate());
        signature.update(AlipaySandboxCodec.canonical(params).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        params.put("sign", Base64.getEncoder().encodeToString(signature.sign()));
        params.put("sign_type", "RSA2");
        String publicKey = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        assertTrue(AlipaySandboxCodec.verify(params, publicKey));
        params.put("total_amount", "10.01");
        assertFalse(AlipaySandboxCodec.verify(params, publicKey));
    }

    private Map<String, String> signed(TradePayment payment) {
        Map<String, String> notice = new LinkedHashMap<>();
        notice.put("merchantId", payment.getMerchantId());
        notice.put("paymentNo", payment.getPaymentNo());
        notice.put("orderId", String.valueOf(payment.getOrderId()));
        notice.put("amount", String.valueOf(payment.getAmount()));
        notice.put("currency", "CNY");
        notice.put("tradeStatus", "TRADE_SUCCESS");
        notice.put("channelTradeNo", "MOCK-" + payment.getPaymentNo());
        notice.put("sign", MockPayCodec.sign(notice, "test-mock-secret"));
        return notice;
    }

    private long courseSeq = 7200L;

    private long place(String requestId) {
        PrePlaceRequest request = new PrePlaceRequest();
        request.setRequestId(requestId);
        request.setCourseIds(List.of(++courseSeq));
        return orders.prePlace(3L, request).getOrderId();
    }

    private static CourseSnapshot snapshot(Long id, String name, int price) {
        CourseSnapshot snapshot = new CourseSnapshot();
        snapshot.setId(id);
        snapshot.setName(name);
        snapshot.setPrice(price);
        snapshot.setCoverUrl("/files/" + id);
        snapshot.setValidDuration(12);
        snapshot.setStatus(Constants.COURSE_STATUS_ONLINE);
        snapshot.setDeleted(0);
        snapshot.setCategoryEnabled(true);
        snapshot.setPurchasable(true);
        return snapshot;
    }
}
