-- 定点恢复被测试脚本误删的种子数据（不 DROP 任何表，避免误伤 member/coach/equipment）
-- 目标：恢复 course id=1、membership_card（叶程季卡次卡），并清理指向已删课程的孤儿预约

-- 0) 清理孤儿预约（原 course_id=1 已删除，留下 status=3 的取消记录，演示时易混淆）
DELETE FROM booking WHERE course_id = 1 AND status = 3;

-- 1) 恢复课程 id=1 力量基础课（coach_id=1 王教练，容量 8，明天开课，A教室）
INSERT INTO course (id, name, coach_id, capacity, booked_cnt, course_time, duration_min, room, status)
VALUES (1, '力量基础课', 1, 8, 0, DATE_ADD(NOW(), INTERVAL 1 DAY), 60, 'A教室', 1);

-- 2) 恢复叶程的季卡次卡（演示「签到消次」）
INSERT INTO membership_card (member_id, card_name, card_type, total_count, remain_count, start_date, end_date, price, status)
VALUES (1, '季卡次卡', 1, 30, 30, CURDATE(), DATE_ADD(CURDATE(), INTERVAL 90 DAY), 999.00, 1);
