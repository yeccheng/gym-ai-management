package com.gym.training.service;

import com.gym.training.entity.Exercise;
import com.gym.training.entity.SetLog;
import com.gym.training.entity.WorkoutSession;
import com.gym.training.mapper.ActiveTraineeMapper;
import com.gym.training.mapper.ExerciseMapper;
import com.gym.training.mapper.SetLogMapper;
import com.gym.training.mapper.WorkoutSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 主动提醒与「瞎练」警告。
 *
 * <p>从「工具」变「教练」的关键：不等用户来问，系统自己看数据后推结论。
 * 推送渠道在毕设里用日志模拟（无接短信/APP 推送），答辩时看日志即可证明任务跑了。
 *
 * <p>扫描对象 = 近 90 天有过训练记录的用户。
 * 不直接依赖 gym-system 的 SysUser，避免领域模块反向依赖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoachReminderService {

    private final WorkoutSessionMapper sessionMapper;
    private final SetLogMapper setLogMapper;
    private final ExerciseMapper exerciseMapper;
    private final ActiveTraineeMapper activeTraineeMapper;

    /**
     * 扫描活跃训练用户，产出提醒并打日志。
     * @return 本轮产生的提醒条数
     */
    public int runEveningCheck() {
        List<Long> userIds = activeTraineeMapper.selectActiveUserIds(LocalDate.now().minusDays(90));
        int count = 0;
        for (Long userId : userIds) {
            for (String tip : tipsForUser(userId)) {
                // 毕设验收：后端日志能看到推送任务执行
                log.info("[教练提醒] userId={} → {}", userId, tip);
                count++;
            }
        }
        log.info("[教练提醒] 本轮扫描用户 {} 人，产出提醒 {} 条", userIds.size(), count);
        return count;
    }

    /** 给「我的 → 提醒中心」：当前用户此刻该看的教练提醒 */
    public List<String> tipsForUser(long userId) {
        LocalDate today = LocalDate.now();
        List<String> tips = new ArrayList<>();
        tips.addAll(checkMissedToday(userId, today));
        tips.addAll(checkMuscleImbalance(userId));
        tips.addAll(checkInjuryHeavy(userId));
        return tips;
    }

    /** 今天没训练 → 提醒；并根据最近一次 session_type 猜「计划是练什么」 */
    private List<String> checkMissedToday(long userId, LocalDate today) {
        WorkoutSession todaySession = sessionMapper.selectByUserAndDate(userId, today);
        if (todaySession != null) {
            List<SetLog> sets = setLogMapper.selectBySession(todaySession.getId());
            if (!sets.isEmpty()) {
                return List.of();
            }
        }
        WorkoutSession last = sessionMapper.selectLastBefore(userId, today);
        String planHint = last == null ? "全身" : sessionTypeLabel(last.getSessionType());
        String suggest = nextPart(last == null ? null : last.getSessionType());
        return List.of("今天还没训练，按分化节奏建议练「" + suggest + "」（你上次是 " + planHint + "）");
    }

    /**
     * 连续 3 周某部位训练量占比超过 60% → 胸背失衡类警告。
     * 用组数占比而不是容量，避免大重量复合动作把比例算偏。
     */
    private List<String> checkMuscleImbalance(long userId) {
        LocalDateTime from = LocalDateTime.now().minusWeeks(3);
        List<SetLog> logs = setLogMapper.selectByUserAndRange(userId, from, LocalDateTime.now()).stream()
                .filter(s -> s.getIsWarmup() == null || s.getIsWarmup() == 0)
                .toList();
        if (logs.size() < 20) {
            return List.of();
        }
        Map<Long, Exercise> exercises = exerciseMapper.selectBatchIds(
                logs.stream().map(SetLog::getExerciseId).filter(Objects::nonNull).collect(Collectors.toSet())
        ).stream().collect(Collectors.toMap(Exercise::getId, e -> e, (a, b) -> a));

        Map<String, Integer> byMuscle = new HashMap<>();
        int total = 0;
        for (SetLog s : logs) {
            Exercise ex = exercises.get(s.getExerciseId());
            String m = ex == null || ex.getPrimaryMuscle() == null ? "其他" : ex.getPrimaryMuscle();
            String group = normalizeMuscle(m);
            byMuscle.merge(group, 1, Integer::sum);
            total++;
        }
        List<String> tips = new ArrayList<>();
        for (Map.Entry<String, Integer> e : byMuscle.entrySet()) {
            double ratio = e.getValue() / (double) total;
            if (ratio >= 0.60) {
                tips.add("近 3 周「" + e.getKey() + "」训练组数占比 "
                        + Math.round(ratio * 100) + "%，超过 60%，存在肌群失衡风险，请补练对侧");
            }
        }
        return tips;
    }

    /** 会话备注含膝盖/腰不适，但同日还有大重量正式组 → 建议降重量 */
    private List<String> checkInjuryHeavy(long userId) {
        WorkoutSession today = sessionMapper.selectByUserAndDate(userId, LocalDate.now());
        if (today == null || today.getNote() == null) {
            return List.of();
        }
        String note = today.getNote();
        if (!(note.contains("膝盖") || note.contains("腰") || note.contains("不舒服") || note.contains("疼痛"))) {
            return List.of();
        }
        List<SetLog> sets = setLogMapper.selectBySession(today.getId()).stream()
                .filter(s -> s.getIsWarmup() == null || s.getIsWarmup() == 0)
                .filter(s -> s.getWeightKg() != null)
                .toList();
        boolean heavy = sets.stream().anyMatch(s -> s.getWeightKg().doubleValue() >= 80);
        if (heavy) {
            return List.of("你记录了「" + note + "」但仍在做大重量，建议立刻降到平时的 60%–70%，疼痛未消不要硬顶");
        }
        return List.of();
    }

    private static String normalizeMuscle(String m) {
        if (m.contains("胸")) return "胸";
        if (m.contains("背")) return "背";
        if (m.contains("股") || m.contains("腘") || m.contains("臀") || m.contains("腿")) return "腿";
        if (m.contains("肩")) return "肩";
        if (m.contains("肱")) return "手臂";
        return m;
    }

    private static String sessionTypeLabel(Integer t) {
        if (t == null) return "全身";
        return switch (t) {
            case 1 -> "推";
            case 2 -> "拉";
            case 3 -> "腿";
            case 4 -> "上肢";
            case 5 -> "下肢";
            case 7 -> "有氧";
            default -> "全身";
        };
    }

    private static String nextPart(Integer lastType) {
        if (lastType == null) return "全身";
        return switch (lastType) {
            case 1 -> "拉";
            case 2 -> "腿";
            case 3 -> "推";
            case 4 -> "下肢";
            case 5 -> "上肢";
            default -> "推";
        };
    }

}
