-- P1/P2 migration: diet_log + plan_deviation

CREATE TABLE IF NOT EXISTS diet_log (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    log_date    DATE         NOT NULL,
    meal_type   TINYINT      NOT NULL DEFAULT 1,
    food_name   VARCHAR(64)  NOT NULL,
    calories    INT          NOT NULL DEFAULT 0,
    protein_g   DECIMAL(6,1),
    note        VARCHAR(255),
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_date (user_id, log_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS plan_deviation (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    session_id  BIGINT,
    exercise_id BIGINT,
    type        VARCHAR(32)  NOT NULL,
    note        VARCHAR(255),
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_time (user_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
