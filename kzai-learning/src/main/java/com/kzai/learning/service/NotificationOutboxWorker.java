package com.kzai.learning.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kzai.common.result.R;
import com.kzai.learning.client.UserNotificationClient;
import com.kzai.learning.entity.SysEventOutbox;
import com.kzai.learning.mapper.SysEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知事件投递 Worker（M11）：本地 Outbox → user 服务内部通知接口。
 * <p>
 * 领取（原子条件更新 + 租约）：
 * <pre>
 * UPDATE ... SET status=SENDING, worker_id=?, lease_until=now+5m, attempt=attempt+1
 * WHERE id=? AND (status=PENDING AND next_retry_time&lt;=now
 *              OR status=SENDING AND lease_until&lt;now)
 * </pre>
 * 影响 1 行才算领取成功；Feign 超时重试<b>复用同一 eventKey</b>，user 侧唯一约束去重。
 * 失败保留行 + 指数退避（≤5 次），超限置 FAILED 并记录最近错误；
 * 服务重启后 PENDING/租约过期的 SENDING 自动恢复投递（持久化保障，非内存/Redis）。
 * <p>
 * 强约束：通知投递失败<b>绝不</b>回滚业务数据，也不会重复生成 AI 回复/教师回复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationOutboxWorker {

    private final SysEventOutboxMapper outboxMapper;
    private final UserNotificationClient notificationClient;

    private final String workerId = initWorkerId();

    @Scheduled(fixedDelay = 3000, initialDelay = 8000)
    public void poll() {
        LocalDateTime now = LocalDateTime.now();
        List<SysEventOutbox> candidates = outboxMapper.selectList(
                new LambdaQueryWrapper<SysEventOutbox>()
                        .and(w -> w
                                .and(w2 -> w2.eq(SysEventOutbox::getStatus, SysEventOutbox.STATUS_PENDING)
                                        .le(SysEventOutbox::getNextRetryTime, now))
                                .or(w2 -> w2.eq(SysEventOutbox::getStatus, SysEventOutbox.STATUS_SENDING)
                                        .isNotNull(SysEventOutbox::getLeaseUntil)
                                        .lt(SysEventOutbox::getLeaseUntil, now)))
                        .orderByAsc(SysEventOutbox::getCreateTime)
                        .last("LIMIT 5"));
        for (SysEventOutbox row : candidates) {
            if (claim(row, now)) {
                deliver(row.getId(), now);
            }
        }
    }

    /** 原子领取：影响 1 行才算成功（防并发重复投递） */
    private boolean claim(SysEventOutbox row, LocalDateTime now) {
        SysEventOutbox upd = new SysEventOutbox();
        upd.setStatus(SysEventOutbox.STATUS_SENDING);
        upd.setWorkerId(workerId);
        upd.setLeaseUntil(now.plusMinutes(SysEventOutbox.LEASE_MINUTES));
        upd.setAttempt((row.getAttempt() == null ? 0 : row.getAttempt()) + 1);
        upd.setUpdateTime(now);
        boolean claimed;
        if (row.getStatus() == SysEventOutbox.STATUS_PENDING) {
            claimed = outboxMapper.update(upd, new LambdaUpdateWrapper<SysEventOutbox>()
                    .eq(SysEventOutbox::getId, row.getId())
                    .eq(SysEventOutbox::getStatus, SysEventOutbox.STATUS_PENDING)
                    .le(SysEventOutbox::getNextRetryTime, now)) == 1;
        } else {
            claimed = outboxMapper.update(upd, new LambdaUpdateWrapper<SysEventOutbox>()
                    .eq(SysEventOutbox::getId, row.getId())
                    .eq(SysEventOutbox::getStatus, SysEventOutbox.STATUS_SENDING)
                    .lt(SysEventOutbox::getLeaseUntil, now)) == 1;
        }
        return claimed;
    }

    private void deliver(long id, LocalDateTime now) {
        SysEventOutbox row = outboxMapper.selectById(id);
        if (row == null) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventKey", row.getEventKey());
        body.put("recipientId", String.valueOf(row.getRecipientId()));
        body.put("type", row.getType());
        body.put("title", row.getTitle());
        body.put("content", row.getContent());
        body.put("bizType", row.getBizType());
        body.put("bizId", row.getBizId());
        body.put("payload", row.getPayloadJson());
        body.put("requestId", workerId + ":" + id);
        try {
            R<Map<String, Object>> r = notificationClient.publish(body);
            boolean ok = r != null && r.isSuccess();
            if (!ok) {
                throw new IllegalStateException("通知服务返回失败 " + (r == null ? "null" : r.getMsg()));
            }
            outboxMapper.update(null, new LambdaUpdateWrapper<SysEventOutbox>()
                    .eq(SysEventOutbox::getId, id)
                    .set(SysEventOutbox::getStatus, SysEventOutbox.STATUS_SUCCESS)
                    .set(SysEventOutbox::getWorkerId, workerId)
                    .set(SysEventOutbox::getError, (Object) null)
                    .set(SysEventOutbox::getUpdateTime, LocalDateTime.now()));
            log.info("[notify.outbox] 投递成功 id={} eventKey={} recipient={}",
                    id, row.getEventKey(), row.getRecipientId());
        } catch (RuntimeException ex) {
            int attempt = row.getAttempt() == null ? 1 : row.getAttempt();
            boolean exhausted = attempt >= SysEventOutbox.MAX_ATTEMPT;
            long backoffSeconds = Math.min(60L * (1L << Math.min(attempt, 4)), 600L);
            LocalDateTime next = now.plusSeconds(backoffSeconds);
            outboxMapper.update(null, new LambdaUpdateWrapper<SysEventOutbox>()
                    .eq(SysEventOutbox::getId, id)
                    .set(SysEventOutbox::getStatus,
                            exhausted ? SysEventOutbox.STATUS_FAILED : SysEventOutbox.STATUS_PENDING)
                    .set(SysEventOutbox::getNextRetryTime, next)
                    .set(SysEventOutbox::getLeaseUntil, (Object) null)
                    .set(SysEventOutbox::getWorkerId, (Object) null)
                    .set(SysEventOutbox::getError, truncate(String.valueOf(ex.getMessage()), 480))
                    .set(SysEventOutbox::getUpdateTime, LocalDateTime.now()));
            log.warn("[notify.outbox] 投递失败 id={} attempt={} exhausted={} next={} err={}",
                    id, attempt, exhausted, next, ex.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max));
    }

    private static String initWorkerId() {
        try {
            return "learning-" + InetAddress.getLocalHost().getHostName() + "-"
                    + ProcessHandle.current().pid();
        } catch (Exception ex) {
            return "learning-" + ProcessHandle.current().pid();
        }
    }
}
