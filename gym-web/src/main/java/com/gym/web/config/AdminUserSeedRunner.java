package com.gym.web.config;

import com.gym.system.entity.SysUser;
import com.gym.system.mapper.SysUserMapper;
import com.gym.web.security.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import com.gym.web.security.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 确保存在独立的管理员账号。
 *
 * <p>{@code demo} 刻意保持会员角色——它是练铁人主路径的样板账号；
 * 办卡/约课 CRUD 只给 {@code admin}，两边互不抢戏。
 */
@Slf4j
@Component
@Order(5)
@RequiredArgsConstructor
public class AdminUserSeedRunner implements CommandLineRunner {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "admin123";

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        SysUser existing = userMapper.selectByUsername(ADMIN_USERNAME);
        if (existing == null) {
            SysUser admin = new SysUser();
            admin.setUsername(ADMIN_USERNAME);
            admin.setPassword(passwordEncoder.encode(ADMIN_PASSWORD));
            admin.setRealName("馆长");
            admin.setRole(Role.ADMIN.code());
            admin.setStatus(1);
            userMapper.insert(admin);
            log.info("已创建管理员账号 {} / {}（仅后台；会员请用 demo 或自行注册）",
                    ADMIN_USERNAME, ADMIN_PASSWORD);
            return;
        }
        if (existing.getRole() == null || existing.getRole() != Role.ADMIN.code()) {
            existing.setRole(Role.ADMIN.code());
            userMapper.updateById(existing);
            log.info("已将账号 {} 纠正为管理员角色", ADMIN_USERNAME);
        }
    }
}
