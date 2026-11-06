package com.gym.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析。
 *
 * <p>放在 gym-common 是因为 AI 模块的长任务也要校验同一个 Token，
 * 放这里避免 gym-ai 反向依赖 gym-web。
 */
@Data
@Component
@ConfigurationProperties(prefix = "gym.jwt")
public class JwtUtil {

    /**
     * HS256 要求密钥至少 256 bit（32 字节）。
     * 默认值是给开发环境用的，生产必须从配置注入——
     * 这个长度是 jjwt 的硬性要求，短了会抛 WeakKeyException。
     */
    private String secret = "gym-dev-secret-key-override-in-production-0123456789abcdef";

    private long accessExpireSeconds = 7200L;
    private long refreshExpireSeconds = 604800L;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 Token。
     * 每个 Token 带唯一 jti，退出登录时靠它进 Redis 黑名单——
     * 这是 JWT 无状态特性的唯一解药：JWT 本身无法主动失效。
     *
     * @param displayName 界面招呼用的名字（真实姓名），不要把登录名 demo 直接贴脸上
     */
    public String generate(Long userId, String username, int role, String displayName) {
        String shown = (displayName == null || displayName.isBlank()) ? username : displayName.trim();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("displayName", shown)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpireSeconds * 1000))
                .signWith(secretKey)
                .compact();
    }

    /** 兼容旧调用：没有显示名时退回用户名 */
    public String generate(Long userId, String username, int role) {
        return generate(userId, username, role, username);
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 剩余有效期秒数。
     * 黑名单的 TTL 就用它——Token 过期后黑名单条目自动消失，
     * 不需要额外的清理任务，Redis 也不会无限增长。
     */
    public long remainingSeconds(String token) {
        Date expiration = parse(token).getExpiration();
        return Math.max((expiration.getTime() - System.currentTimeMillis()) / 1000, 1);
    }

    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
