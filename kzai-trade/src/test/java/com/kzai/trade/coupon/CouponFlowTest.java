package com.kzai.trade.coupon;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.trade.client.CourseClient;
import com.kzai.trade.dto.CourseSnapshot;
import com.kzai.trade.dto.PrePlaceRequest;
import com.kzai.trade.entity.OrderDetail;
import com.kzai.trade.mapper.OrderDetailMapper;
import com.kzai.trade.payment.RefundAppService;
import com.kzai.trade.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:trade_coupon;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "spring.sql.init.data-locations=classpath:data-test.sql",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.task.scheduling.enabled=false"
})
class CouponFlowTest {

    @Autowired
    private CouponAppService coupons;
    @Autowired
    private CouponOrderService couponOrders;
    @Autowired
    private CouponClock clock;
    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderDetailMapper detailMapper;
    @Autowired
    private MktCouponUserMapper userMapper;
    @Autowired
    private MktCouponTemplateMapper templateMapper;
    @Autowired
    private MktCouponCompensateMapper compensateMapper;
    @Autowired
    private RefundAppService refunds;
    @MockBean
    private CourseClient courseClient;

    @BeforeEach
    void setup() {
        UserContext.set(3L, "student01", Constants.USER_TYPE_STUDENT, List.of(Constants.ROLE_STUDENT), "test");
        clock.use(Clock.fixed(Instant.parse("2026-10-23T02:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    void lastCouponAndDuplicateRedeemAndSnapshot() throws Exception {
        long id = publish("满20减5", "FULL_REDUCTION", 2000, 500, 10000, 1, 1, null, "ALL", List.of());
        AtomicInteger wins = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<?> first = pool.submit(() -> claimRace(id, 3L, wins, ready, go));
        Future<?> second = pool.submit(() -> claimRace(id, 4L, wins, ready, go));
        ready.await();
        go.countDown();
        first.get();
        second.get();
        pool.shutdown();
        assertEquals(1, wins.get());
        assertEquals(1, templateMapper.selectById(id).getClaimedCount());

        long coded = publish("兑换券", "FULL_REDUCTION", 0, 100, 10000, 5, 2, "SAVE100", "ALL", List.of());
        coupons.redeem(3L, "SAVE100");
        BizException again = assertThrows(BizException.class, () -> coupons.redeem(3L, "SAVE100"));
        assertTrue(again.getMessage().contains("兑换码已使用"));

        MktCouponUser owned = userMapper.selectOne(Wrappers.<MktCouponUser>lambdaQuery()
                .eq(MktCouponUser::getTemplateId, coded).eq(MktCouponUser::getUserId, 3L));
        templateMapper.update(null, Wrappers.<MktCouponTemplate>lambdaUpdate()
                .set(MktCouponTemplate::getReduceAmount, 999)
                .eq(MktCouponTemplate::getId, coded));
        assertEquals(100, userMapper.selectById(owned.getId()).getReduceAmount());
    }

    @Test
    void orderLocksConsumesReleasesAndDoesNotReturnCoupon() {
        stub(8001L, 30000);
        long id = publish("满10减5", "FULL_REDUCTION", 1000, 500, 10000, 3, 1, null, "INCLUDE", List.of(8001L));
        Map<String, Object> mine = coupons.claim(3L, id);
        long userCouponId = Long.parseLong(String.valueOf(mine.get("id")));
        Map<String, Object> quote = new CouponQuoteService(courseClient, coupons, clock).quote(3L, List.of(8001L));
        assertTrue(String.valueOf(quote.get("recommendedCouponId")).length() > 0);

        PrePlaceRequest request = new PrePlaceRequest();
        request.setRequestId("coupon-order-1");
        request.setCourseIds(List.of(8001L));
        request.setUserCouponId(userCouponId);
        var placed = orderService.prePlace(3L, request);
        assertEquals(500, placed.getDiscountAmount());
        assertEquals(29500, placed.getRealAmount());
        assertEquals(MktCouponUser.LOCKED, userMapper.selectById(userCouponId).getStatus());

        orderService.settleVerifiedPayment(3L, placed.getOrderId(), "MOCK_WECHAT", 29500, "CNY");
        orderService.settleVerifiedPayment(3L, placed.getOrderId(), "MOCK_WECHAT", 29500, "CNY");
        assertEquals(MktCouponUser.USED, userMapper.selectById(userCouponId).getStatus());
        assertEquals(1, templateMapper.selectById(id).getUsedCount());

        OrderDetail detail = detailMapper.selectOne(Wrappers.<OrderDetail>lambdaQuery()
                .eq(OrderDetail::getOrderId, placed.getOrderId()));
        assertEquals(29500, detail.getRealPayAmount());
        refunds.apply(3L, placed.getOrderId(), detail.getId(), "退课", "refund-1", null);
        BizException over = assertThrows(BizException.class, () -> refunds.apply(3L, placed.getOrderId(), detail.getId(), "再退", "refund-2", 1));
        assertTrue(over.getMessage().contains("无可退") || over.getMessage().contains("处理中"));
        assertEquals(MktCouponUser.USED, userMapper.selectById(userCouponId).getStatus());
    }

    @Test
    void cancelReleasesAndConsumeFailureIsRecoverable() {
        stub(8002L, 10000);
        long id = publish("减1元", "FULL_REDUCTION", 0, 100, 10000, 2, 1, null, "ALL", List.of());
        long userCouponId = Long.parseLong(String.valueOf(coupons.claim(3L, id).get("id")));
        PrePlaceRequest request = new PrePlaceRequest();
        request.setRequestId("coupon-cancel");
        request.setCourseIds(List.of(8002L));
        request.setUserCouponId(userCouponId);
        var placed = orderService.prePlace(3L, request);
        orderService.cancel(3L, placed.getOrderId(), false);
        assertEquals(MktCouponUser.UNUSED, userMapper.selectById(userCouponId).getStatus());

        request.setRequestId("coupon-late");
        var second = orderService.prePlace(3L, request);
        couponOrders.releaseByOrder(second.getOrderId());
        userMapper.update(null, Wrappers.<MktCouponUser>lambdaUpdate()
                .set(MktCouponUser::getStatus, MktCouponUser.USED)
                .set(MktCouponUser::getUsedOrderId, 1L)
                .eq(MktCouponUser::getId, userCouponId));
        couponOrders.consumeByOrder(second.getOrderId());
        assertEquals(1, compensateMapper.selectCount(Wrappers.<MktCouponCompensate>lambdaQuery()
                .eq(MktCouponCompensate::getOrderId, second.getOrderId())
                .eq(MktCouponCompensate::getStatus, MktCouponCompensate.PENDING)));
        userMapper.update(null, Wrappers.<MktCouponUser>lambdaUpdate()
                .set(MktCouponUser::getStatus, MktCouponUser.UNUSED)
                .set(MktCouponUser::getUsedOrderId, null)
                .eq(MktCouponUser::getId, userCouponId));
        assertEquals(1, couponOrders.retryCompensate());
        assertEquals(MktCouponUser.USED, userMapper.selectById(userCouponId).getStatus());
    }

    private void claimRace(long id, long userId, AtomicInteger wins, CountDownLatch ready, CountDownLatch go) {
        ready.countDown();
        try {
            go.await();
            coupons.claim(userId, id);
            wins.incrementAndGet();
        } catch (BizException ex) {
            assertTrue(ex.getMessage().contains("领完") || ex.getMessage().contains("限领") || ex.getMessage().contains("发放"));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private long publish(String name, String type, int threshold, int reduce, int ratio, int stock, int limit,
                         String code, String scope, List<Long> courses) {
        Map<String, Object> body = Map.ofEntries(
                Map.entry("name", name),
                Map.entry("couponType", type),
                Map.entry("thresholdAmount", String.valueOf(threshold)),
                Map.entry("reduceAmount", String.valueOf(Math.max(reduce, 1))),
                Map.entry("payRatio", String.valueOf(ratio)),
                Map.entry("totalStock", String.valueOf(stock)),
                Map.entry("perUserLimit", String.valueOf(limit)),
                Map.entry("claimStart", "2026-10-23T09:00:00"),
                Map.entry("claimEnd", "2026-10-23T18:00:00"),
                Map.entry("validStart", "2026-10-23T09:00:00"),
                Map.entry("validEnd", "2026-10-24T09:00:00"),
                Map.entry("courseScope", scope),
                Map.entry("redeemCode", code == null ? "R" + System.nanoTime() : code),
                Map.entry("courseIds", courses)
        );
        Map<String, Object> created = coupons.create(body);
        long id = Long.parseLong(String.valueOf(created.get("id")));
        coupons.publish(id);
        return id;
    }

    private void stub(long courseId, int price) {
        CourseSnapshot snapshot = new CourseSnapshot();
        snapshot.setId(courseId);
        snapshot.setName("优惠课");
        snapshot.setPrice(price);
        snapshot.setStatus(2);
        snapshot.setDeleted(0);
        snapshot.setCategoryEnabled(true);
        snapshot.setPurchasable(true);
        snapshot.setValidDuration(6);
        when(courseClient.batch(anyString())).thenReturn(R.ok(List.of(snapshot)));
    }
}
