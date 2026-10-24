package com.kzai.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.common.constant.Constants;
import com.kzai.common.result.R;
import com.kzai.common.result.ResultCode;
import com.kzai.common.security.InternalAuthUtils;
import com.kzai.common.security.JwtUtils;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 网关 JWT 校验过滤器（系统设计说明书 6.1、6.3.2）。
 * <p>
 * 职责：白名单放行 → 校验 token → 查 Redis 黑名单（扩展设计 E1）→ 注入用户上下文请求头与 requestId。
 * 未登录返回 401，越权由业务服务返回 403。
 */
@Slf4j
@Component
public class AuthGlobalFilter implements WebFilter, Ordered {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final String BEARER = "Bearer ";

    private final String secret;
    private final String internalSecret;
    private final List<String> whiteList;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public AuthGlobalFilter(@Value("${kzai.jwt.secret}") String secret,
                            @Value("${kzai.internal.secret}") String internalSecret,
                            @Value("${kzai.auth.white-list}") String whiteList,
                            ReactiveStringRedisTemplate redisTemplate,
                            ObjectMapper objectMapper,
                            WebClient.Builder webClientBuilder) {
        this.secret = secret;
        this.internalSecret = internalSecret;
        this.whiteList = List.of(whiteList.split(","));
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        // 用户服务内部接口（回源 token 版本号），请求头带内部签名
        this.webClient = webClientBuilder.baseUrl("http://kzai-user").build();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        String requestId = UUID.randomUUID().toString().replace("-", "");

        // M2 权限约束：服务间内部接口禁止从网关外部访问
        if (path.startsWith(Constants.INTERNAL_PATH_PREFIX)) {
            return forbidden(exchange, "内部接口禁止外部访问", requestId);
        }

        if (isWhiteListed(path)) {
            return chain.filter(mutate(exchange, null, null, null, null, requestId));
        }

        String token = resolveToken(request);
        if (token == null) {
            return unauthorized(exchange, "未登录或 token 缺失", requestId);
        }

        Claims claims;
        try {
            claims = JwtUtils.parse(secret, token);
        } catch (Exception e) {
            return unauthorized(exchange, "token 无效或已过期", requestId);
        }

        String jti = claims.getId();
        final String userId = claims.getSubject();
        final String username = String.valueOf(claims.get("username"));
        final String role = String.valueOf(claims.get("role"));
        final String roles = String.join(",", JwtUtils.roles(claims));
        final int tokenVersion = JwtUtils.tokenVersion(claims);

        // 扩展设计 E1：① 按 jti 的黑名单（退出登录）。Redis 不可用不得默认放行 → 转失败
        Mono<Boolean> blacklisted = redisTemplate
                .hasKey(Constants.REDIS_KEY_TOKEN_BLACKLIST + jti)
                .defaultIfEmpty(false)
                .timeout(Duration.ofSeconds(2))
                .onErrorMap(e -> new TokenStateUnavailableException(e));
        // ② 用户级版本号（禁用账号 / 重置密码 / 改密后全部旧 token 失效）
        //    遗留修复②：Redis 仅缓存，未命中则回源 MySQL；两者都不可用 → 拒绝放行
        Mono<Integer> currentVersion = redisTemplate.opsForValue()
                .get(Constants.REDIS_KEY_TOKEN_VERSION + userId)
                .map(this::parseVersion)
                .switchIfEmpty(Mono.defer(() -> fetchVersionFromUserService(userId, requestId)))
                .timeout(Duration.ofSeconds(3))
                .onErrorMap(e -> new TokenStateUnavailableException(e));

        return Mono.zip(blacklisted, currentVersion)
                .flatMap(tuple -> Boolean.TRUE.equals(tuple.getT1())
                        ? unauthorized(exchange, "token 已失效，请重新登录", requestId)
                        : tokenVersion < tuple.getT2()
                        ? unauthorized(exchange, "账号状态已变更，请重新登录", requestId)
                        : chain.filter(mutate(exchange, userId, username, role, roles, requestId)))
                .onErrorResume(TokenStateUnavailableException.class,
                        e -> unauthorized(exchange, "账号状态校验服务不可用，请稍后重试", requestId));
    }

