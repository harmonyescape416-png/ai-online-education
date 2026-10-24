package com.kzai.common.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtUtilsTest {

    private static final String SECRET = "kzai-course-stack-ai-jwt-secret-key-2026-must-be-32-bytes";

    @Test
    void generateAndParse_ok() {
        String token = JwtUtils.generate(SECRET, 7200, 3L, "student01", 2);

        assertTrue(JwtUtils.validate(SECRET, token));
        Claims claims = JwtUtils.parse(SECRET, token);
        assertEquals("3", claims.getSubject());
        assertEquals("student01", claims.get("username"));
        assertEquals(2, ((Number) claims.get("role")).intValue());
        assertNotNull(claims.getId(), "jti 用于退出登录黑名单");
    }

    @Test
    void expiredToken_invalid() {
        String token = JwtUtils.generate(SECRET, -1, 3L, "student01", 2);
        assertFalse(JwtUtils.validate(SECRET, token));
    }

    @Test
    void tamperedToken_invalid() {
        String token = JwtUtils.generate(SECRET, 7200, 3L, "student01", 2);
        assertFalse(JwtUtils.validate("another-secret-key-another-secret-key-32b", token));
    }
}
