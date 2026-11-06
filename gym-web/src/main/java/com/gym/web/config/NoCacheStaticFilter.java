package com.gym.web.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 登录页 / 前端入口禁止浏览器死缓存旧 HTML——
 * 否则会一直看到预填 demo、Function Calling 文案，和新后端对不上。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class NoCacheStaticFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path == null || path.isBlank() || "/".equals(path)
                || path.endsWith("/index.html") || path.endsWith("/app.js")
                || path.endsWith("/app.css") || path.contains("/vendor/app.css")) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0);
        }
        filterChain.doFilter(request, response);
    }
}
