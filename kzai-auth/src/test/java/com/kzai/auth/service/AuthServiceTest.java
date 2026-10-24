package com.kzai.auth.service;

import com.kzai.auth.client.UserClient;
import com.kzai.auth.client.dto.UserDTO;
import com.kzai.auth.dto.LoginRequest;
import com.kzai.auth.dto.LoginVO;
import com.kzai.common.constant.Constants;
import com.kzai.common.exception.AuthException;
import com.kzai.common.result.ResultCode;
import com.kzai.common.result.R;
import com.kzai.common.security.JwtUtils;
import com.kzai.common.security.TokenVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCrypt;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private static final String SECRET = "kzai-course-stack-ai-jwt-secret-key-2026-must-be-32-bytes";

    private UserClient userClient;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private TokenVersionService tokenVersionService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userClient = mock(UserClient.class);
        redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        valueOps = ops;
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        tokenVersionService = mock(TokenVersionService.class);
        when(tokenVersionService.current(org.mockito.ArgumentMatchers.anyLong())).thenReturn(0);
        // M13：登录审计桥用桩（不阻断，审计写入路径由 E2E 覆盖）
        authService = new AuthService(userClient, redisTemplate, tokenVersionService,
                org.mockito.Mockito.mock(LoginAuditBridge.class), SECRET, 7200);
    }

    @Test
    void login_success_withValidPassword() {
        mockUser("student01", "student123", 1, Constants.USER_TYPE_STUDENT);

        LoginVO vo = authService.login(request("student01", "student123"));

        assertEquals("student01", vo.getUsername());
        assertEquals(Constants.USER_TYPE_STUDENT, vo.getRole());
        assertEquals("Bearer", vo.getTokenType());
        assertEquals(7200L, vo.getExpiresIn());
        assertTrue(JwtUtils.validate(SECRET, vo.getToken()));
    }

    @Test
    void login_fail_wrongPassword() {
        mockUser("student01", "student123", 1, Constants.USER_TYPE_STUDENT);

        AuthException ex = assertThrows(AuthException.class,
                () -> authService.login(request("student01", "bad")));
        // 约束 3.3(8)：不区分账号不存在与密码错误，统一 401
        assertEquals(ResultCode.UNAUTHORIZED, ex.getCode());
        assertEquals("账号或密码错误", ex.getMessage());
    }

    @Test
    void login_fail_userNotFound_sameMessage() {
        when(userClient.findByUsername("ghost")).thenReturn(R.ok(null));

        AuthException ex = assertThrows(AuthException.class,
                () -> authService.login(request("ghost", "x")));
        assertEquals("账号或密码错误", ex.getMessage());
    }

    @Test
    void login_fail_disabledAccount() {
        mockUser("locked", "student123", 0, Constants.USER_TYPE_STUDENT);

        AuthException ex = assertThrows(AuthException.class,
                () -> authService.login(request("locked", "student123")));
        assertEquals(ResultCode.FORBIDDEN, ex.getCode());
        assertEquals("账号已被禁用，请联系管理员", ex.getMessage());
    }

    @Test
    void logout_writesBlacklistWithRemainingTtl() {
        String token = JwtUtils.generate(SECRET, 3600, 3L, "student01", 2);

        authService.logout(token);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(keyCaptor.capture(), eq("1"), org.mockito.ArgumentMatchers.any(Duration.class));
        assertTrue(keyCaptor.getValue().startsWith(Constants.REDIS_KEY_TOKEN_BLACKLIST));
    }

    @Test
    void logout_ignoreInvalidToken() {
        authService.logout("not-a-jwt");
        org.mockito.Mockito.verifyNoInteractions(valueOps);
    }

    private void mockUser(String username, String rawPassword, int status, int type) {
        UserDTO dto = new UserDTO();
        dto.setId(3L);
        dto.setUsername(username);
        dto.setCellPhone("13800000003");
        dto.setPassword(BCrypt.hashpw(rawPassword, BCrypt.gensalt(10)));
        dto.setStatus(status);
        dto.setType(type);
        when(userClient.findByUsername(anyString())).thenReturn(R.ok(dto));
    }

    private LoginRequest request(String username, String password) {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);
        return req;
    }
}
