package com.kzai.trade.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.trade.client.CourseClient;
import com.kzai.trade.dto.CourseSnapshot;
import com.kzai.trade.dto.OrderQuery;
import com.kzai.trade.dto.PrePlaceRequest;
import com.kzai.trade.entity.Order;
import com.kzai.trade.entity.OrderDetail;
import com.kzai.trade.entity.OrderRequest;
import com.kzai.trade.mapper.OrderDetailMapper;
import com.kzai.trade.mapper.OrderMapper;
import com.kzai.trade.mapper.OrderRequestMapper;
import com.kzai.trade.payment.TradeRefund;
import com.kzai.trade.payment.TradeRefundMapper;
import com.kzai.trade.vo.OrderDetailVO;
import com.kzai.trade.vo.OrderRefundNoticeVO;
import com.kzai.trade.vo.OrderVO;
import com.kzai.trade.vo.PrePlaceVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 订单服务集成测试：H2（MySQL 兼容模式）真实执行 SQL。
 * 课程服务通过 @MockBean 提供实时课程数据（不造假数据，按契约返回快照）。
 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:trade_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "spring.sql.init.data-locations=classpath:data-test.sql",
        "spring.cloud.nacos.discovery.enabled=false"
})
@Transactional
class OrderServiceTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper detailMapper;
    @Autowired
    private OrderRequestMapper requestMapper;
    @Autowired
    private TradeRefundMapper refundMapper;

    /** 课程服务契约桩：返回实时课程快照，不返回任何"假成功"数据 */
    @MockBean
    private CourseClient courseClient;

    @BeforeEach
    void asStudent() {
        UserContext.set(3L, "student01", Constants.USER_TYPE_STUDENT, List.of(Constants.ROLE_STUDENT), "test");
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void prePlace_createsPendingOrderWithSnapshot() {
        stubCourses(snapshot(7001L, "Java 实战", 29900));

        PrePlaceVO vo = orderService.prePlace(3L, request("req-1", 7001L));

        assertEquals(Constants.ORDER_STATUS_PENDING, vo.getStatus());
        assertEquals(29900, vo.getTotalAmount());
        assertEquals(1, vo.getItems().size());
        assertFalse(vo.getIdempotentHit());

        Order order = orderMapper.selectById(vo.getOrderId());
        assertEquals(Constants.ORDER_STATUS_PENDING, order.getStatus());
        assertEquals(29900, order.getTotalAmount());
        assertEquals(29900, order.getRealAmount());
        assertEquals(0, order.getDiscountAmount());

        List<OrderDetail> details = detailMapper.selectList(
                Wrappers.<OrderDetail>lambdaQuery().eq(OrderDetail::getOrderId, vo.getOrderId()));
        assertEquals(1, details.size());
        assertEquals("Java 实战", details.get(0).getName());
        assertEquals(29900, details.get(0).getPrice());
        assertEquals(Constants.ORDER_STATUS_PENDING, details.get(0).getStatus());
    }

    @Test
    void prePlace_multiCourseAmountIsSumOfCents() {
        stubCourses(snapshot(7002L, "课程A", 12900), snapshot(7003L, "课程B", 9900));

        PrePlaceVO vo = orderService.prePlace(3L, request("req-sum", 7002L, 7003L));
        assertEquals(22800, vo.getTotalAmount());
        assertEquals(2, vo.getItems().size());
    }

    @Test
    void prePlace_duplicateRequestIdReturnsSameOrder() {
        stubCourses(snapshot(7004L, "课程C", 19900));

        PrePlaceVO first = orderService.prePlace(3L, request("req-dup", 7004L));
        PrePlaceVO second = orderService.prePlace(3L, request("req-dup", 7004L));

        assertEquals(first.getOrderId(), second.getOrderId());
        assertTrue(second.getIdempotentHit());
        Long orderCount = orderMapper.selectCount(
                Wrappers.<Order>lambdaQuery().eq(Order::getUserId, 3L));
        // SEED 中用户 3 有 2 笔订单，本次只应新增 1 笔
        assertEquals(3, orderCount);
    }

    @Test
    void prePlace_sameRequestIdDifferentContentConflicts() {
        stubCourses(snapshot(7005L, "课程D", 19900));

        orderService.prePlace(3L, request("req-conflict", 7005L));
        BizException ex = assertThrows(BizException.class,
                () -> orderService.prePlace(3L, request("req-conflict", 7006L)));
        assertTrue(ex.getMessage().contains("requestId"), ex.getMessage());
    }

    @Test
    void prePlace_uniqueIndexIsTheFinalGuard() {
        stubCourses(snapshot(7007L, "课程E", 9900));
        orderService.prePlace(3L, request("req-unique", 7007L));

        // 直接插入相同 (user_id, request_id) 必须被唯一索引拒绝
        OrderRequest duplicate = new OrderRequest();
        duplicate.setUserId(3L);
        duplicate.setRequestId("req-unique");
        duplicate.setRequestFingerprint("x");
        duplicate.setStatus(Constants.IDEMPOTENT_PROCESSING);
        assertThrows(DataIntegrityViolationException.class, () -> requestMapper.insert(duplicate));
    }

    @Test
    void prePlace_rejectsOfflineCourse() {
        CourseSnapshot offline = snapshot(7008L, "已下架课程", 19900);
        offline.setStatus(Constants.COURSE_STATUS_OFFLINE);
        offline.setPurchasable(false);
        offline.setReason("课程未上架");
        stubCourses(offline);

        BizException ex = assertThrows(BizException.class,
                () -> orderService.prePlace(3L, request("req-offline", 7008L)));
        assertTrue(ex.getMessage().contains("不可购买"), ex.getMessage());
    }

    @Test
    void prePlace_rejectsDisabledCategory() {
        CourseSnapshot disabled = snapshot(7009L, "分类禁用课程", 19900);
        disabled.setCategoryEnabled(false);
        disabled.setPurchasable(false);
        disabled.setReason("所属分类已禁用");
        stubCourses(disabled);

        assertThrows(BizException.class, () -> orderService.prePlace(3L, request("req-cat", 7009L)));
    }

    @Test
    void prePlace_rejectsMissingCourse() {
        stubCourses();

        BizException ex = assertThrows(BizException.class,
                () -> orderService.prePlace(3L, request("req-missing", 7999L)));
        assertTrue(ex.getMessage().contains("课程"), ex.getMessage());
    }

    @Test
    void prePlace_rejectsCourseServiceUnavailable() {
        when(courseClient.batch(anyString())).thenThrow(new IllegalStateException("course down"));

        BizException ex = assertThrows(BizException.class,
                () -> orderService.prePlace(3L, request("req-down", 7010L)));
        assertTrue(ex.getMessage().contains("课程服务"), ex.getMessage());
    }

    @Test
    void prePlace_rejectsRepurchaseOfPaidCourse() {
        // SEED：用户 3 已支付课程 4001
        stubCourses(snapshot(4001L, "已购课程", 19900));

        BizException ex = assertThrows(BizException.class,
                () -> orderService.prePlace(3L, request("req-owned", 4001L)));
        assertTrue(ex.getMessage().contains("已购买"), ex.getMessage());
    }

    @Test
    void cancel_pendingOrderAndIdempotentRepeat() {
        String first = orderService.cancel(3L, 5002L, false);
        assertEquals("订单已取消", first);
        Order order = orderMapper.selectById(5002L);
        assertEquals(Constants.ORDER_STATUS_CLOSED, order.getStatus());
        assertNotNull(order.getCloseTime());
        // 明细状态与订单保持一致
        List<OrderDetail> details = detailMapper.selectList(
                Wrappers.<OrderDetail>lambdaQuery().eq(OrderDetail::getOrderId, 5002L));
        assertTrue(details.stream().allMatch(d -> Constants.ORDER_STATUS_CLOSED == d.getStatus()));

        String second = orderService.cancel(3L, 5002L, false);
        assertTrue(second.contains("幂等"), second);
    }

    @Test
    void cancel_rejectsPaidOrder() {
        BizException ex = assertThrows(BizException.class, () -> orderService.cancel(3L, 5001L, false));
        assertTrue(ex.getMessage().contains("仅待支付"), ex.getMessage());
    }

    @Test
    void otherUsersOrderIsForbidden() {
        assertThrows(AuthException.class, () -> orderService.detail(3L, 5003L, false));
        assertThrows(AuthException.class, () -> orderService.cancel(3L, 5003L, false));
        // 管理员可代查
        OrderDetailVO vo = orderService.detail(3L, 5003L, true);
        assertEquals(5003L, vo.getId());
    }

    @Test
    void list_onlyCurrentUserOrders() {
        OrderQuery query = new OrderQuery();
        query.setStatus(Constants.ORDER_STATUS_PENDING);
        var page = orderService.page(3L, query);
        assertTrue(page.getRecords().stream().allMatch(o -> o.getUserId().equals(3L)));
        assertTrue(page.getRecords().stream().allMatch(o -> o.getStatus() == Constants.ORDER_STATUS_PENDING));

        var all = orderService.page(9L, new OrderQuery());
        assertEquals(1, all.getTotal());
        assertTrue(all.getRecords().get(0).getRefundNotices().isEmpty());
    }

    @Test
    void list_showsApproveAndRejectOnTheSameOrder() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 9, 0);
        refundMapper.insert(refund(7101L, "passed-no", "req-pass", TradeRefund.SUCCESS, null, null, now));
        refundMapper.insert(refund(7102L, "rejected-no", "req-reject", TradeRefund.REJECTED, "不符合退款规则", null, now.plusMinutes(1)));
        detailMapper.update(null, Wrappers.<OrderDetail>lambdaUpdate()
                .set(OrderDetail::getRefundStatus, Constants.REFUND_SUCCESS)
                .eq(OrderDetail::getId, 6001L));

        OrderQuery query = new OrderQuery();
        query.setStatus(Constants.ORDER_STATUS_PAID);
        OrderVO order = orderService.page(3L, query).getRecords().stream()
                .filter(item -> item.getId().equals(5001L))
                .findFirst()
                .orElseThrow();
        List<OrderRefundNoticeVO> notices = order.getRefundNotices();
        assertEquals(1, notices.size());
        OrderRefundNoticeVO latest = notices.get(0);
        assertEquals("已驳回", latest.getStatusText());
        assertEquals("REJECTED", latest.getOutcome());
        assertEquals("不符合退款规则", latest.getMessage());
        assertEquals(Constants.ORDER_STATUS_PAID, order.getStatus());
        assertEquals("已退款", order.getStatusText());
    }

    private TradeRefund refund(long id, String refundNo, String requestId, String status,
                               String rejectReason, String failReason, LocalDateTime time) {
        TradeRefund refund = new TradeRefund();
        refund.setId(id);
        refund.setRefundNo(refundNo);
        refund.setRequestId(requestId);
        refund.setOrderId(5001L);
        refund.setDetailId(6001L);
        refund.setUserId(3L);
        refund.setAmount(19900);
        refund.setReason("测试");
        refund.setStatus(status);
        refund.setRejectReason(rejectReason);
        refund.setFailReason(failReason);
        refund.setCreateTime(time);
        refund.setUpdateTime(time);
        return refund;
    }

    @Test
    void entitlement_paidCourseOnly() {
        assertTrue(orderService.hasPaidEntitlement(3L, 4001L));
        assertFalse(orderService.hasPaidEntitlement(3L, 4002L));
        assertEquals(1, orderService.countByUser(3L) > 0 ? 1 : 0);
    }

    private PrePlaceRequest request(String requestId, Long... courseIds) {
        PrePlaceRequest request = new PrePlaceRequest();
        request.setRequestId(requestId);
        request.setCourseIds(List.of(courseIds));
        return request;
    }

    private CourseSnapshot snapshot(Long id, String name, int price) {
        CourseSnapshot snapshot = new CourseSnapshot();
        snapshot.setId(id);
        snapshot.setName(name);
        snapshot.setPrice(price);
        snapshot.setCoverUrl("/files/" + id + "/download");
        snapshot.setValidDuration(12);
        snapshot.setStatus(Constants.COURSE_STATUS_ONLINE);
        snapshot.setDeleted(0);
        snapshot.setCategoryEnabled(true);
        snapshot.setPurchasable(true);
        return snapshot;
    }

    private void stubCourses(CourseSnapshot... snapshots) {
        when(courseClient.batch(anyString())).thenReturn(R.ok(List.of(snapshots)));
    }
}
