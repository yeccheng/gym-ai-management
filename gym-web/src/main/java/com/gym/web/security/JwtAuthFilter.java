package com.gym.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gym.common.result.R;
import com.gym.common.result.ResultCode;
import com.gym.common.security.UserContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.filter.authc.AuthenticatingFilter;
import org.apache.shiro.web.util.WebUtils;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JWT 认证过滤器：从 Authorization: Bearer 取 Token → Subject.login → 写入 UserContext。
 *
 * <p>无状态：登录成功后不建 Shiro Session；请求结束在 {@link #afterCompletion} 清 ThreadLocal。
 */
public class JwtAuthFilter extends AuthenticatingFilter {

    private final ObjectMapper objectMapper;

    public JwtAuthFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected AuthenticationToken createToken(ServletRequest request, ServletResponse response) {
        String token = resolveToken(WebUtils.toHttp(request));
        return StringUtils.hasText(token) ? new JwtToken(token) : null;
    }

    @Override
    protected boolean isAccessAllowed(ServletRequest request, ServletResponse response, Object mappedValue) {
        // OPTIONS 预检直接放行；其余一律走 onAccessDenied 做 login
        HttpServletRequest http = WebUtils.toHttp(request);
        if ("OPTIONS".equalsIgnoreCase(http.getMethod())) {
            return true;
        }
        return false;
    }

    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws Exception {
        String token = resolveToken(WebUtils.toHttp(request));
        if (!StringUtils.hasText(token)) {
            writeJson(WebUtils.toHttp(response), HttpServletResponse.SC_UNAUTHORIZED, ResultCode.UNAUTHORIZED);
            return false;
        }
        return executeLogin(request, response);
    }

    @Override
    protected boolean onLoginSuccess(AuthenticationToken token, Subject subject,
                                     ServletRequest request, ServletResponse response) {
        Object principal = subject.getPrincipal();
        if (principal instanceof LoginPrincipal login) {
            UserContext.setUserId(login.userId());
        }
        return true;
    }

    @Override
    protected boolean onLoginFailure(AuthenticationToken token, AuthenticationException e,
                                     ServletRequest request, ServletResponse response) {
        try {
            writeJson(WebUtils.toHttp(response), HttpServletResponse.SC_UNAUTHORIZED, ResultCode.UNAUTHORIZED);
        } catch (IOException ignored) {
            // 响应已提交时忽略
        }
        return false;
    }

    @Override
    public void afterCompletion(ServletRequest request, ServletResponse response, Exception exception) {
        // 线程复用，必须清掉身份，避免串号
        UserContext.clear();
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        return null;
    }

    private void writeJson(HttpServletResponse response, int status, ResultCode code) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(code)));
    }
}
