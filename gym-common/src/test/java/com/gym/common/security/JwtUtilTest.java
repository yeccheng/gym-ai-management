package com.gym.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 行为的锁死测试。
 * 安全组件的特征是「平时不报错，出事就是漏洞」，所以必须用测试固化预期行为。
 */
class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        // @PostConstruct 在纯单元测试里不会执行，需手动触发密钥初始化
        jwtUtil.init();
    }

    @Test
    @DisplayName("签发后能解析出 userId、username、role")
    void roundTrip() {
        String token = jwtUtil.generate(1001L, "coach01", 2);
        assertNotNull(jwtUtil.parse(token).getId(), "jti 必须存在，黑名单依赖它");
        assertEquals("1001", jwtUtil.parse(token).getSubject());
        assertEquals("coach01", jwtUtil.parse(token).get("username", String.class));
        assertEquals(2, jwtUtil.parse(token).get("role", Integer.class));
        assertTrue(jwtUtil.isValid(token));
    }

    @Test
    @DisplayName("剩余有效期应等于配置的过期时间")
    void remaining() {
        jwtUtil.setAccessExpireSeconds(60L);
        String token = jwtUtil.generate(1L, "admin", 1);
        long remaining = jwtUtil.remainingSeconds(token);
        assertTrue(remaining > 55 && remaining <= 60, "实际剩余 " + remaining);
    }

    @Test
    @DisplayName("尾部被篡改的 Token 必须判无效")
    void tamperedTokenRejected() {
        String token = jwtUtil.generate(1L, "admin", 1);
        assertFalse(jwtUtil.isValid(token + "tampered"));
    }

    @Test
    @DisplayName("换密钥签发的 Token 必须判无效，否则等于鉴权失效")
    void otherSecretRejected() {
        JwtUtil attacker = new JwtUtil();
        attacker.setSecret("another-secret-key-with-different-content-0123456789abcdef");
        attacker.init();
        String forged = attacker.generate(1L, "admin", 1);
        assertFalse(jwtUtil.isValid(forged));
    }

    @Test
    @DisplayName("空 Token 判无效且不抛异常")
    void nullishToken() {
        assertFalse(jwtUtil.isValid(""));
        assertFalse(jwtUtil.isValid("not.a.jwt"));
    }
}
