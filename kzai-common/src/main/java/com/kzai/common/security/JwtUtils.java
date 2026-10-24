package com.kzai.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 签发与解析（系统设计说明书 5.5.3 JwtUtils、6.1 身份认证设计）。
 * 使用 HS256；密钥长度需 >= 32 字节。
 */
public final class JwtUtils {

    private JwtUtils() {
    }

    public static SecretKey key(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 token。
     *
     * @param secret        密钥
     * @param expireSeconds 过期秒数
     * @param userId        用户ID
     * @param username      登录账号
     * @param role          用户类型（1员工，2学员，3老师）
     */
    public static String generate(String secret, long expireSeconds,
                                  Long userId, String username, Integer role) {
        return generate(secret, expireSeconds, userId, username, role, null, 0);
    }

    /**
     * 签发 token（M2：携带权限角色与用户级 token 版本号）。
     *
     * @param roles 权限角色编码列表，可为 null
     * @param ver   用户级 token 版本号，与 Redis AUTH:TOKEN:VER:{userId} 比对实现批量失效
     */
    public static String generate(String secret, long expireSeconds,
                                  Long userId, String username, Integer role,
                                  List<String> roles, int ver) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + expireSeconds * 1000);
        return Jwts.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .claim("roles", roles == null ? List.of() : roles)
                .claim("ver", ver)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key(secret))
                .compact();
    }

    /**
     * 解析 token，失败抛出 JwtException。
     */
    public static Claims parse(String secret, String token) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(key(secret))
                .build()
                .parseSignedClaims(token);
        return jws.getPayload();
    }

    /**
     * 校验 token 是否合法且未过期。
     */
    public static boolean validate(String secret, String token) {
        try {
            parse(secret, token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public static String jti(String secret, String token) {
        return parse(secret, token).getId();
    }

    public static Long userId(String secret, String token) {
        return Long.valueOf(parse(secret, token).getSubject());
    }

    public static Map<String, Object> claims(String secret, String token) {
        return parse(secret, token);
    }

    /** token 中的用户级版本号，用于与 Redis 比对实现批量失效（缺省 0）。 */
    public static int tokenVersion(Claims claims) {
        Object ver = claims.get("ver");
        return ver == null ? 0 : ((Number) ver).intValue();
    }

    /** token 中的权限角色列表。 */
    @SuppressWarnings("unchecked")
    public static List<String> roles(Claims claims) {
        Object roles = claims.get("roles");
        if (roles == null) {
            return List.of();
        }
        if (roles instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(roles));
    }
}
