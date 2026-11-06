package com.gym.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gym.common.result.R;
import com.gym.common.result.ResultCode;
import com.gym.web.security.LoginPrincipal;
import com.gym.web.security.RequireRole;
import com.gym.web.security.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 权限拦截器。
 *
 * <p>配合 {@link RequireRole}：注解 → 拦截器 → Shiro Subject.hasRole。
 * 无注解的接口零开销；数组语义为「或」。
 */
@Slf4j
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireRole annotation = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (annotation == null) {
            return true;
        }

        Subject subject = SecurityUtils.getSubject();
        if (subject == null || !subject.isAuthenticated()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ResultCode.UNAUTHORIZED);
            return false;
        }

        boolean allowed = Arrays.stream(annotation.value())
                .map(Role::roleName)
                .anyMatch(subject::hasRole);
        if (!allowed) {
            Object principal = subject.getPrincipal();
            Object userId = principal instanceof LoginPrincipal login ? login.userId() : principal;
            log.warn("权限不足 userId={} 需要 {} 路径 {}",
                    userId,
                    Arrays.stream(annotation.value()).map(Role::roleName).collect(Collectors.toSet()),
                    request.getRequestURI());
            writeError(response, HttpServletResponse.SC_FORBIDDEN, ResultCode.FORBIDDEN);
            return false;
        }
        return true;
    }

    private void writeError(HttpServletResponse response, int status, ResultCode code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(code)));
    }
}
