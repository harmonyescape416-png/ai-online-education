package com.kzai.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kzai.common.exception.BizException;
import com.kzai.user.entity.SysNotification;
import com.kzai.user.mapper.SysNotificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内通知（M11）。
 * <p>
 * 设计要点：
 * <ol>
 *   <li><b>归属唯一来源</b>：所有查询/变更方法都显式接收 {@code userId}（来自 JWT），
 *       并在 SQL 条件中限制 {@code recipient_id}，不信任任何客户端传入的收件人。</li>
 *   <li><b>事件幂等</b>：{@code UNIQUE(recipient_id, event_key)}，重复投递只命中同一行；
 *       MySQL 唯一键是唯一持久化保障（不用 Redis 临时 Key 代替）。</li>
 *   <li><b>已读幂等</b>：重复标记已读不覆盖 {@code read_time}（保留首次实际已读时间）。</li>
 *   <li><b>逻辑删除</b>：仅接收者维度置 {@code deleted=1}，不动任何业务数据；重复删除幂等返回成功。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final SysNotificationMapper notificationMapper;

    /**
     * 事件投递（内部接口调用，仅由服务端内部签名保护）。
     * <p>
     * 幂等：同一 {@code eventKey} 对同一收件人重复投递→命中唯一键冲突→按已存在处理，
     * 返回已有通知 ID（不新增、不报错），保证「重复投递只生成一条通知」。
     */
    @Transactional
    public long publish(String eventKey, long recipientId, String type, String title,
                        String content, String bizType, String bizId) {
        if (eventKey == null || eventKey.isBlank()) {
            throw new BizException("eventKey 不能为空");
        }
        if (recipientId <= 0) {
            throw new BizException("接收用户非法");
        }
        SysNotification existing = notificationMapper.selectOne(
                new LambdaQueryWrapper<SysNotification>()
                        .eq(SysNotification::getRecipientId, recipientId)
                        .eq(SysNotification::getEventKey, eventKey));
        if (existing != null) {
            log.debug("[notify] 事件已存在，幂等跳过 eventKey={} id={}", eventKey, existing.getId());
            return existing.getId();
        }
        LocalDateTime now = LocalDateTime.now();
        SysNotification n = new SysNotification();
        n.setRecipientId(recipientId);
        n.setEventKey(eventKey);
        n.setType(type);
        n.setTitle(truncate(title, 120, "通知"));
        n.setContent(truncate(content, 480, ""));
        n.setBizType(bizType == null ? "" : bizType);
        n.setBizId(bizId == null ? "" : bizId);
        n.setReadStatus(SysNotification.UNREAD);
        n.setDeleted(SysNotification.NOT_DELETED);
        n.setCreateTime(now);
        n.setUpdateTime(now);
        try {
            notificationMapper.insert(n);
            log.info("[notify] 通知创建 eventKey={} recipient={} type={} id={}",
                    eventKey, recipientId, type, n.getId());
            return n.getId();
        } catch (DuplicateKeyException ex) {
            // 并发下唯一键冲突：认定为同一事件已投递（幂等成功）
            SysNotification again = notificationMapper.selectOne(
                    new LambdaQueryWrapper<SysNotification>()
                            .eq(SysNotification::getRecipientId, recipientId)
                            .eq(SysNotification::getEventKey, eventKey));
            log.info("[notify] 并发重复投递命中唯一键，幂等处理 eventKey={}", eventKey);
            return again == null ? 0L : again.getId();
        }
    }

    /** 未读数量（仅当前用户、仅未删除） */
    public long unreadCount(long userId) {
        Long c = notificationMapper.selectCount(new LambdaQueryWrapper<SysNotification>()
                .eq(SysNotification::getRecipientId, userId)
                .eq(SysNotification::getDeleted, SysNotification.NOT_DELETED)
                .eq(SysNotification::getReadStatus, SysNotification.UNREAD));
        return c == null ? 0L : c;
    }

    /**
     * 通知列表：按创建时间倒序分页；支持类型与已读/未读筛选；仅本人且未删除。
     *
     * @param readStatus null=全部 0=未读 1=已读
     */
    public Map<String, Object> list(long userId, String type, Integer readStatus, long page, long size) {
        Page<SysNotification> p = notificationMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 50)),
                new LambdaQueryWrapper<SysNotification>()
                        .eq(SysNotification::getRecipientId, userId)
                        .eq(SysNotification::getDeleted, SysNotification.NOT_DELETED)
                        .eq(type != null && !type.isBlank(), SysNotification::getType, type)
                        .eq(readStatus != null, SysNotification::getReadStatus, readStatus)
                        .orderByDesc(SysNotification::getCreateTime));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", p.getTotal());
        out.put("page", p.getCurrent());
        out.put("size", p.getSize());
        out.put("records", p.getRecords().stream().map(this::vo).toList());
        return out;
    }

    /**
     * 通知详情：仅本人可访问；首次打开自动标记已读。
     * <p>
     * 若业务对象已删除/不可访问，此处<b>不返回业务正文</b>（通知只含摘要），
     * 由前端跳转后以业务接口权限为准，通知存在不代表拥有业务内容权限。
     */
    @Transactional
    public Map<String, Object> detail(long userId, long id) {
        SysNotification n = requireOwn(userId, id);
        if (n.getReadStatus() == null || n.getReadStatus() == SysNotification.UNREAD) {
            markReadInternal(userId, id);
            n.setReadStatus(SysNotification.READ);
            n.setReadTime(LocalDateTime.now());
        }
        return vo(n);
    }

    /** 单条标记已读（幂等：已读时不覆盖 read_time） */
    @Transactional
    public boolean markRead(long userId, long id) {
        requireOwn(userId, id);
        return markReadInternal(userId, id) > 0;
    }

    /**
     * 全部标记已读：仅影响当前用户的未读且未删除通知。
     * <p>
     * 条件带 {@code read_status=0}，并发新增的未读通知即使在本语句之后插入也<b>不会</b>
     * 被本次操作误改（条件不命中）；返回实际更新条数。
     */
    @Transactional
    public long markAllRead(long userId) {
        return notificationMapper.update(null, new LambdaUpdateWrapper<SysNotification>()
                .eq(SysNotification::getRecipientId, userId)
                .eq(SysNotification::getDeleted, SysNotification.NOT_DELETED)
                .eq(SysNotification::getReadStatus, SysNotification.UNREAD)
                .set(SysNotification::getReadStatus, SysNotification.READ)
                .set(SysNotification::getReadTime, LocalDateTime.now())
                .set(SysNotification::getUpdateTime, LocalDateTime.now()));
    }

    /** 删除（接收者维度逻辑删除；重复删除幂等成功） */
    @Transactional
    public boolean delete(long userId, long id) {
        requireOwn(userId, id);
        int rows = notificationMapper.update(null, new LambdaUpdateWrapper<SysNotification>()
                .eq(SysNotification::getId, id)
                .eq(SysNotification::getRecipientId, userId)
                .set(SysNotification::getDeleted, SysNotification.DELETED)
                .set(SysNotification::getUpdateTime, LocalDateTime.now()));
        return rows >= 0; // 已删除时 rows=0，按幂等处理（不报错）
    }

    /**
     * 用户注销/删除时清理其通知（M11：避免新增通知表产生未处理的用户关联残留）。
     * 与用户删除同库事务内执行；通知本身不含业务正文，直接物理删除不留悬挂。
     */
    @Transactional
    public int deleteByUser(long userId) {
        return notificationMapper.delete(new LambdaQueryWrapper<SysNotification>()
                .eq(SysNotification::getRecipientId, userId));
    }

    /** 取本人通知（不存在/不属于本人→受控 404） */
    private SysNotification requireOwn(long userId, long id) {
        SysNotification n = notificationMapper.selectOne(new LambdaQueryWrapper<SysNotification>()
                .eq(SysNotification::getId, id)
                .eq(SysNotification::getRecipientId, userId)
                .eq(SysNotification::getDeleted, SysNotification.NOT_DELETED));
        if (n == null) {
            // 受控 404：越权与不存在同码，不泄露他人通知是否存在
            throw new BizException(404, "通知不存在或无权访问");
        }
        return n;
    }

    /** 内部：仅更新未读→已读（已读不覆盖 read_time，保证幂等语义） */
    private int markReadInternal(long userId, long id) {
        return notificationMapper.update(null, new LambdaUpdateWrapper<SysNotification>()
                .eq(SysNotification::getId, id)
                .eq(SysNotification::getRecipientId, userId)
                .eq(SysNotification::getReadStatus, SysNotification.UNREAD)
                .set(SysNotification::getReadStatus, SysNotification.READ)
                .set(SysNotification::getReadTime, LocalDateTime.now())
                .set(SysNotification::getUpdateTime, LocalDateTime.now()));
    }

    /** 视图：Long ID 按字符串传输（避免前端精度丢失） */
    private Map<String, Object> vo(SysNotification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId() == null ? null : String.valueOf(n.getId()));
        m.put("type", n.getType());
        m.put("title", n.getTitle());
        m.put("content", n.getContent());
        m.put("bizType", n.getBizType());
        m.put("bizId", n.getBizId() == null ? null : String.valueOf(n.getBizId()));
        m.put("readStatus", n.getReadStatus());
        m.put("readTime", n.getReadTime() == null ? null : n.getReadTime().toString());
        m.put("createTime", n.getCreateTime() == null ? null : n.getCreateTime().toString());
        return m;
    }

    private static String truncate(String s, int max, String fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 可选类型白名单（供参数校验与前端筛选一致性） */
    public static final List<String> TYPES = List.of(
            SysNotification.TYPE_AI_REPLY,
            SysNotification.TYPE_QUESTION_REPLY,
            SysNotification.TYPE_COURSE_APPROVED);
}
