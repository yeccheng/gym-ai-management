-- 业务模块补齐迁移脚本
-- 说明：线上 member 表是旧结构（age/experience/note，varchar 的 gender/goal），
-- 与 schema.sql 设计不一致；demo 训练数据走 sys_user.id，不依赖 member，可安全重建。

-- 1) 重建 member（新增 status：1正常 2挂失 3冻结 4注销）
DROP TABLE IF EXISTS member;
CREATE TABLE member (
    id          BIGINT      PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(32) NOT NULL,
    phone       VARCHAR(20) NOT NULL,
    gender      TINYINT     NOT NULL DEFAULT 0          COMMENT '0未知 1男 2女',
    birthday    DATE,
    height_cm   DECIMAL(5, 2),
    weight_kg   DECIMAL(5, 2),
    goal        TINYINT     NOT NULL DEFAULT 1          COMMENT '1增肌 2减脂 3力量 4塑形 5健康',
    train_level TINYINT     NOT NULL DEFAULT 1          COMMENT '1新手 2中级 3高级',
    join_date   DATE,
    status      TINYINT     NOT NULL DEFAULT 1          COMMENT '1正常 2挂失 3冻结 4注销',
    deleted     TINYINT     NOT NULL DEFAULT 0,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_phone_deleted (phone, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '会员';

-- 2) 器材表（论文(1)器材管理：登记/报修/维护）
CREATE TABLE IF NOT EXISTS equipment (
    id         BIGINT      PRIMARY KEY AUTO_INCREMENT,
    name       VARCHAR(64) NOT NULL,
    category   TINYINT     NOT NULL DEFAULT 1          COMMENT '1有氧 2力量 3自由重量 4功能',
    location   VARCHAR(32),
    status     TINYINT     NOT NULL DEFAULT 1          COMMENT '1正常 2报修 3停用',
    buy_date   DATE,
    last_maint DATE,
    note       VARCHAR(255),
    deleted    TINYINT     NOT NULL DEFAULT 0,
    create_time DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '器材';

-- 3) 种子数据：让新模块在演示时即有内容
INSERT INTO member (name, phone, gender, height_cm, weight_kg, goal, train_level, join_date, status) VALUES
 ('叶程', '13800000001', 1, 172.5, 70.0, 3, 2, CURDATE(), 1),
 ('李雷', '13800000002', 1, 178.0, 75.0, 1, 1, CURDATE(), 1);

INSERT INTO coach (name, phone, level, specialty, intro) VALUES
 ('王教练', '13900000001', 3, '增肌/力量举', '国家健身教练职业资格，8 年带训经验'),
 ('赵教练', '13900000002', 2, '减脂/塑形', 'ACE 认证，擅长新手体适能');

INSERT INTO course (name, coach_id, capacity, course_time, duration_min, room, status) VALUES
 ('力量基础课', 1, 8,  DATE_ADD(NOW(), INTERVAL 1 DAY), 60, 'A教室', 1),
 ('减脂燃脂课', 2, 10, DATE_ADD(NOW(), INTERVAL 2 DAY), 45, 'B教室', 1),
 ('高级力量举', 1, 6,  DATE_ADD(NOW(), INTERVAL 3 DAY), 90, 'A教室', 1);

INSERT INTO equipment (name, category, location, status, buy_date, last_maint) VALUES
 ('跑步机',     1, '有氧区',     1, '2024-01-10', '2026-09-01'),
 ('杠铃深蹲架', 2, '自由力量区', 1, '2024-03-15', '2026-09-20'),
 ('坐姿推胸机', 2, '固定器械区', 2, '2024-02-01', '2026-08-15'),
 ('壶铃组',     3, '自由重量区', 1, '2024-05-20', '2026-10-01');

-- 给叶程开一张季卡次卡，供演示「签到消次」
INSERT INTO membership_card (member_id, card_name, card_type, total_count, remain_count, start_date, end_date, price, status)
 VALUES (1, '季卡次卡', 1, 30, 30, CURDATE(), DATE_ADD(CURDATE(), INTERVAL 90 DAY), 999.00, 1);
