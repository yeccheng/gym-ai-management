package com.gym.web.security;

import com.gym.common.security.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.credential.CredentialsMatcher;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.springframework.stereotype.Component;

/**
 * JWT Realm：校验签名 / 黑名单，并把角色灌进 AuthorizationInfo。
 *
 * <p>凭证在 {@link JwtUtil} 里已验完，故 CredentialsMatcher 直接放行，
 * 避免 Shiro 再按「用户名密码」规则比对一次。
 */
@Component
@RequiredArgsConstructor
public class JwtRealm extends AuthorizingRealm {

    private final JwtUtil jwtUtil;
    private final TokenBlacklist tokenBlacklist;

    {
        // JWT 已在 doGetAuthenticationInfo 验签，这里不做二次密码比对
        CredentialsMatcher noop = (token, info) -> true;
        setCredentialsMatcher(noop);
    }

    @Override
    public boolean supports(AuthenticationToken token) {
        return token instanceof JwtToken;
    }

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        Object primary = principals.getPrimaryPrincipal();
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
        if (primary instanceof LoginPrincipal login) {
            info.addRole(login.roleName());
        }
        return info;
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token)
            throws AuthenticationException {
        String jwt = ((JwtToken) token).getToken();
        if (jwt == null || jwt.isBlank() || !jwtUtil.isValid(jwt)) {
            throw new IncorrectCredentialsException("无效或过期的 Token");
        }
        try {
            Claims claims = jwtUtil.parse(jwt);
            if (tokenBlacklist.isBlacklisted(claims.getId())) {
                throw new IncorrectCredentialsException("Token 已失效");
            }
            Long userId = Long.valueOf(claims.getSubject());
            Integer roleCode = claims.get("role", Integer.class);
            LoginPrincipal principal = new LoginPrincipal(
                    userId, roleCode, Role.roleNameOf(roleCode));
            return new SimpleAuthenticationInfo(principal, jwt, getName());
        } catch (JwtException | IllegalArgumentException | ClassCastException e) {
            throw new IncorrectCredentialsException("Token 解析失败", e);
        }
    }
}
