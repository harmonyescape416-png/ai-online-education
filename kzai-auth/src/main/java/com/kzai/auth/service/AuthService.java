package com.kzai.auth.service;

import com.kzai.auth.client.UserClient;
import com.kzai.auth.client.dto.UserDTO;
import com.kzai.auth.dto.LoginRequest;
import com.kzai.auth.dto.LoginVO;
import com.kzai.common.constant.Constants;
import com.kzai.common.exception.AuthException;
import com.kzai.common.result.R;
import com.kzai.common.result.ResultCode;
import com.kzai.common.security.JwtUtils;
import com.kzai.common.security.TokenVersionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 登录业务：BCrypt 校验 + 账号状态校验 + JWT 签发（需求分析说明书 3.2.5.1、3.3(8)）。
 */
@Slf4j
@Service
public class AuthService {

    private static final String TOKEN_TYPE = "Bearer";

    private final UserClient userClient;
    private final StringRedisTemplate redisTemplate;
    private final TokenVersionService tokenVersionService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final String secret;
    private final long expireSeconds;
    private final LoginAuditBridge loginAudit; // M13：登录事件审计

    public AuthService(UserClient userClient,
                       StringRedisTemplate redisTemplate,
                       TokenVersionService tokenVersionService,
                       LoginAuditBridge loginAudit,
                       @Value("${kzai.jwt.secret}") String secret,
                       @Value("${kzai.jwt.expire-seconds}") long expireSeconds) {
        this.userClient = userClient;
        this.redisTemplate = redisTemplate;
        this.tokenVersionService = tokenVersionService;
        this.loginAudit = loginAudit;
        this.secret = secret;
        this.expireSeconds = expireSeconds;
    }

    /** M15.17：从 Authorization 头解析登录用户（CHANGE_PHONE 等登录态场景用；失效抛 401 语义异常） */
    public Long requireLoginUserId(String authorizationHeader) {
        String token = authorizationHeader != null && authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring("Bearer ".length()) : null;
        if (token == null || token.isBlank()) {
            throw new com.kzai.common.exception.AuthException(ResultCode.UNAUTHORIZED, "未登录或 token 无效");
        }
        try {
            var claims = com.kzai.common.security.JwtUtils.parse(secret, token);
            String uid = claims.getSubject();
            if (uid == null || uid.isBlank()) {
                throw new com.kzai.common.exception.AuthException(ResultCode.UNAUTHORIZED, "未登录或 token 无效");
            }
            return Long.valueOf(String.valueOf(uid));
        } catch (com.kzai.common.exception.AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new com.kzai.common.exception.AuthException(ResultCode.UNAUTHORIZED, "未登录或 token 无效");
        }
    }

    public LoginVO login(LoginRequest request) {
        // M15.17：统一账号输入——手机号格式按手机号查询，否则按用户名查询（后端明确区分，避免歧义）
        String account = request.getUsername();
        boolean isPhone = account != null && account.matches("^1[3-9]\\d{9}$");
        R<UserDTO> result = isPhone
                ? userClient.findByPhone(account)
                : userClient.findByUsername(account);
        UserDTO user = result == null ? null : result.getData();

        // 约束（3.3(8)）：登录失败提示不区分账号不存在与密码错误
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginAudit.write(user == null ? null : user.getId(), request.getUsername(),
                    false, "BAD_CREDENTIALS");
            throw new AuthException(ResultCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            loginAudit.write(user.getId(), request.getUsername(), false, "DISABLED");
            throw new AuthException(ResultCode.FORBIDDEN, "账号已被禁用，请联系管理员");
        }

        List<String> roles = resolveRoles(user);
        int version = tokenVersionService.current(user.getId());
        String token = JwtUtils.generate(secret, expireSeconds,
                user.getId(), user.getUsername(), user.getType(), roles, version);
        loginAudit.write(user.getId(), user.getUsername(), true, "LOGIN");
        return LoginVO.builder()
                .token(token)
                .tokenType(TOKEN_TYPE)
                .expiresIn(expireSeconds)
                .userId(user.getId())
                .username(user.getUsername())
                .role(user.getType())
                .roles(roles)
                .build();
    }

    /**
     * 退出登录：把 token 的 jti 写入 Redis 黑名单，剩余有效期后自动清理。
     * 扩展设计 E1：满足「退出登录后 token 失效」「账号禁用后已签发 token 立即失效」。
     */
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            var claims = JwtUtils.parse(secret, token);
            long remain = claims.getExpiration().getTime() - System.currentTimeMillis();
            if (remain <= 0) {
                return;
            }
            redisTemplate.opsForValue().set(
                    Constants.REDIS_KEY_TOKEN_BLACKLIST + claims.getId(),
                    "1",
                    Duration.ofMillis(remain));
        } catch (Exception e) {
            // 扩展能力降级：Redis 不可用或 token 非法时，不阻断退出流程
            log.warn("[auth] logout blacklist skipped: {}", e.getMessage());
        }
    }

    /**
     * 权限角色：优先取 sys_user_role（M2 扩展表），无记录时按原始 user.type 推导，保证历史账号兼容。
     * <p>
     * 遗留修复①：type=1 回退 STAFF（无管理权限），ADMIN 必须显式授予。
     * 与 kzai-user 的 UserRoleService#deriveFromType 保持一致，避免两处推导不一致造成提权。
     */
    private List<String> resolveRoles(UserDTO user) {
        List<String> roles = user.getRoles();
        if (roles != null && !roles.isEmpty()) {
            return roles;
        }
        Integer type = user.getType();
        if (type == null) {
            return List.of(Constants.ROLE_STUDENT);
        }
        return switch (type) {
            case Constants.USER_TYPE_STAFF -> List.of(Constants.ROLE_STAFF);
            case Constants.USER_TYPE_TEACHER -> List.of(Constants.ROLE_TEACHER);
            default -> List.of(Constants.ROLE_STUDENT);
        };
    }
}
