package com.gym.web.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 登录增强所需的轻量库表补丁：sys_user.phone。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthSchemaSupport {

    private final JdbcTemplate jdbc;

    @PostConstruct
    public void ensurePhoneColumn() {
        try {
            Integer n = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'sys_user'
                      AND COLUMN_NAME = 'phone'
                    """, Integer.class);
            if (n != null && n > 0) {
                return;
            }
            jdbc.execute("""
                    ALTER TABLE sys_user
                    ADD COLUMN phone VARCHAR(20) NULL COMMENT '登录手机号' AFTER real_name,
                    ADD KEY idx_phone (phone)
                    """);
            // 历史账号：用户名本身是手机号的，回填 phone
            jdbc.update("""
                    UPDATE sys_user
                    SET phone = username
                    WHERE deleted = 0
                      AND phone IS NULL
                      AND username REGEXP '^1[0-9]{10}$'
                    """);
            log.info("已为 sys_user 增加 phone 字段，并回填手机号用户名");
        } catch (Exception e) {
            log.warn("sys_user.phone 补丁跳过/失败（可手工 ALTER）: {}", e.getMessage());
        }
    }
}
