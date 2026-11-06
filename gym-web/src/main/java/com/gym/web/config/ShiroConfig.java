package com.gym.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gym.web.security.BcryptPasswordEncoder;
import com.gym.web.security.JsonRolesAuthorizationFilter;
import com.gym.web.security.JwtAuthFilter;
import com.gym.web.security.JwtRealm;
import com.gym.web.security.PasswordEncoder;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.mgt.DefaultSessionStorageEvaluator;
import org.apache.shiro.mgt.DefaultSubjectDAO;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shiro + JWT 安全配置（替代原 Spring Security）。
 *
 * <p>要点：禁用 Session 存储，每次请求靠 Bearer Token；
 * {@code /api/**} 额外要求 ADMIN 角色，其余受保护路径只需登录。
 */
@Configuration
public class ShiroConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BcryptPasswordEncoder();
    }

    @Bean
    public DefaultWebSecurityManager securityManager(JwtRealm jwtRealm) {
        DefaultWebSecurityManager manager = new DefaultWebSecurityManager();
        manager.setRealm(jwtRealm);
        manager.setRememberMeManager(null);

        // 前后端分离 + JWT：关闭 Shiro Session，避免服务端再存一份会话
        DefaultSubjectDAO subjectDAO = new DefaultSubjectDAO();
        DefaultSessionStorageEvaluator evaluator = new DefaultSessionStorageEvaluator();
        evaluator.setSessionStorageEnabled(false);
        subjectDAO.setSessionStorageEvaluator(evaluator);
        manager.setSubjectDAO(subjectDAO);
        // 供 AuthInterceptor 等非 Filter 代码通过 SecurityUtils.getSubject() 取当前用户
        SecurityUtils.setSecurityManager(manager);
        return manager;
    }

    @Bean
    public ShiroFilterFactoryBean shiroFilterFactoryBean(SecurityManager securityManager,
                                                         ObjectMapper objectMapper) {
        ShiroFilterFactoryBean factory = new ShiroFilterFactoryBean();
        factory.setSecurityManager(securityManager);

        Map<String, Filter> filters = new LinkedHashMap<>();
        filters.put("jwt", new JwtAuthFilter(objectMapper));
        filters.put("roles", new JsonRolesAuthorizationFilter(objectMapper));
        factory.setFilters(filters);

        Map<String, String> chain = new LinkedHashMap<>();
        // 认证相关
        chain.put("/auth/login", "anon");
        chain.put("/auth/register", "anon");
        chain.put("/auth/sms/send", "anon");
        chain.put("/auth/login/sms", "anon");
        chain.put("/auth/password/reset", "anon");
        // 接口文档
        chain.put("/doc.html", "anon");
        chain.put("/v3/api-docs/**", "anon");
        chain.put("/webjars/**", "anon");
        chain.put("/favicon.ico", "anon");
        // 前端静态资源（页面放行，业务接口仍各自带 JWT）
        chain.put("/", "anon");
        chain.put("/index.html", "anon");
        chain.put("/app.js", "anon");
        chain.put("/app.css", "anon");
        chain.put("/food-db.js", "anon");
        chain.put("/css/**", "anon");
        chain.put("/js/**", "anon");
        chain.put("/static/**", "anon");
        chain.put("/vendor/**", "anon");
        chain.put("/sw.js", "anon");
        chain.put("/manifest.webmanifest", "anon");
        chain.put("/error", "anon");
        // 场馆 ERP 仅管理员；其余需登录
        chain.put("/api/**", "jwt,roles[ADMIN]");
        chain.put("/**", "jwt");
        factory.setFilterChainDefinitionMap(chain);
        return factory;
    }
}
