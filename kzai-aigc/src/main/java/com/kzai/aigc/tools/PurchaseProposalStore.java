package com.kzai.aigc.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 购买确认提案存储（M7，Redis）。
 * <p>
 * 写操作（预下单）必须有用户对<b>具体课程</b>的显式确认才能执行：
 * <ol>
 *   <li>AI 首次收到购买意图 → OrderTools 查询真实课程 → 生成「待确认提案」
 *       （绑定 userId + sessionId + courseIds + 稳定 requestId，TTL 10 分钟）；</li>
 *   <li>用户在<b>同一会话</b>下一条消息明确确认 → ChatService 调用 {@link #markConfirmed}；</li>
 *   <li>OrderTools 执行预下单前校验提案 confirmed 且包含目标课程；下单成功即 consume，
 *       防止同一确认被复用产生第二笔订单。</li>
 * </ol>
 * 跨会话/跨用户不可复用（key 绑定 userId+sessionId）；过期自动失效（TTL）。
 * <p>
 * Redis 不可用时读写均降级：get 视为「无提案」（工具拒绝下单，<b>fail-closed</b>），
 * 绝不在无确认凭据的情况下放行写操作。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseProposalStore {

    private static final String KEY_PREFIX = "AI:PROPOSAL:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AiProperties props;

    public static class Proposal {
        public String proposalId;
        /** 稳定幂等号：由 proposalId 派生，确认过期前的重试始终复用同一 requestId */
        public String requestId;
        public List<String> courseIds;
        public boolean confirmed;
        public boolean consumed;
        public long createdAt;

        public boolean contains(String courseId) {
            return courseIds != null && courseId != null && courseIds.contains(courseId);
        }
    }

    private String key(long userId, String sessionId) {
        return KEY_PREFIX + userId + "_" + sessionId;
    }

    /** 读取当前提案；无提案 / 已消费 / Redis 不可用 → null */
    public Proposal get(long userId, String sessionId) {
        try {
            String json = redis.opsForValue().get(key(userId, sessionId));
            if (json == null || json.isBlank()) {
                return null;
            }
            Proposal p = objectMapper.readValue(json, Proposal.class);
            return (p == null || p.consumed) ? null : p;
        } catch (RuntimeException ex) {
            log.warn("购买提案读取降级（按无提案处理，fail-closed）: {}", ex.getMessage());
            return null;
        } catch (JsonProcessingException ex) {
            log.warn("购买提案解析失败（按无提案处理）: {}", ex.getMessage());
            return null;
        }
    }

    /** 生成「待确认」提案；覆盖旧提案（同一会话同一时刻只有一个有效提案） */
    public Proposal storePending(long userId, String sessionId, List<String> courseIds) {
        Proposal p = new Proposal();
        p.proposalId = UUID.randomUUID().toString().replace("-", "");
        // 稳定 requestId：同一提案重试（含超时重试）复用同一幂等号，绝不另起新号
        p.requestId = "ai-" + p.proposalId;
        p.courseIds = courseIds;
        p.confirmed = false;
        p.consumed = false;
        p.createdAt = System.currentTimeMillis();
        save(userId, sessionId, p);
        return p;
    }

    /** 用户明确确认后调用；只确认「当前已存在的提案」，不存在时返回 false */
    public boolean markConfirmed(long userId, String sessionId) {
        Proposal p = get(userId, sessionId);
        if (p == null) {
            return false;
        }
        p.confirmed = true;
        save(userId, sessionId, p);
        return true;
    }

    /** 下单成功（含幂等命中）后消费提案，防止确认被复用 */
    public void consume(long userId, String sessionId) {
        try {
            redis.delete(key(userId, sessionId));
        } catch (RuntimeException ex) {
            log.warn("购买提案消费失败: {}", ex.getMessage());
        }
    }

    private void save(long userId, String sessionId, Proposal p) {
        try {
            redis.opsForValue().set(key(userId, sessionId),
                    objectMapper.writeValueAsString(p),
                    Duration.ofMinutes(Math.max(1, props.getProposalTtlMinutes())));
        } catch (RuntimeException ex) {
            // 保存失败：下一次 get 拿不到提案 → 工具拒绝下单（fail-closed）
            log.warn("购买提案保存失败（写操作将被拒绝）: {}", ex.getMessage());
        } catch (JsonProcessingException ex) {
            log.warn("购买提案序列化失败: {}", ex.getMessage());
        }
    }
}
