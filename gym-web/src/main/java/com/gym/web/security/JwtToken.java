package com.gym.web.security;

import org.apache.shiro.authc.AuthenticationToken;

/**
 * 把 Bearer JWT 包装成 Shiro 可识别的 Token。
 */
public class JwtToken implements AuthenticationToken {

    private final String token;

    public JwtToken(String token) {
        this.token = token;
    }

    @Override
    public Object getPrincipal() {
        return token;
    }

    @Override
    public Object getCredentials() {
        return token;
    }

    public String getToken() {
        return token;
    }
}
