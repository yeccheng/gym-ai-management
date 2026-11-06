package com.gym.web.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gym.system.entity.SysUser;
import com.gym.system.mapper.SysUserMapper;
import com.gym.web.security.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import com.gym.web.security.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 保证系统里一定有一个默认管理员。
 *
 * <p>为什么不写在 schema.sql 里：BCrypt 哈希每次生成都不同（自带盐），
 * 写死一条 SQL 到生产环境是灾难，也失去了「首次启动」这个语义。
 *
 * <p><b>为什么判断条件是「有没有 admin」而不是「用户表是不是空的」。</b>
 * 早期版本写的是 {@code selectCount(null) > 0} 就跳过，结果演示账号 demo 先被
 * DemoDataRunner 建出来后，表非空，管理员再也不会被创建——
 * 系统里只剩一个普通用户，谁都进不了管理功能，且没有任何报错。
 * 初始化器之间互相依赖对方的"空表"假设，是最容易埋雷的一种写法：
 * 判断条件必须直接对准自己要保证的那件事，不能拿别人的状态当代理指标。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD = "admin123";

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        LambdaQueryWrapper<SysUser> query = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, DEFAULT_ADMIN_USERNAME);
        if (userMapper.selectCount(query) > 0) {
            return;
        }
        SysUser admin = new SysUser();
        admin.setUsername(DEFAULT_ADMIN_USERNAME);
        admin.setPassword(passwordEncoder.encode(DEFAULT_ADMIN_PASSWORD));
        admin.setRealName("系统管理员");
        admin.setRole(Role.ADMIN.code());
        admin.setStatus(1);
        userMapper.insert(admin);
        log.info("已创建默认管理员 {} / {}，请登录后立即修改",
                DEFAULT_ADMIN_USERNAME, DEFAULT_ADMIN_PASSWORD);
    }
}
