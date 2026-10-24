package com.kzai.course.notify;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kzai.course.entity.SysEventOutbox;
import com.kzai.course.mapper.SysEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 通知事件入队（M11·course）：与课程审核业务<b>同库同事务</b>调用。
 * <p>
 * eventKey 以真实审核记录为依据（{@code COURSE_APPROVED:auditId}），
 * 因此「重新提交审核并再次通过」会形成新事件；仅凭课程当前状态不会产生通知。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationOutboxService {

    private final SysEventOutboxMapper outboxMapper;

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
