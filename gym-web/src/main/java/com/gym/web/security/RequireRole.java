package com.gym.web.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级权限控制。
 *
 * <p>「自定义注解 + 拦截器」，而不是 Shiro 的 {@code @RequiresRoles}。
 * 注解语义直白；链路可讲清：注解 → 拦截器 → Subject.hasRole。
 *
 * <p>用法：@RequireRole(Role.ADMIN) 或 @RequireRole({Role.COACH, Role.ADMIN})。
 * 数组语义是「或」——命中其中任一角色即放行。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireRole {

    Role[] value();
}
