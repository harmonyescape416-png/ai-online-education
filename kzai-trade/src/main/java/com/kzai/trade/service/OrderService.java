package com.kzai.trade.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.common.result.ResultCode;
import com.kzai.common.util.MoneyUtils;
import com.kzai.trade.client.CourseClient;
import com.kzai.trade.coupon.CouponOrderService;
import com.kzai.trade.dto.CourseSnapshot;
import com.kzai.trade.dto.EntitlementDecision;
import com.kzai.trade.dto.OrderQuery;
import com.kzai.trade.dto.PrePlaceRequest;
import com.kzai.trade.entity.Order;
import com.kzai.trade.entity.OrderDetail;
import com.kzai.trade.entity.OrderRequest;
import com.kzai.trade.mapper.OrderDetailMapper;
import com.kzai.trade.mapper.OrderMapper;
import com.kzai.trade.mapper.OrderRequestMapper;
import com.kzai.trade.payment.OrderRefundNoticeService;
import com.kzai.trade.vo.OrderDetailVO;
import com.kzai.trade.vo.OrderItemVO;
import com.kzai.trade.vo.OrderRefundNoticeVO;
import com.kzai.trade.vo.OrderVO;
import com.kzai.trade.vo.PrePlaceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单服务（M4）：购买预下单、订单查询、取消、已购校验。
 * <p>
 * 设计约束：
 * <ul>
 *   <li>金额一律 INT 分，服务端依据课程实时价格计算，不接受前端价格/折扣/用户ID；</li>
 *   <li>幂等以「数据库唯一索引 + 同一事务」保证，Redis 不做唯一性依据；</li>
 *   <li>课程数据只取课程服务内部接口，服务不可用时报受控错误，不使用假数据。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderDetailMapper detailMapper;
    private final OrderRequestMapper requestMapper;
    private final CourseClient courseClient;
    private final com.kzai.trade.client.InternalLearningClient internalLearningClient;
    private final com.kzai.trade.compensation.EnrollCompensationService enrollCompensationService;
    private final OrderRefundNoticeService refundNoticeService;
    private final CouponOrderService couponOrderService;
    private final PlatformTransactionManager transactionManager;

    @Value("${kzai.trade.max-items:20}")
    private int maxItems;

    /**
     * 购买预下单：同一事务内写入订单、明细与幂等记录。
     * <p>
     * 并发控制：uk_user_request 唯一索引保证只有一笔成功；冲突时本事务回滚，
     * 外层再读取已有记录返回同一订单。
     */
    public PrePlaceVO prePlace(Long userId, PrePlaceRequest request) {
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            return tx.execute(status -> doPrePlace(userId, request));
        } catch (DataIntegrityViolationException e) {
            // 并发下唯一索引冲突：本事务已回滚（不会留下孤立订单/明细/幂等记录），回读已有结果
            log.info("[trade] concurrent pre-place detected, userId={} requestId={}",
                    userId, request == null ? null : request.getRequestId());
            PrePlaceVO existing = readExistingResult(userId, request.getRequestId().trim());
            if (existing != null) {
                return existing;
            }
            throw new BizException("该请求正在处理中，请稍后重试");
        }
    }

    @Transactional
    protected PrePlaceVO doPrePlace(Long userId, PrePlaceRequest request) {
        List<Long> courseIds = normalizeCourseIds(request.getCourseIds());
        String requestId = request.getRequestId().trim();
        String fingerprint = fingerprint(courseIds, request.getUserCouponId());

        OrderRequest existing = findRequest(userId, requestId);
        if (existing != null) {
            if (!fingerprint.equals(existing.getRequestFingerprint())) {
                throw new BizException("requestId 已用于其他购买内容，请更换 requestId 后重试");
            }
            if (existing.getOrderId() != null
                    && Integer.valueOf(Constants.IDEMPOTENT_DONE).equals(existing.getStatus())) {
                return toPrePlaceVO(requireOrder(existing.getOrderId()), true, requestId);
            }
            throw new BizException("该请求正在处理中，请稍后重试");
        }

        // 课程实时数据：不信任任何前端提交的课程信息
        List<CourseSnapshot> snapshots = fetchSnapshots(courseIds);
        checkPurchasable(snapshots);
        checkNotPurchased(userId, courseIds);

        long total = 0L;
        for (CourseSnapshot snapshot : snapshots) {
            total += snapshot.getPrice() == null ? 0 : snapshot.getPrice();
        }
        if (total > Integer.MAX_VALUE) {
            throw new BizException("订单金额超出允许范围");
        }
        int totalAmount = (int) total;

        LocalDateTime now = LocalDateTime.now();
        // 1) 先占幂等位（处理中），唯一索引冲突即并发重复
        OrderRequest orderRequest = new OrderRequest();
        orderRequest.setUserId(userId);
        orderRequest.setRequestId(requestId);
        orderRequest.setRequestFingerprint(fingerprint);
        orderRequest.setStatus(Constants.IDEMPOTENT_PROCESSING);
        requestMapper.insert(orderRequest);

        // 2) 订单主表
        Order order = new Order();
        order.setUserId(userId);
        order.setStatus(Constants.ORDER_STATUS_PENDING);
        order.setTotalAmount(totalAmount);
        order.setRealAmount(totalAmount);
        order.setDiscountAmount(0);
        order.setMessage("预下单成功，等待支付");
        order.setCreater(userId);
        order.setUpdater(userId);
        order.setDeleted(0);
        // createTime 显式写入，保证响应中能直接返回下单时间
        order.setCreateTime(now);
        order.setUpdateTime(now);
        orderMapper.insert(order);

        // 3) 订单明细：保存课程快照
        List<OrderDetail> details = new ArrayList<>();
        for (CourseSnapshot snapshot : snapshots) {
            OrderDetail detail = new OrderDetail();
            detail.setOrderId(order.getId());
            detail.setUserId(userId);
            detail.setCourseId(snapshot.getId());
            detail.setPrice(snapshot.getPrice());
            detail.setName(snapshot.getName());
            detail.setCoverUrl(snapshot.getCoverUrl());
            detail.setValidDuration(snapshot.getValidDuration());
            detail.setDiscountAmount(0);
            detail.setRealPayAmount(snapshot.getPrice());
            detail.setStatus(Constants.ORDER_STATUS_PENDING);
            detail.setCreater(userId);
            detail.setUpdater(userId);
            detailMapper.insert(detail);
            details.add(detail);
        }
        if (request.getUserCouponId() != null) {
            couponOrderService.attach(userId, request.getUserCouponId(), order, details);
        }

        // 4) 幂等记录回写订单号
        orderRequest.setOrderId(order.getId());
        orderRequest.setStatus(Constants.IDEMPOTENT_DONE);
        requestMapper.updateById(orderRequest);

        log.info("[trade] pre-place ok: order={} user={} amount={} items={}",
                order.getId(), userId, totalAmount, details.size());
        return toPrePlaceVO(order, false, requestId);
    }

    /** 我的订单：强制按当前登录用户过滤 */
    public IPage<OrderVO> page(Long userId, OrderQuery query) {
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1 ? 10 : query.getPageSize();

        var wrapper = Wrappers.<Order>lambdaQuery().eq(Order::getUserId, userId);
        if (query.getStatus() != null) {
            wrapper.eq(Order::getStatus, query.getStatus());
        }
        if ("createTimeAsc".equalsIgnoreCase(query.getSort())) {
            wrapper.orderByAsc(Order::getCreateTime);
        } else {
            wrapper.orderByDesc(Order::getCreateTime);
        }
        Page<Order> page = orderMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        List<Long> orderIds = page.getRecords().stream().map(Order::getId).toList();
        Map<Long, Integer> itemCounts = itemCounts(orderIds);
        Map<Long, List<OrderRefundNoticeVO>> notices = refundNoticeService.forOrders(orderIds);
        Set<Long> refunded = refundNoticeService.successfullyRefunded(orderIds);
        return page.convert(order -> toOrderVO(order, itemCounts.getOrDefault(order.getId(), 0),
                notices.getOrDefault(order.getId(), List.of()), refunded.contains(order.getId())));
    }

    public OrderDetailVO detail(Long userId, Long orderId, boolean allowAdmin) {
        Order order = requireOwnedOrder(userId, orderId, allowAdmin);
        List<OrderDetail> details = detailMapper.selectList(
                Wrappers.<OrderDetail>lambdaQuery().eq(OrderDetail::getOrderId, orderId));
        return OrderDetailVO.builder()
                .id(order.getId())
                .userId(order.getUserId())
                .status(order.getStatus())
                .statusText(refundNoticeService.successfullyRefunded(List.of(orderId)).contains(orderId)
                        ? "已退款" : OrderSupport.statusText(order.getStatus()))
                .totalAmount(order.getTotalAmount())
                .totalAmountYuan(MoneyUtils.centsToYuan(order.getTotalAmount()))
                .realAmount(order.getRealAmount())
                .realAmountYuan(MoneyUtils.centsToYuan(order.getRealAmount()))
                .discountAmount(order.getDiscountAmount())
                .message(order.getMessage())
                .createTime(order.getCreateTime())
                .payTime(order.getPayTime())
                .closeTime(order.getCloseTime())
                .items(details.stream().map(this::toItemVO).toList())
                .build();
    }

    /**
     * 取消订单：仅所有者可取消，仅待支付可取消；已关闭订单重复取消按幂等成功处理。
     */
    @Transactional
    public String cancel(Long userId, Long orderId, boolean allowAdmin) {
        Order order = requireOwnedOrder(userId, orderId, allowAdmin);
        if (Integer.valueOf(Constants.ORDER_STATUS_CLOSED).equals(order.getStatus())) {
            couponOrderService.releaseByOrder(orderId);
            return "订单已取消（重复取消按幂等处理）";
        }
        if (!Integer.valueOf(Constants.ORDER_STATUS_PENDING).equals(order.getStatus())) {
            throw new BizException("仅待支付订单可取消，当前状态：" + OrderSupport.statusText(order.getStatus()));
        }
        LocalDateTime now = LocalDateTime.now();
        // 条件更新：并发取消时只有一个请求能把状态从待支付改为已关闭
        int rows = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getStatus, Constants.ORDER_STATUS_CLOSED)
                .set(Order::getCloseTime, now)
                .set(Order::getUpdateTime, now)
                .set(Order::getMessage, "用户取消订单")
                .set(Order::getUpdater, userId)
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, Constants.ORDER_STATUS_PENDING));
        if (rows == 0) {
            Order current = orderMapper.selectById(orderId);
            if (current != null && Integer.valueOf(Constants.ORDER_STATUS_CLOSED).equals(current.getStatus())) {
                return "订单已取消（重复取消按幂等处理）";
            }
            throw new BizException("订单状态已变更，无法取消，请刷新后重试");
        }
        // 明细状态与订单保持一致（沿用原文字段语义）
        detailMapper.update(null, Wrappers.<OrderDetail>lambdaUpdate()
                .set(OrderDetail::getStatus, Constants.ORDER_STATUS_CLOSED)
                .set(OrderDetail::getUpdateTime, now)
                .set(OrderDetail::getUpdater, userId)
                .eq(OrderDetail::getOrderId, orderId)
                .eq(OrderDetail::getStatus, Constants.ORDER_STATUS_PENDING));
        couponOrderService.releaseByOrder(orderId);
        log.info("[trade] order canceled: order={} user={}", orderId, userId);
        return "订单已取消";
    }

    private static String channelLabel(String channel) {
        if (channel == null || channel.isBlank()) {
            return "未记录渠道";
        }
        return switch (channel) {
            case "MOCK_WECHAT" -> "模拟微信支付";
            case "MOCK_ALIPAY" -> "模拟支付宝";
            case "ALIPAY_SANDBOX" -> "支付宝沙箱";
            default -> channel;
        };
    }

    /** M15.15：明细状态同步已支付 + 渠道落库（enroll 权益判定依赖明细状态） */
    private void syncDetailPaid(Long userId, Long orderId, String channel) {
        var update = Wrappers.<com.kzai.trade.entity.OrderDetail>lambdaUpdate()
                .set(com.kzai.trade.entity.OrderDetail::getStatus, Constants.ORDER_STATUS_PAID)
                .set(com.kzai.trade.entity.OrderDetail::getUpdater, userId)
                .eq(com.kzai.trade.entity.OrderDetail::getOrderId, orderId);
        if (channel != null && !channel.isBlank()) {
            update.set(com.kzai.trade.entity.OrderDetail::getPayChannel, channel);
        }
        detailMapper.update(null, update);
    }

    /**
     * 只接受已经验签、且金额币种核对通过的支付结果。
     * @return true 表示本次把订单推进到已支付并登记了开课补偿；false 表示已是终态，不得再次开课。
     */
    @Transactional
    public boolean settleVerifiedPayment(long userId, long orderId, String channel, int paidAmount, String currency) {
        if (!"CNY".equals(currency)) {
            throw new BizException("只接受人民币订单");
        }
        Order order = orderMapper.selectById(orderId);
        if (order == null || Integer.valueOf(1).equals(order.getDeleted())) {
            throw new BizException("订单不存在");
        }
        int expect = order.getRealAmount() == null ? 0 : order.getRealAmount();
        if (paidAmount != expect) {
            throw new BizException("支付金额与订单实付不一致");
        }
        if (OrderSupport.paid(order.getStatus())) {
            syncDetailPaid(userId, orderId, channel);
            couponOrderService.consumeByOrder(orderId);
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean timeoutClosed = Integer.valueOf(Constants.ORDER_STATUS_CLOSED).equals(order.getStatus())
                && order.getMessage() != null && order.getMessage().startsWith("超时未支付");
        int rows;
        if (timeoutClosed) {
            rows = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                    .set(Order::getStatus, Constants.ORDER_STATUS_PAID)
                    .set(Order::getPayTime, now)
                    .set(Order::getUpdateTime, now)
                    .set(Order::getMessage, "超时关闭后收到已验签的支付成功")
                    .set(Order::getPayChannel, channel)
                    .eq(Order::getId, orderId)
                    .eq(Order::getStatus, Constants.ORDER_STATUS_CLOSED));
        } else if (Integer.valueOf(Constants.ORDER_STATUS_PENDING).equals(order.getStatus())) {
            rows = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                    .set(Order::getStatus, Constants.ORDER_STATUS_PAID)
                    .set(Order::getPayTime, now)
                    .set(Order::getUpdateTime, now)
                    .set(Order::getMessage, "支付成功（" + channelLabel(channel) + "）")
                    .set(Order::getPayChannel, channel)
                    .eq(Order::getId, orderId)
                    .eq(Order::getStatus, Constants.ORDER_STATUS_PENDING));
        } else {
            return false;
        }
        if (rows != 1) {
            Order latest = orderMapper.selectById(orderId);
            return latest != null && OrderSupport.paid(latest.getStatus()) ? false : false;
        }
        syncDetailPaid(userId, orderId, channel);
        couponOrderService.consumeByOrder(orderId);
        List<Long> courseIds = detailMapper.selectList(Wrappers.<OrderDetail>lambdaQuery()
                        .eq(OrderDetail::getOrderId, orderId))
                .stream().map(OrderDetail::getCourseId).distinct().toList();
        enrollCompensationService.registerPending(userId, orderId, courseIds);
        return true;
    }

    /**
     * M15.19-C：模拟支付——仅完成订单/明细状态流转（事务内），开课由 Controller 在事务提交后调用
     * enrollPaidCourses（同步返回准确开课结果；失败持久化到 order.message 供重试与补偿）。
     *
     * @param channel 支付渠道标识（WECHAT/ALIPAY，落明细 pay_channel）
     */
    @Transactional
    public Map<String, Object> payOrder(Long userId, Long orderId, String channel, boolean allowAdmin) {
        Order order = requireOwnedOrder(userId, orderId, allowAdmin);
        if (Integer.valueOf(Constants.ORDER_STATUS_PAID).equals(order.getStatus())) {
            // 幂等分支同样同步明细状态/渠道（兜底：首次开课失败时的重试路径）
            syncDetailPaid(userId, orderId, channel);
            // M15.19-C：开课由 Controller 在事务提交后统一调用 enrollPaidCourses（失败持久化可重试）
            return Map.of("orderId", String.valueOf(orderId), "status", 2,
                    "paymentStatus", "PAID", "alreadyPaid", true,
                    "channel", channel == null ? "" : channel);
        }
        if (!Integer.valueOf(Constants.ORDER_STATUS_PENDING).equals(order.getStatus())) {
            throw new BizException("仅待支付订单可支付，当前状态：" + OrderSupport.statusText(order.getStatus()));
        }
        LocalDateTime now = LocalDateTime.now();
        // 条件更新：并发支付时只有一个请求能把状态从待支付改为已支付
        int rows = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getStatus, Constants.ORDER_STATUS_PAID)
                .set(Order::getPayTime, now)
                .set(Order::getUpdateTime, now)
                .set(Order::getMessage, "支付成功（" + channelLabel(channel) + "）")
                .set(Order::getUpdater, userId)
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, Constants.ORDER_STATUS_PENDING));
        if (rows == 0) {
            throw new BizException("支付处理冲突，请刷新后重试");
        }
        syncDetailPaid(userId, orderId, channel);
        // M15.20-C：事务内登记 PENDING 补偿记录（进程中断/开课失败均可恢复）
        List<Long> payCourseIds = detailMapper.selectList(Wrappers.<com.kzai.trade.entity.OrderDetail>lambdaQuery()
                .eq(com.kzai.trade.entity.OrderDetail::getOrderId, orderId))
                .stream().map(com.kzai.trade.entity.OrderDetail::getCourseId).distinct().toList();
        enrollCompensationService.registerPending(userId, orderId, payCourseIds);
        return Map.of("orderId", String.valueOf(orderId), "status", 2,
                "paymentStatus", "PAID",
                "channel", channel == null ? "" : channel);
    }

    /** M15.19-C：支付成功后开课（公开；Controller 事务提交后同步调用；失败持久化 order.message 供重试） */
    public Map<String, Object> enrollPaidCourses(Long userId, Long orderId) {
        List<Long> courseIds = detailMapper.selectList(Wrappers.<com.kzai.trade.entity.OrderDetail>lambdaQuery()
                .eq(com.kzai.trade.entity.OrderDetail::getOrderId, orderId))
                .stream().map(com.kzai.trade.entity.OrderDetail::getCourseId).distinct().toList();
        java.util.List<Map<String, Object>> enrolled = new java.util.ArrayList<>();
        java.util.List<String> enrollErrors = new java.util.ArrayList<>();
        for (Long courseId : courseIds) {
            try {
                var r = internalLearningClient.enrollForPaid(Map.of("userId", String.valueOf(userId),
                        "courseId", String.valueOf(courseId)));
                if (r != null && Integer.valueOf(200).equals(r.getCode())) {
                    enrolled.add(Map.of("courseId", String.valueOf(courseId)));
                } else {
                    enrollErrors.add("课程 " + courseId + " 开课失败");
                }
            } catch (Exception ex) {
                enrollErrors.add("课程 " + courseId + " 开课失败：" + (ex.getMessage() == null ? "未知" : ex.getMessage()));
            }
        }
        // M15.19-C：开课结果持久化（order.message）——失败可见且 alreadyPaid 重试路径可补偿
        String enrollMsg = enrollErrors.isEmpty()
                ? "支付成功，已开课 " + enrolled.size() + " 门"
                : "支付成功；开课失败 " + enrollErrors.size() + " 门（" + String.join("；", enrollErrors) + "），重试支付可补偿";
        orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getMessage, enrollMsg)
                .set(Order::getUpdater, userId)
                .eq(Order::getId, orderId));
        return Map.of("orderId", String.valueOf(orderId), "status", 2,
                "enrolledCount", enrolled.size(), "enrollErrors", enrollErrors,
                "enrollMessage", enrollMsg);
    }


    /** 内部：用户订单数（删除用户前的关联校验） */
    public long countByUser(Long userId) {
        Long count = orderMapper.selectCount(Wrappers.<Order>lambdaQuery().eq(Order::getUserId, userId));
        return count == null ? 0L : count;
    }

    /** 内部：是否已取得课程学习权益（严格判定：订单状态 + 明细状态 + 退款状态 + 有效期） */
    public boolean hasPaidEntitlement(Long userId, Long courseId) {
        return Boolean.TRUE.equals(entitlementDetail(userId, courseId).getAllowed());
    }

    /**
     * 内部：结构化学习权益判定（M5）。
     * <p>
     * 判定链：订单归属（user_id）→ 订单主体状态 → 订单明细状态 → 退款状态 → 学习有效期。
     * 任何一环不满足即判定为无权益，并给出面向用户的明确原因。
     */
    public EntitlementDecision entitlementDetail(Long userId, Long courseId) {
        if (userId == null || courseId == null) {
            return EntitlementDecision.deny("用户或课程信息缺失，无法校验学习权益");
        }
        List<Order> orders = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, userId)
                .in(Order::getStatus, OrderSupport.paidStatusList()));
        if (orders.isEmpty()) {
            return EntitlementDecision.deny("未查询到该课程的已支付订单，请先购买课程");
        }
        Map<Long, Order> orderById = orders.stream()
                .collect(Collectors.toMap(Order::getId, Function.identity(), (a, b) -> a));
        List<OrderDetail> details = detailMapper.selectList(Wrappers.<OrderDetail>lambdaQuery()
                .in(OrderDetail::getOrderId, orderById.keySet())
                .eq(OrderDetail::getCourseId, courseId)
                .eq(OrderDetail::getUserId, userId));
        if (details.isEmpty()) {
            return EntitlementDecision.deny("未查询到该课程的已支付订单，请先购买课程");
        }

        EntitlementDecision fallback = null;
        boolean sawDetail = false;
        boolean fullRefundOnly = true;
        LocalDateTime now = LocalDateTime.now();
        for (OrderDetail detail : details) {
            Order order = orderById.get(detail.getOrderId());
            if (order == null) {
                continue;
            }
            if (!OrderSupport.paid(order.getStatus())) {
                fallback = EntitlementDecision.deny("订单当前状态为「"
                        + OrderSupport.statusText(order.getStatus()) + "」，未取得学习权益");
                continue;
            }
            if (!OrderSupport.paid(detail.getStatus())) {
                fallback = EntitlementDecision.deny("课程订单明细状态为「"
                        + OrderSupport.statusText(detail.getStatus()) + "」，未取得学习权益");
                continue;
            }
            sawDetail = true;
            if (OrderSupport.refundBlocksEntitlement(detail.getRefundStatus())) {
                if (!Integer.valueOf(Constants.REFUND_SUCCESS).equals(detail.getRefundStatus())) {
                    fullRefundOnly = false;
                }
                fallback = EntitlementDecision.deny("该课程订单处于「"
                        + OrderSupport.refundStatusText(detail.getRefundStatus()) + "」状态，学习权益已暂停");
                continue;
            }
            LocalDateTime expireTime = expireTime(detail, order);
            if (expireTime != null && expireTime.isBefore(now)) {
                fullRefundOnly = false;
                fallback = EntitlementDecision.deny("课程学习有效期已于 "
                        + expireTime.toLocalDate() + " 到期，请重新购买后继续学习");
                continue;
            }
            return EntitlementDecision.grant(order.getId(), detail.getStatus(),
                    detail.getRefundStatus(), expireTime);
        }
        EntitlementDecision denied = fallback == null
                ? EntitlementDecision.deny("未取得该课程的学习权益，请先购买课程")
                : fallback;
        if (sawDetail && fullRefundOnly) {
            denied.setRefundStatus(Constants.REFUND_SUCCESS);
        }
        return denied;
    }

    /**
     * 学习有效期：优先取明细快照 course_expire_time；
     * 否则按 valid_duration（月）自支付时间（无支付时间则取下单时间）推算；
     * 两者都没有时表示长期有效（NULL）。
     */
    private LocalDateTime expireTime(OrderDetail detail, Order order) {
        if (detail.getCourseExpireTime() != null) {
            return detail.getCourseExpireTime();
        }
        Integer months = detail.getValidDuration();
        if (months == null || months <= 0) {
            return null;
        }
        LocalDateTime base = order.getPayTime() != null ? order.getPayTime() : order.getCreateTime();
        if (base == null) {
            return null;
        }
        return base.plusMonths(months);
    }

    /** 内部：批量判断已购课程（供课程服务校验付费资源访问，与单课程判定同规则） */
    public Set<Long> paidCourseIds(Long userId, List<Long> courseIds) {
        if (userId == null || courseIds == null || courseIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> result = new LinkedHashSet<>();
        for (Long courseId : courseIds) {
            if (courseId != null && Boolean.TRUE.equals(
                    entitlementDetail(userId, courseId).getAllowed())) {
                result.add(courseId);
            }
        }
        return result;
    }

    private List<Long> paidOrderIds(Long userId) {
        List<Order> orders = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, userId)
                .in(Order::getStatus, OrderSupport.paidStatusList()));
        return orders.stream().map(Order::getId).toList();
    }

    private void checkNotPurchased(Long userId, List<Long> courseIds) {
        List<Long> paidOrderIds = paidOrderIds(userId);
        if (paidOrderIds.isEmpty()) {
            return;
        }
        List<OrderDetail> owned = detailMapper.selectList(Wrappers.<OrderDetail>lambdaQuery()
                .in(OrderDetail::getOrderId, paidOrderIds)
                .in(OrderDetail::getCourseId, courseIds)
                .in(OrderDetail::getStatus, OrderSupport.paidStatusList()));
        if (!owned.isEmpty()) {
            Set<Long> ownedIds = owned.stream().map(OrderDetail::getCourseId).collect(Collectors.toSet());
            throw new BizException("课程已购买，无需重复下单：" + ownedIds);
        }
    }

    private List<CourseSnapshot> fetchSnapshots(List<Long> courseIds) {
        String ids = courseIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        R<List<CourseSnapshot>> result;
        try {
            result = courseClient.batch(ids);
        } catch (Exception e) {
            // 课程服务不可用：返回受控错误，绝不使用临时假数据下单
            log.error("[trade] course service unavailable: {}", e.getMessage());
            throw new BizException("课程服务暂时不可用，请稍后重试");
        }
        List<CourseSnapshot> snapshots = result == null ? null : result.getData();
        if (snapshots == null || snapshots.size() != courseIds.size()) {
            throw new BizException("课程信息获取失败，请稍后重试");
        }
        Map<Long, CourseSnapshot> byId = snapshots.stream()
                .collect(Collectors.toMap(CourseSnapshot::getId, Function.identity(), (a, b) -> a));
        List<CourseSnapshot> ordered = new ArrayList<>();
        for (Long courseId : courseIds) {
            CourseSnapshot snapshot = byId.get(courseId);
            if (snapshot == null) {
                throw new BizException("课程不存在：" + courseId);
            }
            ordered.add(snapshot);
        }
        return ordered;
    }

    private void checkPurchasable(List<CourseSnapshot> snapshots) {
        for (CourseSnapshot snapshot : snapshots) {
            if (snapshot.getId() == null || Integer.valueOf(1).equals(snapshot.getDeleted())) {
                throw new BizException("课程不存在：" + snapshot.getId());
            }
            if (!Boolean.TRUE.equals(snapshot.getPurchasable())) {
                String reason = StringUtils.hasText(snapshot.getReason())
                        ? snapshot.getReason() : "当前课程不可购买";
                throw new BizException("课程《" + snapshot.getName() + "》不可购买：" + reason);
            }
            if (snapshot.getPrice() == null || snapshot.getPrice() < 0) {
                throw new BizException("课程价格异常：" + snapshot.getId());
            }
        }
    }

    private List<Long> normalizeCourseIds(List<Long> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            throw new BizException("请选择要购买的课程");
        }
        for (Long courseId : courseIds) {
            if (courseId == null || courseId <= 0) {
                throw new BizException("课程ID非法");
            }
        }
        // 去重后保持顺序
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(courseIds));
        if (distinct.size() != courseIds.size()) {
            throw new BizException("课程ID重复：" + courseIds);
        }
        if (distinct.size() > maxItems) {
            throw new BizException("单笔订单最多 " + maxItems + " 门课程");
        }
        return distinct;
    }

    private OrderRequest findRequest(Long userId, String requestId) {
        List<OrderRequest> records = requestMapper.selectList(
                Wrappers.<OrderRequest>lambdaQuery()
                        .eq(OrderRequest::getUserId, userId)
                        .eq(OrderRequest::getRequestId, requestId));
        return records.isEmpty() ? null : records.get(0);
    }

    private PrePlaceVO readExistingResult(Long userId, String requestId) {
        OrderRequest existing = findRequest(userId, requestId);
        if (existing == null || existing.getOrderId() == null
                || !Integer.valueOf(Constants.IDEMPOTENT_DONE).equals(existing.getStatus())) {
            return null;
        }
        return toPrePlaceVO(requireOrder(existing.getOrderId()), true, requestId);
    }

    private Order requireOrder(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException("订单不存在");
        }
        return order;
    }

    public Order requireOwnedOrder(Long userId, Long orderId, boolean allowAdmin) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException("订单不存在");
        }
        if (!Objects.equals(order.getUserId(), userId) && !allowAdmin) {
            throw new AuthException(ResultCode.FORBIDDEN, "无权访问该订单");
        }
        return order;
    }

    private Map<Long, Integer> itemCounts(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        List<OrderDetail> details = detailMapper.selectList(
                Wrappers.<OrderDetail>lambdaQuery().in(OrderDetail::getOrderId, orderIds));
        return details.stream().collect(Collectors.groupingBy(OrderDetail::getOrderId,
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));
    }

    private PrePlaceVO toPrePlaceVO(Order order, boolean idempotentHit, String requestId) {
        List<OrderDetail> details = detailMapper.selectList(
                Wrappers.<OrderDetail>lambdaQuery().eq(OrderDetail::getOrderId, order.getId()));
        return PrePlaceVO.builder()
                .orderId(order.getId())
                .userId(order.getUserId())
                .status(order.getStatus())
                .statusText(OrderSupport.statusText(order.getStatus()))
                .totalAmount(order.getTotalAmount())
                .totalAmountYuan(MoneyUtils.centsToYuan(order.getTotalAmount()))
                .realAmount(order.getRealAmount())
                .discountAmount(order.getDiscountAmount())
                .requestId(requestId)
                .idempotentHit(idempotentHit)
                .createTime(order.getCreateTime())
                .items(details.stream().map(this::toItemVO).toList())
                .build();
    }

    private OrderVO toOrderVO(Order order, int itemCount, List<OrderRefundNoticeVO> refundNotices, boolean refunded) {
        return OrderVO.builder()
                .id(order.getId())
                .userId(order.getUserId())
                .status(order.getStatus())
                .statusText(refunded ? "已退款" : OrderSupport.statusText(order.getStatus()))
                .totalAmount(order.getTotalAmount())
                .totalAmountYuan(MoneyUtils.centsToYuan(order.getTotalAmount()))
                .realAmount(order.getRealAmount())
                .realAmountYuan(MoneyUtils.centsToYuan(order.getRealAmount()))
                .discountAmount(order.getDiscountAmount())
                .message(order.getMessage())
                .itemCount(itemCount)
                .createTime(order.getCreateTime())
                .payTime(order.getPayTime())
                .closeTime(order.getCloseTime())
                .refundNotices(refundNotices)
                .build();
    }

    private OrderItemVO toItemVO(OrderDetail detail) {
        return OrderItemVO.builder()
                .id(detail.getId())
                .courseId(detail.getCourseId())
                .name(detail.getName())
                .coverUrl(detail.getCoverUrl())
                .price(detail.getPrice())
                .priceYuan(MoneyUtils.centsToYuan(detail.getPrice()))
                .validDuration(detail.getValidDuration())
                .status(detail.getStatus())
                .statusText(OrderSupport.statusText(detail.getStatus()))
                .refundStatus(detail.getRefundStatus())
                .build();
    }

    /** 请求内容摘要：课程ID升序拼接后取 SHA-256，用于识别"同 requestId 不同内容" */
    private String fingerprint(List<Long> courseIds, Long couponId) {
        String raw = courseIds.stream().sorted().map(String::valueOf)
                .collect(Collectors.joining(","));
        if (couponId != null) {
            raw = raw + "|c" + couponId;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 必然可用，退化时使用原始串（不影响唯一性判定的正确性）
            return raw;
        }
    }

    /** 当前用户是否后台管理员（用于订单的越权放行判断） */
    public static boolean isBackstageAdmin() {
        return UserContext.hasRole(Constants.ROLE_ADMIN) || UserContext.hasRole(Constants.ROLE_STAFF);
    }
}
