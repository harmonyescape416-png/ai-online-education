package com.kzai.trade.compensation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.exception.BizException;
import com.kzai.trade.client.InternalLearningClient;
import com.kzai.trade.mapper.OrderDetailMapper;
import com.kzai.trade.mapper.OrderMapper;
import com.kzai.trade.compensation.EnrollCompensationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * M15.20-C：开课失败补偿闭环。
 * 支付事务内登记 PENDING（进程中断/未调 learning 可恢复）；同步执行成功→SUCCESS；
 * 失败 attempts++/退避（1/2/4 分钟）→3 次后 FAILED_MANUAL；定时扫描自动重试；人工重试接口。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EnrollCompensationService {

    private final EnrollCompensationMapper mapper;
    private final org.springframework.beans.factory.ObjectProvider<InternalLearningClient> learningClientProvider;
        private final AtomicBoolean retryLock = new AtomicBoolean(false);

    /** 支付事务内登记 PENDING（每订单+课程唯一，幂等） */
    @Transactional
    public void registerPending(Long userId, Long orderId, List<Long> courseIds) {
        for (Long courseId : courseIds) {
            Long exists = mapper.selectCount(Wrappers.<EnrollCompensationEntity>lambdaQuery()
                    .eq(EnrollCompensationEntity::getOrderId, orderId)
                    .eq(EnrollCompensationEntity::getCourseId, courseId));
            if (exists != null && exists > 0) {
                continue;
            }
            EnrollCompensationEntity e = new EnrollCompensationEntity();
            e.setOrderId(orderId);
            e.setCourseId(courseId);
            e.setUserId(userId);
            e.setAttempts(0);
            e.setStatus("PENDING");
            e.setNextRetryAt(LocalDateTime.now());
            LocalDateTime now = LocalDateTime.now();
            e.setCreateTime(now);
            e.setUpdateTime(now);
            mapper.insert(e);
        }
    }

    /** 同步执行（事务提交后由 Controller 调用）——M15.22：统一经 DB 原子认领（防同步/定时/人工竞争） */
    public void executeNow(Long userId, Long orderId) {
        for (EnrollCompensationEntity e : pendingOf(orderId)) {
            if (tryClaim(e)) {
                doEnroll(e);
            }
        }
    }

    /** DB 原子认领：仅 PENDING→RUNNING；认领失败=已被他人处理（同步/定时/人工互斥） */
    private boolean tryClaim(EnrollCompensationEntity e) {
        int claimed = mapper.update(null, Wrappers.<EnrollCompensationEntity>lambdaUpdate()
                .set(EnrollCompensationEntity::getStatus, "RUNNING")
                .set(EnrollCompensationEntity::getUpdateTime, LocalDateTime.now())
                .eq(EnrollCompensationEntity::getId, e.getId())
                .eq(EnrollCompensationEntity::getStatus, "PENDING"));
        return claimed == 1;
    }

    /**
     * 定时自动重试（M15.21：DB 原子抢占——UPDATE 条件认领防同步/定时/人工并发覆盖；
     * 成功状态不倒退；3 次后 FAILED_MANUAL）。
     */
    /** M15.22：RUNNING 租约超时回收（进程中断后 10 分钟可被重新认领） */
    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void recoverStaleRunning() {
        int recovered = mapper.update(null, Wrappers.<EnrollCompensationEntity>lambdaUpdate()
                .set(EnrollCompensationEntity::getStatus, "PENDING")
                .set(EnrollCompensationEntity::getUpdateTime, LocalDateTime.now())
                .eq(EnrollCompensationEntity::getStatus, "RUNNING")
                .le(EnrollCompensationEntity::getUpdateTime, LocalDateTime.now().minusMinutes(10)));
        if (recovered > 0) {
            log.info("[compensation] 回收 RUNNING 超时任务 {} 条（进程中断恢复）", recovered);
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void scheduledRetry() {
        List<EnrollCompensationEntity> due = mapper.selectList(Wrappers
                .<EnrollCompensationEntity>lambdaQuery()
                .eq(EnrollCompensationEntity::getStatus, "PENDING")
                .le(EnrollCompensationEntity::getNextRetryAt, LocalDateTime.now())
                .lt(EnrollCompensationEntity::getAttempts, 3)
                .last("LIMIT 50"));
        for (EnrollCompensationEntity e : due) {
            if (tryClaim(e)) {
                e.setStatus("RUNNING");
                doEnroll(e);
            }
        }
    }


    /** 人工重试（FAILED_MANUAL/PENDING 立即执行；幂等） */
    public void manualRetry(Long userId, Long orderId, boolean admin) {
        List<EnrollCompensationEntity> all = mapper.selectList(Wrappers
                .<EnrollCompensationEntity>lambdaQuery()
                .eq(EnrollCompensationEntity::getOrderId, orderId));
        if (all.isEmpty()) {
            throw new BizException("该订单无待补偿开课任务");
        }
        if (!admin && all.stream().anyMatch(p -> !p.getUserId().equals(userId))) {
            throw new BizException("无权操作该订单的补偿任务");
        }
        for (EnrollCompensationEntity e : all) {
            // SUCCESS 跳过；RUNNING（有效任务）不覆盖——等待租约超时回收
            if ("SUCCESS".equals(e.getStatus()) || "RUNNING".equals(e.getStatus())) {
                continue;
            }
            // M15.21：人工重试重置次数（新一轮 3 次自动退避）
            e.setAttempts(0);
            e.setStatus("PENDING");
            e.setNextRetryAt(LocalDateTime.now());
            e.setUpdateTime(LocalDateTime.now());
            mapper.updateById(e);
            if (tryClaim(e)) {
                e.setStatus("RUNNING");
                doEnroll(e);
            }
        }
    }

    /** 状态查询（订单维度） */
    public List<EnrollCompensationEntity> statusOf(Long orderId) {
        return mapper.selectList(Wrappers.<EnrollCompensationEntity>lambdaQuery()
                .eq(EnrollCompensationEntity::getOrderId, orderId)
                .orderByAsc(EnrollCompensationEntity::getId));
    }

    private List<EnrollCompensationEntity> pendingOf(Long orderId) {
        return mapper.selectList(Wrappers.<EnrollCompensationEntity>lambdaQuery()
                .eq(EnrollCompensationEntity::getOrderId, orderId)
                .eq(EnrollCompensationEntity::getStatus, "PENDING"));
    }

    /** 单次开课尝试（RUNNING 认领后执行；成功 SUCCESS/失败退避 PENDING；3 次 FAILED_MANUAL；SUCCESS 不倒退） */
    private void doEnroll(EnrollCompensationEntity e) {
        if ("SUCCESS".equals(e.getStatus())) {
            return; // 成功状态不倒退
        }
        e.setAttempts(e.getAttempts() == null ? 1 : e.getAttempts() + 1);
        try {
            var r = learningClientProvider.getObject().enrollForPaid(java.util.Map.of(
                    "userId", String.valueOf(e.getUserId()),
                    "courseId", String.valueOf(e.getCourseId())));
            boolean ok = r != null && Integer.valueOf(200).equals(r.getCode());
            e.setStatus(ok ? "SUCCESS" : (e.getAttempts() >= 3 ? "FAILED_MANUAL" : "PENDING"));
            e.setLastError(ok ? null : "开课失败");
        } catch (Exception ex) {
            e.setStatus(e.getAttempts() >= 3 ? "FAILED_MANUAL" : "PENDING");
            e.setLastError(ex.getMessage() == null ? "网络异常" : ex.getMessage());
        }
        int backoffMinutes = (int) Math.pow(2, Math.min(e.getAttempts() - 1, 3));
        e.setNextRetryAt(LocalDateTime.now().plusMinutes(backoffMinutes));
        e.setUpdateTime(LocalDateTime.now());
        mapper.updateById(e);
        log.info("[compensation] 订单 {} 课程 {} 开课补偿尝试 {}: {}",
                e.getOrderId(), e.getCourseId(), e.getAttempts(), e.getStatus());
    }
}
