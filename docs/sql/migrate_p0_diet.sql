-- P0：饮食记录表（智能体 getCurrentDietLog 工具依赖）
-- 可在已有库上安全执行：IF NOT EXISTS

CREATE TABLE IF NOT EXISTS diet_log (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    log_date    DATE         NOT NULL                COMMENT '记录日期',
    meal_type   TINYINT      NOT NULL DEFAULT 1      COMMENT '1早餐 2午餐 3晚餐 4加餐',
    food_name   VARCHAR(64)  NOT NULL                COMMENT '食物名称',
    calories    INT          NOT NULL DEFAULT 0      COMMENT '热量 kcal',
    protein_g   DECIMAL(6,1)                         COMMENT '蛋白质克数',
    note        VARCHAR(255),
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_date (user_id, log_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '饮食热量粗略记录';
