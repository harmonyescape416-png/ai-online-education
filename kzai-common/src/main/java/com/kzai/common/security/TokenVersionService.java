package com.kzai.common.security;

import com.kzai.common.constant.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 用户级 token 版本号（扩展设计 E1 + M2 遗留修复②）。
 * <p>
 * 场景：账号禁用、管理员重置密码、用户自助改密后，需要让该用户**全部**已签发 token 立即失效，
 * 而按 jti 的黑名单无法覆盖此前签发的所有 token，故引入版本号。
 * <p>
 * 遗留修复②：版本号以 {@link TokenVersionStore}（MySQL）为权威来源，Redis 仅作缓存：
 * 读缓存 → 未命中回源 DB → 回写缓存；bump 时先自增 DB 再失效缓存。
 * DB 不可用时必须让调用方 fail-closed（不得当作"版本 0 未撤销"放行）。
 */
@Slf4j
@Component
public class TokenVersionService {

    /** 缓存保留时长：覆盖最长 token 有效期即可 */
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectProvider<TokenVersionStore> storeProvider;

    public TokenVersionService(StringRedisTemplate redisTemplate,
                               ObjectProvider<TokenVersionStore> storeProvider) {
        this.redisTemplate = redisTemplate;
        this.storeProvider = storeProvider;
    }

    /** 当前版本号，无记录时为 0；存储不可用时抛异常（调用方按失效处理） */
    public int current(Long userId) {
        if (userId == null) {
            return 0;
        }
        Integer cached = readCache(userId);
        if (cached != null) {
            return cached;
        }
        int version = store().current(userId);
        writeCache(userId, version);
        return version;
    }

    /** 版本号自增，使该用户全部旧 token 失效 */
    public int bump(Long userId) {
        if (userId == null) {
            return 0;
        }
        int next = store().bump(userId);
        // 缓存失效而非覆盖：确保下一次读取一定回源到权威存储
        invalidateCache(userId);
        return next;
    }

    /** token 携带的版本号早于当前版本号即判失效；存储不可用按失效处理（fail-closed） */
    public boolean isStale(Long userId, int tokenVersion) {
        try {
            return tokenVersion < current(userId);
        } catch (Exception e) {
            log.warn("[token-version] check unavailable, treat as stale: {}", e.getMessage());
            return true;
        }
    }

    private Integer readCache(Long userId) {
        try {
            String value = redisTemplate.opsForValue().get(Constants.REDIS_KEY_TOKEN_VERSION + userId);
            if (value == null || value.isBlank()) {
                return null;
            }
            return Integer.parseInt(value);
        } catch (Exception e) {
            // Redis 不可用不视为致命：权威数据在 MySQL，回源即可
            log.warn("[token-version] cache read failed, fallback to store: {}", e.getMessage());
            return null;
        }
    }

    private void writeCache(Long userId, int version) {
        try {
            redisTemplate.opsForValue().set(
                    Constants.REDIS_KEY_TOKEN_VERSION + userId, String.valueOf(version), TTL);
        } catch (Exception e) {
            log.warn("[token-version] cache write failed: {}", e.getMessage());
        }
    }

    private void invalidateCache(Long userId) {
        try {
            redisTemplate.delete(Constants.REDIS_KEY_TOKEN_VERSION + userId);
        } catch (Exception e) {
            log.warn("[token-version] cache invalidate failed: {}", e.getMessage());
        }
    }

    private TokenVersionStore store() {
        TokenVersionStore store = storeProvider.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("未配置 TokenVersionStore，无法确认 token 撤销状态");
        }
        return store;
    }
}
