package com.kzai.aigc.session;

import com.kzai.aigc.prompt.PromptManager;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.memory.ChatMemoryKeys;
import com.kzai.aigc.memory.RedisChatMemory;
import com.kzai.aigc.session.entity.ChatSession;
import com.kzai.aigc.session.mapper.ChatSessionMapper;
import com.kzai.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * AI 会话服务（M6）。MySQL 仅存元数据，消息正文在 Redis。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionMapper mapper;
    private final RedisChatMemory memory;
    private final PendingCleanupService pendingCleanup;
    private final AiProperties props;
    private final com.kzai.aigc.config.DynamicAiParams dynamic;
    private final com.kzai.aigc.prompt.PromptVersionService promptVersionService;
    private final PromptManager promptManager; // M13：历史上限热更新
    private final org.springframework.context.ApplicationEventPublisher events;
    public record Deleted(long userId, String sessionId) { }

    /** 新建会话：真实写入 MySQL，sessionId 为 UUID，初始标题留空。 */
    @Transactional
    public ChatSession create(long userId) {
        ChatSession s = new ChatSession();
        // M15.20-A：新会话绑定当前各类型最新发布版本（历史会话不受后续发布影响；存量 NULL 兼容）
        s.setPromptVersionMap(promptVersionService.snapshotAllTypes(promptManager::get));
        s.setSessionId(UUID.randomUUID().toString());
        s.setUserId(userId);
        s.setTitle(null);
        s.setCreateTime(LocalDateTime.now());
        s.setUpdateTime(LocalDateTime.now());
        s.setCreater(userId);
        s.setUpdater(userId);
        mapper.insert(s);
        return s;
    }

    /**
     * 异步更新标题：取首条用户问题前 N 字。
     * M7 修复竞态：条件更新（仅当 title 仍为 NULL/空串时生效）落到 SQL 层，
     * 避免「读取时为空 → 用户重命名 → 更新覆盖重命名」的读写窗口。
     * 调用方负责触发（如 ChatService 在流结束后异步调用）。
     */
    public void tryAutoTitle(long userId, String sessionId, String firstUserText) {
        if (firstUserText == null || firstUserText.isBlank()) {
            return;
        }
        ChatSession s = requireOwned(userId, sessionId);
        if (s.getTitle() != null && !s.getTitle().isBlank()) {
            return;
        }
        String title = firstUserText.length() > props.getTitleLength()
                ? firstUserText.substring(0, props.getTitleLength())
                : firstUserText;
        mapper.update(null, Wrappers.<ChatSession>lambdaUpdate()
                .set(ChatSession::getTitle, title)
                .set(ChatSession::getUpdateTime, LocalDateTime.now())
                .eq(ChatSession::getId, s.getId())
                // 竞态防护：重命名与自动标题并发时，数据库层面保证重命名不被覆盖
                .and(w -> w.isNull(ChatSession::getTitle).or().eq(ChatSession::getTitle, "")));
    }

    /** 重命名（用户主动）：仅能改自己的会话；非空校验。 */
    public void rename(long userId, String sessionId, String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new BizException("标题不能为空");
        }
        ChatSession s = requireOwned(userId, sessionId);
        String t = newTitle.length() > props.getTitleLength() ? newTitle.substring(0, props.getTitleLength()) : newTitle;
        mapper.update(null, Wrappers.<ChatSession>lambdaUpdate()
                .set(ChatSession::getTitle, t)
                .set(ChatSession::getUpdater, userId)
                .set(ChatSession::getUpdateTime, LocalDateTime.now())
                .eq(ChatSession::getId, s.getId()));
    }

    /**
     * 删除：先删 MySQL 元数据（断开可访问性），再删 Redis 消息/停止标识。
     * Redis 删除失败时由 {@link PendingCleanupService} 登记补偿并异步重试。
     */
    @Transactional
    public void delete(long userId, String sessionId) {
        ChatSession s = requireOwned(userId, sessionId);
        mapper.deleteById(s.getId());
        events.publishEvent(new Deleted(userId, sessionId));
        pendingCleanup.cleanup(userId, sessionId);
    }

    /**
     * 查询单个：必须属于当前用户。
     * <p>
     * 抛 AuthException(403) 而非 BizException，使 HTTP 状态码与业务码一致，
     * 与 M2–M5「越权 → 403」的约定保持一致；提示语不区分"不存在/无权"，避免会话 ID 探测。
     */
    public ChatSession requireOwned(long userId, String sessionId) {
        ChatSession s = mapper.selectOne(Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getSessionId, sessionId));
        if (s == null) {
            throw new com.kzai.common.exception.AuthException(
                    com.kzai.common.result.ResultCode.FORBIDDEN, "会话不存在或无权访问");
        }
        return s;
    }

    /** 历史列表：按更新时间倒序，上限取 M13 动态参数 chat.history-limit（热更新生效）。 */
    public List<ChatSession> history(long userId) {
        return mapper.selectList(Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getUserId, userId)
                .orderByDesc(ChatSession::getUpdateTime)
                .last("LIMIT " + dynamic.historyLimit()));
    }

    /** 当前用户会话总数（用于删除前关联校验 / 管理端统计） */
    public long countByUser(long userId) {
        Long count = mapper.selectCount(Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getUserId, userId));
        return count == null ? 0L : count;
    }
}
