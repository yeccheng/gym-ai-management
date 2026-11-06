package com.gym.web.security;

/**
 * 密码哈希接口。
 *
 * <p>从 Spring Security 迁出后保留同名能力，实现用 Hutool BCrypt，
 * 与历史上 {@code BCryptPasswordEncoder} 写出的密文兼容。
 */
public interface PasswordEncoder {

    String encode(String rawPassword);

    boolean matches(String rawPassword, String encodedPassword);
}
