package com.gym.web.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * JWT 黑名单。
 *
 * <p>JWT 的硬伤是**无状态即无法主动失效**：退出登录后，只要没过期，
 * 旧 Token 照样能用。黑名单是标准解法，Shiro + JWT 无状态方案同样必须面对。
 *
 * <p>关键设计：TTL 直接用 Token 的剩余有效期。Token 一过期，条目自动消失，
 * 不需要清理任务，Redis 也不会无限增长。
 */
@Component
@RequiredArgsConstructor
public class TokenBlacklist {

    private static final String KEY_PREFIX = "jwt:blacklist:";

    private final StringRedisTemplate redisTemplate;

    public void blacklist(String jti, long ttlSeconds) {
        redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofSeconds(Math.max(ttlSeconds, 1)));
    }

    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
    }
}
