package com.kzai.trade.service;

import com.kzai.common.constant.Constants;
import com.kzai.trade.dto.EntitlementDecision;
import com.kzai.trade.entity.Order;
import com.kzai.trade.entity.OrderDetail;
import com.kzai.trade.mapper.OrderDetailMapper;
import com.kzai.trade.mapper.OrderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 学习权益判定测试（M5）：订单主体状态 × 明细状态 × 退款状态 × 有效期 的真实组合判定。
 * H2 真实执行 SQL，不使用任何 Mock 替代数据库行为。
 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:entitlement_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "spring.sql.init.data-locations=classpath:data-test.sql",
        "spring.cloud.nacos.discovery.enabled=false"
})
@Transactional
class EntitlementServiceTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper detailMapper;

    @MockBean
    private com.kzai.trade.client.CourseClient courseClient;

    private long newId() {
        return System.nanoTime() % 1_000_000_000L + 700_000_000L;
    }

    /** 构造订单 + 明细 */
    private long seed(int orderStatus, int detailStatus, Integer refundStatus,
                      Integer validDuration, LocalDateTime courseExpireTime,
                      LocalDateTime payTime, Long courseId) {
        long orderId = newId();
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(3L);
        order.setStatus(orderStatus);
        order.setTotalAmount(19900);
        order.setRealAmount(19900);
        order.setDiscountAmount(0);
        order.setPayTime(payTime);
        order.setDeleted(0);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.insert(order);

        OrderDetail detail = new OrderDetail();
        detail.setId(newId());
        detail.setOrderId(orderId);
        detail.setUserId(3L);
        detail.setCourseId(courseId == null ? 4001L : courseId);
        detail.setPrice(19900);
        detail.setName("权益测试课程");
        detail.setValidDuration(validDuration);
        detail.setCourseExpireTime(courseExpireTime);
        detail.setDiscountAmount(0);
        detail.setRealPayAmount(19900);
        detail.setStatus(detailStatus);
        detail.setRefundStatus(refundStatus);
        detail.setCreateTime(LocalDateTime.now());
        detail.setUpdateTime(LocalDateTime.now());
        detailMapper.insert(detail);
        return orderId;
    }

    @Test
    void paidAndNotExpired_grantsEntitlement() {
        seed(Constants.ORDER_STATUS_PAID, 2, null, 12, null, LocalDateTime.now(), 4001L);
        EntitlementDecision d = orderService.entitlementDetail(3L, 4001L);
        assertTrue(d.getAllowed());
        assertEquals("PAID", d.getSource());
        assertNotNull(d.getExpireTime(), "有效期按 valid_duration 推算");
    }

    @Test
    void pendingOrder_denied() {
        seed(Constants.ORDER_STATUS_PENDING, 1, null, 12, null, null, 4101L);
        EntitlementDecision d = orderService.entitlementDetail(3L, 4101L);
        assertFalse(d.getAllowed());
        assertTrue(d.getReason().contains("未查询到该课程的已支付订单"));
    }

    @Test
    void closedOrder_denied() {
        seed(Constants.ORDER_STATUS_CLOSED, 3, null, 12, null, LocalDateTime.now(), 4102L);
        EntitlementDecision d = orderService.entitlementDetail(3L, 4102L);
        assertFalse(d.getAllowed());
    }

    @Test
    void refundApplying_denied() {
        seed(Constants.ORDER_STATUS_PAID, 2, Constants.REFUND_APPLYING, 12, null, LocalDateTime.now(), 4103L);
        EntitlementDecision d = orderService.entitlementDetail(3L, 4103L);
        assertFalse(d.getAllowed());
        assertTrue(d.getReason().contains("退款"), d.getReason());
        assertNotEquals(Constants.REFUND_SUCCESS, d.getRefundStatus());
    }

    @Test
    void refundSuccess_denied() {
        seed(Constants.ORDER_STATUS_PAID, 2, Constants.REFUND_SUCCESS, 12, null, LocalDateTime.now(), 4104L);
        EntitlementDecision d = orderService.entitlementDetail(3L, 4104L);
        assertFalse(d.getAllowed());
        assertEquals(Constants.REFUND_SUCCESS, d.getRefundStatus());
    }

    @Test
    void unknownRefundStatus_deniedByDefault() {
        // 未定义清楚的退款状态一律不得默认授予权益
        seed(Constants.ORDER_STATUS_PAID, 2, 99, 12, null, LocalDateTime.now(), 4105L);
        assertFalse(orderService.entitlementDetail(3L, 4105L).getAllowed());
    }

    @Test
    void refundRejected_keepsEntitlement() {
        seed(Constants.ORDER_STATUS_PAID, 2, Constants.REFUND_REJECTED, 12, null, LocalDateTime.now(), 4106L);
        assertTrue(orderService.entitlementDetail(3L, 4106L).getAllowed());
    }

    @Test
    void expiredByCourseExpireTime_denied() {
        seed(Constants.ORDER_STATUS_PAID, 2, null, null,
                LocalDateTime.now().minusDays(1), LocalDateTime.now(), 4107L);
        EntitlementDecision d = orderService.entitlementDetail(3L, 4107L);
        assertFalse(d.getAllowed());
        assertTrue(d.getReason().contains("有效期"), d.getReason());
    }

    @Test
    void expireTimeDerivedFromPayTime() {
        // 支付时间为 13 个月前，有效期 12 个月 → 已过期
        seed(Constants.ORDER_STATUS_PAID, 2, null, 12, null, LocalDateTime.now().minusMonths(13), 4108L);
        assertFalse(orderService.entitlementDetail(3L, 4108L).getAllowed());
    }

    @Test
    void noValidDuration_meansPermanent() {
        seed(Constants.ORDER_STATUS_PAID, 2, null, null, null, LocalDateTime.now(), 4109L);
        EntitlementDecision d = orderService.entitlementDetail(3L, 4109L);
        assertTrue(d.getAllowed());
        assertNull(d.getExpireTime(), "无有效期视为长期有效");
    }

    @Test
    void otherUserCannotBorrowEntitlement() {
        seed(Constants.ORDER_STATUS_PAID, 2, null, 12, null, LocalDateTime.now(), 4110L);
        assertFalse(orderService.entitlementDetail(9L, 4110L).getAllowed(), "他人不得借用订单权益");
    }

    @Test
    void paidCourseIdsRespectsRefundAndExpiry() {
        seed(Constants.ORDER_STATUS_PAID, 2, Constants.REFUND_SUCCESS, 12, null, LocalDateTime.now(), 4111L);
        seed(Constants.ORDER_STATUS_PAID, 2, null, 12, null, LocalDateTime.now(), 4112L);
        var ids = orderService.paidCourseIds(3L, java.util.List.of(4111L, 4112L));
        assertTrue(ids.contains(4112L));
        assertFalse(ids.contains(4111L), "已退款课程不得计入已购");
    }
}
