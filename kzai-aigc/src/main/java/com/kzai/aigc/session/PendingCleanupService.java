package com.kzai.aigc.session;

import com.kzai.aigc.memory.ChatMemoryKeys;
import com.kzai.aigc.memory.RedisChatMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * 删除补偿（M6 要求 5.9：Redis 删除失败时保留可恢复的补偿机制）。
 * <p>
 * 流程：
 * <ol>
 *   <li>删除会话时先删 MySQL 元数据（会话不存在 → 后续任何人都无法通过 {@code requireOwned} 越权判定通过）；</li>
 *   <li>再清理 Redis（消息 List + 停止标识），最多重试 3 次；</li>
 *   <li>仍失败则写入补偿标记 {@code AI:PENDING_CLEAN:{sessionId}}（TTL 30 天）并记录日志；</li>
 *   <li>定时任务扫描补偿标记并重试，成功后清除标记。</li>
 * </ol>
 * 重要：即使 Redis 消息暂时残留，由于 MySQL 元数据已删除，任何用户（包括原所有者）
 * 都无法再访问该 sessionId 的消息；新会话使用新的 UUID，残留 Key 不会被复用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingCleanupService {

    private static final String PREFIX = "AI:PENDING_CLEAN:";
    private static final int MAX_RETRY = 3;
    private static final Duration PENDING_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redis;
    private final RedisChatMemory memory;

    /**
     * 清理 Redis 残留。返回 true 表示已清理干净；false 表示失败并已登记补偿。
     */
    public boolean cleanup(long userId, String sessionId) {
        for (int i = 1; i <= MAX_RETRY; i++) {
            try {
                memory.clear(ChatMemoryKeys.conversationId(userId, sessionId));
                redis.delete(ChatMemoryKeys.stopKey(sessionId));
                log.info("[aigc.cleanup] ok sessionId={} attempt={}", sessionId, i);
                return true;
            } catch (RuntimeException ex) {
                log.warn("[aigc.cleanup] failed sessionId={} attempt={} msg={}", sessionId, i, ex.getMessage());
                sleepQuietly(200L * i);
            }
        }
        try {
            redis.opsForValue().set(PREFIX + sessionId, String.valueOf(userId), PENDING_TTL);
            log.error("[aigc.cleanup] pending registered sessionId={}", sessionId);
        } catch (RuntimeException ex) {
            log.error("[aigc.cleanup] cannot register pending sessionId={} msg={}", sessionId, ex.getMessage());
        }
        return false;
    }

    /** 补偿重试：每 60 秒扫描一次待清理标记 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void retryPending() {
        Set<String> keys;
        try {
            keys = redis.keys(PREFIX + "*");
        } catch (RuntimeException ex) {
            log.warn("[aigc.cleanup] scan unavailable: {}", ex.getMessage());
            return;
        }
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (String key : keys) {
            String sessionId = key.substring(PREFIX.length());
            String uid = redis.opsForValue().get(key);
            if (uid == null) {
                continue;
            }
            try {
                memory.clear(ChatMemoryKeys.conversationId(Long.parseLong(uid), sessionId));
                redis.delete(ChatMemoryKeys.stopKey(sessionId));
                redis.delete(key);
                log.info("[aigc.cleanup] retried ok sessionId={}", sessionId);
            } catch (RuntimeException ex) {
                log.warn("[aigc.cleanup] retry failed sessionId={} msg={}", sessionId, ex.getMessage());
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
