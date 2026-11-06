-- 补齐常见动作（已存在同名则跳过）
INSERT INTO exercise (name, name_en, alias, equipment, movement_type, movement_pattern,
                      primary_muscle, secondary_muscle, difficulty, deleted)
SELECT * FROM (
  SELECT '上斜杠铃卧推' AS name, 'Incline Bench Press' AS name_en, '上斜卧推,上斜推' AS alias, 1 AS equipment, 1 AS movement_type, 1 AS movement_pattern, '胸' AS primary_muscle, '肩,肱三' AS secondary_muscle, 2 AS difficulty, 0 AS deleted
  UNION ALL SELECT '下斜杠铃卧推', 'Decline Bench Press', '下斜卧推', 1, 1, 1, '胸', '肱三', 2, 0
  UNION ALL SELECT '哑铃飞鸟', 'Dumbbell Fly', '飞鸟,夹胸', 2, 2, 1, '胸', '', 1, 0
  UNION ALL SELECT '器械推胸', 'Chest Press Machine', '推胸器,坐姿推胸', 3, 1, 1, '胸', '肱三', 1, 0
  UNION ALL SELECT '高位下拉', 'Lat Pulldown', '下拉,背阔下拉', 3, 1, 2, '背', '肱二', 1, 0
  UNION ALL SELECT '坐姿划船', 'Seated Cable Row', '绳索划船,坐姿划船', 5, 1, 2, '背', '肱二', 1, 0
  UNION ALL SELECT '单臂哑铃划船', 'One Arm Dumbbell Row', '哑铃划船', 2, 1, 2, '背', '肱二', 1, 0
  UNION ALL SELECT '前蹲', 'Front Squat', '前蹲,前杠深蹲', 1, 1, 3, '股四头肌', '核心,臀', 3, 0
  UNION ALL SELECT '罗马尼亚硬拉', 'Romanian Deadlift', 'RDL,直腿硬拉', 1, 1, 4, '腘绳肌', '臀,背', 2, 0
  UNION ALL SELECT '腿弯举', 'Leg Curl', '腿弯,腘绳弯举', 3, 2, 4, '腘绳肌', '', 1, 0
  UNION ALL SELECT '腿伸展', 'Leg Extension', '腿屈伸,踢腿', 3, 2, 3, '股四头肌', '', 1, 0
  UNION ALL SELECT '哑铃肩推', 'Dumbbell Shoulder Press', '哑铃推举,坐姿肩推', 2, 1, 1, '肩', '肱三', 1, 0
  UNION ALL SELECT '侧平举', 'Lateral Raise', '侧平举,飞鸟肩', 2, 2, 1, '肩', '', 1, 0
  UNION ALL SELECT '面拉', 'Face Pull', '面拉,绳索面拉', 5, 2, 2, '肩', '背', 1, 0
  UNION ALL SELECT '哑铃弯举', 'Dumbbell Curl', '哑铃弯举,交替弯举', 2, 2, 2, '肱二头肌', '前臂', 1, 0
  UNION ALL SELECT '锤式弯举', 'Hammer Curl', '锤式,中立弯举', 2, 2, 2, '肱二头肌', '前臂', 1, 0
  UNION ALL SELECT '仰卧臂屈伸', 'Skull Crusher', '仰卧屈伸,法式推举', 1, 2, 1, '肱三头肌', '', 2, 0
  UNION ALL SELECT '双杠臂屈伸', 'Dip', '双杠撑,臂屈伸', 4, 1, 1, '肱三头肌', '胸,肩', 2, 0
  UNION ALL SELECT '平板支撑', 'Plank', '平板,plank', 4, 2, 5, '腹', '核心', 1, 0
  UNION ALL SELECT '卷腹', 'Crunch', '卷腹,仰卧起坐', 4, 2, 5, '腹', '', 1, 0
  UNION ALL SELECT '悬垂举腿', 'Hanging Leg Raise', '悬垂抬腿', 4, 2, 5, '腹', '', 2, 0
  UNION ALL SELECT '臀桥', 'Hip Thrust', '臀桥,髋推', 1, 1, 4, '臀', '腘绳', 1, 0
  UNION ALL SELECT '农夫行走', 'Farmer Walk', '农夫走', 6, 1, 6, '核心', '前臂,斜方', 1, 0
) AS t
WHERE NOT EXISTS (SELECT 1 FROM exercise e WHERE e.name = t.name AND e.deleted = 0);
