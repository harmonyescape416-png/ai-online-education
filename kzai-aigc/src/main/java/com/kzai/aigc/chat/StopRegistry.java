package com.kzai.aigc.chat;

import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.memory.ChatMemoryKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 停止生成注册表（M6）。
 * <p>
 * 应用内：每个正在执行的生成任务持有一个 key（sessionId 维度），同一 sessionId 同时只允许一个任务。
 * Redis：AI:STOP:{sessionId} 存 "1" 表示「已请求停止」，TTL 5 分钟；生成循环每段都检查。
 * 防止「上一轮停止标识误伤下一轮生成」：成功完成 / 异常 / 主动取消时都会清掉该 Key。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StopRegistry {

    private final StringRedisTemplate redis;
    private final AiProperties props;

    /** 应用内活跃会话集合：仅用于单实例下「同一会话同时只允许一个任务」的限制 */
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    /** 试图登记一个正在进行的生成任务；同一 sessionId 已存在则返回 false */
    public boolean tryAcquire(String sessionId) {
        return active.add(sessionId);
    }

    public void release(String sessionId) {
        active.remove(sessionId);
    }

    public boolean isActive(String sessionId) {
        return active.contains(sessionId);
    }

    /** 标记停止：写入 Redis 5 分钟 TTL */
    public void markStop(String sessionId) {
        try {
            redis.opsForValue().set(ChatMemoryKeys.stopKey(sessionId), "1",
                    Duration.ofMinutes(props.getStopTtlMinutes()));
        } catch (RuntimeException ex) {
            log.warn("markStop 失败：{}", ex.getMessage());
        }
    }

    /** 检查是否被请求停止（生成循环每段都应调用） */
    public boolean isStopRequested(String sessionId) {
        try {
            return redis.hasKey(ChatMemoryKeys.stopKey(sessionId));
        } catch (RuntimeException ex) {
            log.warn("isStopRequested 失败：{}", ex.getMessage());
            return false;
        }
    }

    /** 清理停止标识（成功完成 / 正常失败 / 取消时调用） */
    public void clearStop(String sessionId) {
        try {
            redis.delete(ChatMemoryKeys.stopKey(sessionId));
        } catch (RuntimeException ex) {
            log.warn("clearStop 失败：{}", ex.getMessage());
        }
    }
}
