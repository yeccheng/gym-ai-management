package com.gym.web.security;

import cn.hutool.crypto.digest.BCrypt;

/**
 * Hutool BCrypt 实现，校验算法与 Spring Security BCrypt 互通。
 */
public class BcryptPasswordEncoder implements PasswordEncoder {

    @Override
    public String encode(String rawPassword) {
        return BCrypt.hashpw(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }
        return BCrypt.checkpw(rawPassword, encodedPassword);
    }
}
