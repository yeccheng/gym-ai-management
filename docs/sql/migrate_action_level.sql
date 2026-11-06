-- P0-1：动作分级，搜「卧推」时基础主动作排前面
-- 可重复执行（列已存在则跳过需手工判断；MySQL 8 无 IF NOT EXISTS 列时用存储过程或忽略报错）

ALTER TABLE exercise
    ADD COLUMN action_level TINYINT NOT NULL DEFAULT 2
        COMMENT '1基础主动作 2变体 3辅助孤立' AFTER difficulty;

-- 基础主动作
UPDATE exercise SET action_level = 1 WHERE name IN (
    '杠铃卧推', '杠铃深蹲', '硬拉', '杠铃肩推', '引体向上', '杠铃划船'
) AND deleted = 0;

-- 变体（明确标 2，覆盖默认）
UPDATE exercise SET action_level = 2 WHERE name IN (
    '哑铃卧推', '上斜杠铃卧推', '下斜杠铃卧推', '前蹲', '罗马尼亚硬拉',
    '哑铃肩推', '高位下拉', '坐姿划船', '单臂哑铃划船', '腿举', '器械推胸', '臀桥'
) AND deleted = 0;

-- 辅助孤立
UPDATE exercise SET action_level = 3 WHERE name IN (
    '哑铃飞鸟', '侧平举', '面拉', '杠铃弯举', '哑铃弯举', '锤式弯举',
    '仰卧臂屈伸', '双杠臂屈伸', '腿弯举', '腿伸展', '平板支撑', '卷腹', '悬垂举腿'
) AND deleted = 0;
