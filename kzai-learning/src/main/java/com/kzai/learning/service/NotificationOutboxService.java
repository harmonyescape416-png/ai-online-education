package com.kzai.learning.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kzai.learning.entity.SysEventOutbox;
import com.kzai.learning.mapper.SysEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 通知事件入队（M11）：与业务<b>同库同事务</b>调用。
 * <p>
 * 只能在业务事务内部调用（无 @Transactional(REQUIRES_NEW)），因此：
 * 业务回滚 → 本行不存在 → 不会有通知；业务提交 → 本行持久存在 → Worker 一定看得到。
 * <p>
 * eventKey 由业务方按真实业务对象生成（如 {@code AI_REPLY:replyId}、
 * {@code QUESTION_REPLY:replyId}），同一业务对象重复触发只入队一次（唯一键冲突即幂等）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationOutboxService {

    private final SysEventOutboxMapper outboxMapper;

    /**
     * 入队（业务事务内调用）。
     *
     * @return 入队行 ID；已存在/重复触发返回已有行 ID（幂等）
     */
    public long enqueue(String eventKey, String bizType, String bizId, long recipientId,
                        String type, String title, String content, String payloadJson) {
        if (eventKey == null || eventKey.isBlank() || recipientId <= 0) {
            log.warn("[notify.outbox] 入队参数非法 eventKey={} recipient={}", eventKey, recipientId);
            return 0L;
        }
        SysEventOutbox existing = outboxMapper.selectOne(
                new LambdaQueryWrapper<SysEventOutbox>().eq(SysEventOutbox::getEventKey, eventKey));
        if (existing != null) {
            return existing.getId();
        }
        LocalDateTime now = LocalDateTime.now();
        SysEventOutbox row = new SysEventOutbox();
        row.setEventKey(eventKey);
        row.setBizType(bizType == null ? "" : bizType);
        row.setBizId(bizId == null ? "" : bizId);
        row.setRecipientId(recipientId);
        row.setType(type);
        row.setTitle(title == null ? "" : title);
        row.setContent(content == null ? "" : content);
        row.setPayloadJson(payloadJson);
        row.setStatus(SysEventOutbox.STATUS_PENDING);
        row.setAttempt(0);
        row.setNextRetryTime(now);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        try {
            outboxMapper.insert(row);
            log.info("[notify.outbox] 入队 eventKey={} recipient={} type={} id={}",
                    eventKey, recipientId, type, row.getId());
            return row.getId();
        } catch (DuplicateKeyException ex) {
            SysEventOutbox again = outboxMapper.selectOne(
                    new LambdaQueryWrapper<SysEventOutbox>().eq(SysEventOutbox::getEventKey, eventKey));
            return again == null ? 0L : again.getId();
        }
    }
}