    /** Redis 缓存未命中时回源用户服务（MySQL 权威版本号），并回写缓存 */
    private Mono<Integer> fetchVersionFromUserService(String userId, String requestId) {
        return webClient.get()
                .uri("/internal/users/{userId}/token-version", userId)
                .header(Constants.HEADER_REQUEST_ID, requestId)
                .header(Constants.HEADER_INTERNAL_SIGN, InternalAuthUtils.sign(internalSecret,
                        InternalAuthUtils.payload(requestId, "", "", "", "")))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<R<Integer>>() {
                })
                .map(result -> result == null || result.getData() == null ? 0 : result.getData())
                .doOnNext(version -> redisTemplate.opsForValue()
                        .set(Constants.REDIS_KEY_TOKEN_VERSION + userId,
                                String.valueOf(version), Duration.ofHours(24))
                        .onErrorResume(e -> Mono.empty())
                        .subscribe());
    }

    /** 账号状态校验不可用：用于触发 fail-closed，不做静默放行 */
    private static class TokenStateUnavailableException extends RuntimeException {
        TokenStateUnavailableException(Throwable cause) {
            super(cause);
        }
    }

    private int parseVersion(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private ServerWebExchange mutate(ServerWebExchange exchange, String userId, String username,
                                     String role, String roles, String requestId) {
        ServerHttpRequest.Builder builder = exchange.getRequest().mutate();
        // 先移除客户端伪造的身份头与签名头，再由网关注入（前端无法伪造身份）
        builder.headers(headers -> {
            headers.remove(Constants.HEADER_USER_ID);
            headers.remove(Constants.HEADER_USERNAME);
            headers.remove(Constants.HEADER_USER_ROLE);
            headers.remove(Constants.HEADER_USER_ROLES);
            headers.remove(Constants.HEADER_REQUEST_ID);
            headers.remove(Constants.HEADER_INTERNAL_SIGN);
        });
        builder.header(Constants.HEADER_REQUEST_ID, requestId);
        if (userId != null) {
            builder.header(Constants.HEADER_USER_ID, userId);
            builder.header(Constants.HEADER_USERNAME, username == null ? "" : username);
            builder.header(Constants.HEADER_USER_ROLE, role == null ? "" : role);
            builder.header(Constants.HEADER_USER_ROLES, roles == null ? "" : roles);
        }
        // 业务服务据此判定请求确实来自网关（遗留修复④）
        builder.header(Constants.HEADER_INTERNAL_SIGN, InternalAuthUtils.sign(internalSecret,
                InternalAuthUtils.payload(requestId, userId, username, role, roles)));
        return exchange.mutate().request(builder.build()).build();
    }

    private boolean isWhiteListed(String path) {
        return whiteList.stream().anyMatch(p -> PATH_MATCHER.match(p.trim(), path));
    }

    private String resolveToken(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith(BEARER)) {
            return auth.substring(BEARER.length());
        }
        return null;
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String msg, String requestId) {
        return write(exchange, HttpStatus.FORBIDDEN, ResultCode.FORBIDDEN, msg, requestId);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String msg, String requestId) {
        return write(exchange, HttpStatus.UNAUTHORIZED, ResultCode.UNAUTHORIZED, msg, requestId);
    }

    private Mono<Void> write(ServerWebExchange exchange, HttpStatus status, int code,
                             String msg, String requestId) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(Constants.HEADER_REQUEST_ID, requestId);
        try {
            String body = objectMapper.writeValueAsString(
                    R.fail(code, msg).requestId(requestId));
            DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return response.setComplete();
        }
    }
}
