-- P0-2：示例数据与真实打卡隔离（同一张表，用标记区分）
ALTER TABLE workout_session
    ADD COLUMN is_demo TINYINT NOT NULL DEFAULT 0
        COMMENT '1=DemoDataRunner 示例数据 0=用户真实打卡' AFTER note;

-- 历史会话（旧版 Demo 灌的 26 周）标成示例；今天的保留为真实打卡
UPDATE workout_session SET is_demo = 1 WHERE workout_date < CURDATE();
UPDATE workout_session SET is_demo = 0 WHERE workout_date = CURDATE();
