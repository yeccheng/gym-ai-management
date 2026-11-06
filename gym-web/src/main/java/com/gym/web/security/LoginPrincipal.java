package com.gym.web.security;

/**
 * Shiro 认证主体：业务侧用 userId，授权侧用 roleName（如 ADMIN）。
 */
public record LoginPrincipal(Long userId, Integer roleCode, String roleName) {
}
