package com.kzai.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kzai.common.exception.BizException;
import com.kzai.user.entity.SysNotification;
import com.kzai.user.mapper.SysNotificationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通知服务测试（M11）：事件幂等、未读统计、已读幂等、逻辑删除、跨用户隔离、用户删除清理。
 * 使用 H2（schema-test.sql 已镜像 sys_notification 结构与唯一约束）。
 */
@SpringBootTest(properties = {"spring.cloud.nacos.discovery.enabled=false"})
class NotificationServiceTest {

    @Autowired
    private NotificationService notificationService;
    @Autowired
    private SysNotificationMapper notificationMapper;

    private static final long USER_A = 900001L;
    private static final long USER_B = 900002L;

    @BeforeEach
    void clean() {
        notificationMapper.delete(new LambdaQueryWrapper<SysNotification>()
                .in(SysNotification::getRecipientId, USER_A, USER_B));
    }

    @Test
    void publish_sameEventKeyOnlyOnce() {
        long id1 = notificationService.publish("AI_REPLY:1", USER_A, "AI_REPLY",
                "标题", "内容", "QA", "1");
        long id2 = notificationService.publish("AI_REPLY:1", USER_A, "AI_REPLY",
                "标题", "内容", "QA", "1");
        assertEquals(id1, id2, "同一 eventKey 重复投递必须幂等（唯一约束保障）");
        assertEquals(1, count(USER_A));
    }

    @Test
    void publish_differentEventsCreateSeparateNotifications() {
        notificationService.publish("AI_REPLY:1", USER_A, "AI_REPLY", "a", "a", "QA", "1");
        notificationService.publish("QUESTION_REPLY:2", USER_A, "QUESTION_REPLY", "b", "b", "QA", "1");
        assertEquals(2, count(USER_A));
    }

    @Test
    void unreadCount_matchesDb() {
        notificationService.publish("E:1", USER_A, "AI_REPLY", "a", "a", "QA", "1");
        notificationService.publish("E:2", USER_A, "AI_REPLY", "b", "b", "QA", "1");
        assertEquals(2, notificationService.unreadCount(USER_A));
        notificationService.markAllRead(USER_A);
        assertEquals(0, notificationService.unreadCount(USER_A));
        assertEquals(0, notificationService.unreadCount(USER_B), "他人未读数互不影响");
    }

    @Test
    void detail_autoRead_and_readTimeNotOverwritten() {
        long id = notificationService.publish("E:10", USER_A, "AI_REPLY", "t", "c", "QA", "1");
        Map<String, Object> d = notificationService.detail(USER_A, id);
        assertEquals(1, d.get("readStatus"));
        SysNotification first = notificationMapper.selectById(id);
        assertTrue(first.getReadTime() != null);

        notificationService.markRead(USER_A, id);
        notificationService.detail(USER_A, id);
        SysNotification again = notificationMapper.selectById(id);
        assertEquals(first.getReadTime(), again.getReadTime(),
                "重复已读不得覆盖首次 read_time（幂等语义）");
    }

    @Test
    void markAllRead_onlyAffectsUnreadAndNotDeleted() {
        long read = notificationService.publish("E:20", USER_A, "AI_REPLY", "a", "a", "QA", "1");
        notificationService.markRead(USER_A, read);
        long unread = notificationService.publish("E:21", USER_A, "AI_REPLY", "b", "b", "QA", "1");
        long after = notificationService.publish("E:22", USER_A, "AI_REPLY", "c", "c", "QA", "1");

        notificationService.markAllRead(USER_A);
        assertEquals(1, notificationMapper.selectById(unread).getReadStatus());
        // 全部已读之后新增的通知仍为未读（条件带 read_status=0，不会被误改）
        // 全部已读之后再新增的通知仍为未读（update 条件带 read_status=0，不会误改新通知）
        notificationService.publish("E:23", USER_A, "AI_REPLY", "d", "d", "QA", "1");
        assertEquals(1, countUnread(USER_A), "全部已读后新增的通知应仍为未读");
        assertEquals(1, notificationMapper.selectById(after).getReadStatus());
    }

    @Test
    void delete_isIdempotentAndHiddenFromList() {
        long id = notificationService.publish("E:30", USER_A, "AI_REPLY", "a", "a", "QA", "1");
        assertTrue(notificationService.delete(USER_A, id));
        assertEquals(1, notificationMapper.selectById(id).getDeleted());
        assertEquals(0L, ((Number) notificationService.list(USER_A, null, null, 1, 10).get("total")).longValue());
        // 重复删除：受控 404（不报 500）
        BizException ex = assertThrows(BizException.class, () -> notificationService.delete(USER_A, id));
        assertEquals(404, ex.getCode());
    }

    @Test
    void crossUser_isolation() {
        long idA = notificationService.publish("E:40", USER_A, "AI_REPLY", "a", "a", "QA", "1");
        BizException ex1 = assertThrows(BizException.class, () -> notificationService.detail(USER_B, idA));
        assertEquals(404, ex1.getCode(), "他人通知以受控 404 拒绝，不泄露存在性");
        assertThrows(BizException.class, () -> notificationService.markRead(USER_B, idA));
        assertThrows(BizException.class, () -> notificationService.delete(USER_B, idA));
        assertEquals(0, notificationMapper.selectById(idA).getDeleted(), "他人操作不得改动本人通知");
        assertEquals(1, count(USER_A));
    }

    @Test
    void deleteByUser_clearsNotifications() {
        notificationService.publish("E:50", USER_A, "AI_REPLY", "a", "a", "QA", "1");
        notificationService.publish("E:51", USER_B, "AI_REPLY", "b", "b", "QA", "1");
        int removed = notificationService.deleteByUser(USER_A);
        assertEquals(1, removed);
        assertEquals(0, count(USER_A), "用户删除时其通知一并清理（无用户关联残留）");
        assertEquals(1, count(USER_B), "不得误删其他用户通知");
    }

    private long count(long userId) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<SysNotification>()
                .eq(SysNotification::getRecipientId, userId));
    }

    private long countUnread(long userId) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<SysNotification>()
                .eq(SysNotification::getRecipientId, userId)
                .eq(SysNotification::getDeleted, SysNotification.NOT_DELETED)
                .eq(SysNotification::getReadStatus, SysNotification.UNREAD));
    }

    @Test
    void list_sortedAndFilterable() {
        long older = notificationService.publish("E:60", USER_A, "AI_REPLY", "a", "a", "QA", "1");
        SysNotification o = notificationMapper.selectById(older);
        o.setCreateTime(LocalDateTime.now().minusDays(1));
        notificationMapper.updateById(o);
        notificationService.publish("E:61", USER_A, "QUESTION_REPLY", "b", "b", "QA", "1");

        Map<String, Object> all = notificationService.list(USER_A, null, null, 1, 10);
        assertEquals(2L, ((Number) all.get("total")).longValue());
        Map<String, Object> onlyAi = notificationService.list(USER_A, "AI_REPLY", null, 1, 10);
        assertEquals(1L, ((Number) onlyAi.get("total")).longValue());
        Map<String, Object> onlyRead = notificationService.list(USER_A, null, 1, 1, 10);
        assertEquals(0L, ((Number) onlyRead.get("total")).longValue());
    }
}
