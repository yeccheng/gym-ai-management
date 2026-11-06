package com.gym.web.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.gym.training.entity.Exercise;
import com.gym.training.mapper.ExerciseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时补齐常见动作，并回填 action_level（搜「卧推」时基础动作排前）。
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class ExerciseSeedRunner implements CommandLineRunner {

    private final ExerciseMapper exerciseMapper;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        ensureColumn("exercise", "action_level",
                "ALTER TABLE exercise ADD COLUMN action_level TINYINT NOT NULL DEFAULT 2 "
                        + "COMMENT '1基础主动作 2变体 3辅助孤立' AFTER difficulty");
        boolean isDemoAdded = ensureColumn("workout_session", "is_demo",
                "ALTER TABLE workout_session ADD COLUMN is_demo TINYINT NOT NULL DEFAULT 0 "
                        + "COMMENT '1示例 0真实打卡' AFTER note");
        // 刚加列时：历史会话标成示例；今天的保留为真实
        if (isDemoAdded) {
            try {
                int n = jdbcTemplate.update(
                        "UPDATE workout_session SET is_demo = 1 WHERE workout_date < CURDATE()");
                log.info("已将 {} 条历史会话标为 is_demo=1（今天的保留为真实）", n);
            } catch (Exception e) {
                log.warn("回填 is_demo 失败: {}", e.getMessage());
            }
        }
        // 每次启动兜底：今日会话只要挂过组，一律改回真实——
        // 修复「打卡页有组、我的页 hasRealData=false」的脏数据
        try {
            int fixed = jdbcTemplate.update("""
                    UPDATE workout_session ws
                    SET is_demo = 0
                    WHERE COALESCE(ws.is_demo, 1) <> 0
                      AND ws.workout_date = CURDATE()
                      AND EXISTS (
                        SELECT 1 FROM set_log s WHERE s.session_id = ws.id
                      )
                    """);
            if (fixed > 0) {
                log.info("已将 {} 个「今日有组」的会话纠正为 is_demo=0", fixed);
            }
        } catch (Exception e) {
            log.warn("纠正今日真实会话 is_demo 失败: {}", e.getMessage());
        }
        int added = 0;
        // level 1 基础主动作
        added += ensure("杠铃卧推", "Barbell Bench Press", "卧推,平板卧推,bench", 1, "胸", 1);
        added += ensure("杠铃深蹲", "Barbell Squat", "深蹲,squat", 1, "股四头肌", 1);
        added += ensure("硬拉", "Deadlift", "传统硬拉,deadlift", 1, "腘绳肌", 1);
        added += ensure("杠铃肩推", "Overhead Press", "肩推,推举,OHP", 1, "肩", 1);
        added += ensure("引体向上", "Pull Up", "引体,pullup", 4, "背", 1);
        added += ensure("杠铃划船", "Barbell Row", "划船,俯身划船", 1, "背", 1);
        // level 2 变体
        added += ensure("哑铃卧推", "Dumbbell Bench Press", "哑铃推胸,哑铃卧推", 2, "胸", 2);
        added += ensure("上斜杠铃卧推", "Incline Bench Press", "上斜卧推,上斜推", 1, "胸", 2);
        added += ensure("下斜杠铃卧推", "Decline Bench Press", "下斜卧推", 1, "胸", 2);
        added += ensure("器械推胸", "Chest Press Machine", "推胸器,坐姿推胸", 3, "胸", 2);
        added += ensure("高位下拉", "Lat Pulldown", "下拉,背阔下拉", 3, "背", 2);
        added += ensure("坐姿划船", "Seated Cable Row", "绳索划船", 5, "背", 2);
        added += ensure("单臂哑铃划船", "One Arm Dumbbell Row", "哑铃划船", 2, "背", 2);
        added += ensure("前蹲", "Front Squat", "前蹲,前杠深蹲", 1, "股四头肌", 2);
        added += ensure("罗马尼亚硬拉", "Romanian Deadlift", "RDL,直腿硬拉", 1, "腘绳肌", 2);
        added += ensure("腿举", "Leg Press", "腿推,哈克", 3, "股四头肌", 2);
        added += ensure("哑铃肩推", "Dumbbell Shoulder Press", "哑铃推举,坐姿肩推", 2, "肩", 2);
        added += ensure("臀桥", "Hip Thrust", "臀桥,髋推", 1, "臀", 2);
        // level 3 辅助孤立
        added += ensure("哑铃飞鸟", "Dumbbell Fly", "飞鸟,夹胸", 2, "胸", 3);
        added += ensure("腿弯举", "Leg Curl", "腿弯,腘绳弯举", 3, "腘绳肌", 3);
        added += ensure("腿伸展", "Leg Extension", "腿屈伸,踢腿", 3, "股四头肌", 3);
        added += ensure("侧平举", "Lateral Raise", "侧平举", 2, "肩", 3);
        added += ensure("面拉", "Face Pull", "面拉,绳索面拉", 5, "肩", 3);
        added += ensure("杠铃弯举", "Barbell Curl", "二头弯举,弯举", 1, "肱二头肌", 3);
        added += ensure("哑铃弯举", "Dumbbell Curl", "哑铃弯举,交替弯举", 2, "肱二头肌", 3);
        added += ensure("锤式弯举", "Hammer Curl", "锤式", 2, "肱二头肌", 3);
        added += ensure("仰卧臂屈伸", "Skull Crusher", "仰卧屈伸", 1, "肱三头肌", 3);
        added += ensure("双杠臂屈伸", "Dip", "双杠撑,臂屈伸", 4, "肱三头肌", 3);
        added += ensure("平板支撑", "Plank", "平板,plank", 4, "腹", 3);
        added += ensure("卷腹", "Crunch", "卷腹", 4, "腹", 3);
        added += ensure("悬垂举腿", "Hanging Leg Raise", "悬垂抬腿", 4, "腹", 3);
        backfillLevels();
        int cues = fillCoachingCues();
        if (added > 0) {
            log.info("动作库补齐 {} 个常见动作", added);
        }
        if (cues > 0) {
            log.info("已回填 {} 个动作的要领（步骤/错误/呼吸/安全）", cues);
        }
    }

    /**
     * 给核心动作写上组间能扫一眼的要领。已有 steps 的不覆盖——允许教练以后改库。
     */
    private int fillCoachingCues() {
        int n = 0;
        n += cue("杠铃卧推",
                "肩胛收紧躺稳；握距略宽于肩；杠落到胸中下部后向上推至肘接近伸直。",
                "屁股离凳、手腕折、下放弹胸、肩过度前伸。",
                "下放吸气，推起呼气。",
                "护腕可选；肩膀不适先减重量或改哑铃。");
        n += cue("杠铃深蹲",
                "杠落上斜方肌；脚与肩同宽略外展；髋膝同时屈，蹲至大腿至少平行地面再站起。",
                "膝盖过度内扣、脚跟离地、过度前倾变成早安式。",
                "下放吸气，站起呼气。",
                "腰有伤先练高脚杯或腿举；腰带不能替代核心发力。");
        n += cue("硬拉",
                "杠贴近小腿；髋铰链俯身握杠；腰背中立，用腿和髋把杠拉到站直，肩在杠后。",
                "圆背硬拉、杠离身体太远、用腰硬拽、顶部过度后仰。",
                "发力前吸气憋住核心，过膝后呼气。",
                "从地板拉不动可先做 Romanian / 垫高；腰痛立刻停。");
        n += cue("杠铃肩推",
                "杠置于锁骨前；核心收紧；垂直向上推至肘伸直，下落控制回锁骨。",
                "过度后仰借力、肘过度打开、推到脑后压肩。",
                "推起呼气，下落吸气。",
                "肩峰撞击感改哑铃或减幅度。");
        n += cue("引体向上",
                "正手略宽于肩；肩胛先下沉再拉肘向下，下巴过杠后控制下放。",
                "耸肩借晃、只拉一半、下放自由落体。",
                "上拉呼气，下放吸气。",
                "做不了完整组用弹力带辅助或高位下拉。");
        n += cue("杠铃划船",
                "髋铰链俯身约 30～45°；拉杠至下腹，肘贴近身体，肩胛后收。",
                "靠甩腰借力、圆背、只拉手腕。",
                "拉起呼气，放下吸气。",
                "下背酸先减重量或改器械划船。");
        n += cue("哑铃卧推",
                "肩胛收紧；哑铃落到胸侧，推至臂接近伸直，轨迹略呈弧线。",
                "下放过深压肩、手腕折、两端重量不对称猛起。",
                "推起呼气，下放吸气。",
                "新手先坐姿推胸练稳轨迹。");
        n += cue("罗马尼亚硬拉",
                "微屈膝；杠贴腿向下滑，感受腘绳与臀拉伸，至躯干近平行再髋驱动站起。",
                "膝盖过度弯曲变普通硬拉、圆背、杠离腿。",
                "下放吸气，站起呼气。",
                "腘绳柔韧性差先减小行程。");
        n += cue("侧平举",
                "肘微屈；大臂外展至约与肩平，小拇指略高于拇指，慢下放。",
                "借甩躯干、举过头顶、耸肩代劳。",
                "举起呼气，放下吸气。",
                "肩不适改绳索面拉或减重量。");
        n += cue("腿弯举",
                "俯卧或坐姿固定；在控制下弯曲膝关节，顶峰稍停再慢放。",
                "借髋抬起、重量砸回、行程只有一半。",
                "弯起呼气，放下吸气。",
                "膝痛减重量或改髋铰链腘绳训练。");
        n += cue("面拉",
                "绳索与眼同高；拉向脸侧，外旋前臂，肘高于手，肩胛后收。",
                "变成高位下拉、耸肩、只用手臂拉。",
                "拉回呼气，回放吸气。",
                "肩袖友好动作，可放热身或练后。");
        n += cue("臀桥",
                "上背靠凳、杠或垫落髋；脚跟发力把髋推到肩膝一线，顶峰挤臀。",
                "用腰过度反弓、膝盖外崩或内扣、行程过短。",
                "推起呼气，下放吸气。",
                "下背代偿时减小行程并收肋。");
        return n;
    }

    private int cue(String name, String steps, String mistakes, String breathing, String safety) {
        Exercise e = exerciseMapper.selectByName(name);
        if (e == null) {
            return 0;
        }
        if (e.getSteps() != null && !e.getSteps().isBlank()) {
            return 0;
        }
        exerciseMapper.update(null, new LambdaUpdateWrapper<Exercise>()
                .eq(Exercise::getId, e.getId())
                .set(Exercise::getSteps, steps)
                .set(Exercise::getCommonMistakes, mistakes)
                .set(Exercise::getBreathing, breathing)
                .set(Exercise::getSafetyTips, safety));
        return 1;
    }

    /** @return true 表示本次新加了列 */
    private boolean ensureColumn(String table, String column, String alterSql) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    """
                            SELECT COUNT(*) FROM information_schema.COLUMNS
                            WHERE TABLE_SCHEMA = DATABASE()
                              AND TABLE_NAME = ?
                              AND COLUMN_NAME = ?
                            """,
                    Integer.class, table, column);
            if (n != null && n == 0) {
                jdbcTemplate.execute(alterSql);
                log.info("已自动添加 {}.{} 列", table, column);
                return true;
            }
        } catch (Exception e) {
            log.warn("检查/添加 {}.{} 失败（可手动执行 docs/sql/migrate_*.sql）: {}",
                    table, column, e.getMessage());
        }
        return false;
    }

    private void backfillLevels() {
        setLevel(1, "杠铃卧推", "杠铃深蹲", "硬拉", "杠铃肩推", "引体向上", "杠铃划船");
        setLevel(2, "哑铃卧推", "上斜杠铃卧推", "下斜杠铃卧推", "前蹲", "罗马尼亚硬拉",
                "哑铃肩推", "高位下拉", "坐姿划船", "单臂哑铃划船", "腿举", "器械推胸", "臀桥");
        setLevel(3, "哑铃飞鸟", "侧平举", "面拉", "杠铃弯举", "哑铃弯举", "锤式弯举",
                "仰卧臂屈伸", "双杠臂屈伸", "腿弯举", "腿伸展", "平板支撑", "卷腹", "悬垂举腿");
    }

    private void setLevel(int level, String... names) {
        for (String name : names) {
            exerciseMapper.update(null, new LambdaUpdateWrapper<Exercise>()
                    .eq(Exercise::getName, name)
                    .set(Exercise::getActionLevel, level));
        }
    }

    private int ensure(String name, String nameEn, String alias, int equipment,
                       String muscle, int actionLevel) {
        Long cnt = exerciseMapper.selectCount(new LambdaQueryWrapper<Exercise>()
                .eq(Exercise::getName, name));
        if (cnt != null && cnt > 0) {
            return 0;
        }
        Exercise e = new Exercise();
        e.setName(name);
        e.setNameEn(nameEn);
        e.setAlias(alias);
        e.setEquipment(equipment);
        e.setMovementType(actionLevel == 3 ? 2 : 1);
        e.setPrimaryMuscle(muscle);
        e.setDifficulty(2);
        e.setActionLevel(actionLevel);
        e.setDeleted(0);
        exerciseMapper.insert(e);
        return 1;
    }
}
