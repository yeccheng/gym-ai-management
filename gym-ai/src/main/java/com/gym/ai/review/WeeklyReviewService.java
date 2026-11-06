package com.gym.ai.review;

import com.gym.ai.autoconfigure.GymAiTemplate;
import com.gym.training.entity.Exercise;
import com.gym.training.entity.SetLog;
import com.gym.training.mapper.ExerciseMapper;
import com.gym.training.mapper.SetLogMapper;
import com.gym.training.metric.TrainMetrics;
import com.gym.training.metric.TrainMetrics.MuscleGroup;
import com.gym.training.metric.TrainMetrics.VolumeVerdict;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 周报复盘——AI 落点之一。
 *
 * <p>核心设计：<b>事实与判断分离。</b>
 * 容量、肌群组数、e1RM 变化、平台期判定全部由 TrainMetrics 确定性算出，
 * 组装成结构化文本后再交给模型，模型的职责只有「解读 + 给建议」。
 *
 * <p>为什么不让模型自己看原始记录：它不会算。
 * 让大模型去数「本周胸练了几组」，等于把算术交给会产生幻觉的组件，
 * 得出的数字再漂亮也没有意义。
 */
@Service
@RequiredArgsConstructor
public class WeeklyReviewService {

    private final SetLogMapper setLogMapper;
    private final ExerciseMapper exerciseMapper;
    private final GymAiTemplate gymAiTemplate;

    public record LiftTrend(String exerciseName,
                            double startE1rm,
                            double endE1rm,
                            double growthPct,
                            boolean plateau) {
    }

    public record WeeklyFacts(int workingSetCount,
                              double totalVolume,
                              Map<String, Integer> muscleSets,
                              Map<String, VolumeVerdict> muscleVerdicts,
                              List<LiftTrend> liftTrends) {
    }

    /** 生成周报。先算事实，再让模型解读 */
    public String review(Long userId, LocalDate weekStart) {
        LocalDateTime from = weekStart.atStartOfDay();
        LocalDateTime to = from.plusWeeks(1);
        List<SetLog> logs = setLogMapper.selectByUserAndRange(userId, from, to);
        if (logs == null || logs.isEmpty()) {
            return "本周没有训练记录，无法生成复盘。";
        }
        return gymAiTemplate.chat(buildPrompt(compute(logs)));
    }

    /**
     * 流式版周报，供异步任务的 SSE 推送使用。
     * 与同步版的区别只有「结果怎么回去」，事实计算完全同一条路径——
     * 保证两种调用方式给出的结论一致。
     */
    public Flux<String> streamReview(Long userId, LocalDate weekStart) {
        LocalDateTime from = weekStart.atStartOfDay();
        LocalDateTime to = from.plusWeeks(1);
        List<SetLog> logs = setLogMapper.selectByUserAndRange(userId, from, to);
        if (logs == null || logs.isEmpty()) {
            return Flux.just("本周没有训练记录，无法生成复盘。");
        }
        return gymAiTemplate.stream(buildPrompt(compute(logs)));
    }

    /** 不经过模型的纯计算，可单独测试 */
    public WeeklyFacts compute(List<SetLog> logs) {
        Map<Long, Exercise> exerciseMap = loadExercises(logs);

        double volume = 0;
        int workingSetCount = 0;
        Map<String, Integer> muscleSets = new LinkedHashMap<>();
        // exerciseId -> (sessionId -> 该次训练的最大 e1RM)
        Map<Long, TreeMap<Long, Double>> maxE1rmBySession = new HashMap<>();

        // 第一遍先建立每个动作的 e1RM 基准。
        // 有效组判定拿本组自算的 e1RM 当分母会退化成恒真（详见 TrainMetrics.volume 注释），
        // 必须用这个人在这个动作上的历史最好成绩。
        Map<Long, Double> e1rmBaseline = new HashMap<>();
        for (SetLog log : logs) {
            if (isWarmup(log)) {
                continue;
            }
            e1rmBaseline.merge(log.getExerciseId(),
                    TrainMetrics.e1rm(nvl(log.getWeightKg()),
                            log.getReps() == null ? 0 : log.getReps(),
                            log.getRpe() == null ? null : log.getRpe().doubleValue()),
                    Math::max);
        }

        for (SetLog log : logs) {
            if (isWarmup(log)) {
                continue;
            }
            double weight = nvl(log.getWeightKg());
            int reps = log.getReps() == null ? 0 : log.getReps();
            Double rpe = log.getRpe() == null ? null : log.getRpe().doubleValue();
            double e1rm = TrainMetrics.e1rm(weight, reps, rpe);

            boolean working = TrainMetrics.isWorkingSet(weight, rpe,
                    e1rmBaseline.getOrDefault(log.getExerciseId(), e1rm));
            if (working) {
                workingSetCount++;
                volume += weight * reps;
                Exercise ex = exerciseMap.get(log.getExerciseId());
                if (ex != null && ex.getPrimaryMuscle() != null) {
                    muscleSets.merge(ex.getPrimaryMuscle(), 1, Integer::sum);
                }
            }
            if (e1rm > 0 && log.getSessionId() != null) {
                maxE1rmBySession
                        .computeIfAbsent(log.getExerciseId(), k -> new TreeMap<>())
                        .merge(log.getSessionId(), e1rm, Math::max);
            }
        }

        Map<String, VolumeVerdict> verdicts = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : muscleSets.entrySet()) {
            MuscleGroup group = MuscleGroup.ofLabel(entry.getKey());
            if (group != null) {
                verdicts.put(entry.getKey(), TrainMetrics.verdict(group, entry.getValue()));
            }
        }

        return new WeeklyFacts(workingSetCount, volume, muscleSets, verdicts,
                buildTrends(maxE1rmBySession, exerciseMap));
    }

    private List<LiftTrend> buildTrends(Map<Long, TreeMap<Long, Double>> maxE1rmBySession,
                                        Map<Long, Exercise> exerciseMap) {
        List<LiftTrend> trends = new ArrayList<>();
        for (Map.Entry<Long, TreeMap<Long, Double>> entry : maxE1rmBySession.entrySet()) {
            List<Double> series = new ArrayList<>(entry.getValue().values());
            if (series.size() < 2) {
                continue;
            }
            double start = series.get(0);
            double end = series.get(series.size() - 1);
            double growthPct = start > 0 ? (end - start) / start * 100 : 0;
            Exercise ex = exerciseMap.get(entry.getKey());
            boolean plateau = TrainMetrics.isPlateau(series);
            if (!plateau && Math.abs(growthPct) < 0.5 && series.size() < 3) {
                continue;
            }
            trends.add(new LiftTrend(ex == null ? "未知动作" : ex.getName(),
                    start, end, growthPct, plateau));
        }
        trends.sort((a, b) -> Boolean.compare(b.plateau(), a.plateau()));
        return trends;
    }

    /**
     * 把事实组装成结构化 Prompt。
     * 关键在于开头那句「数值准确，不要重新计算」——
     * 明确告诉模型这部分是事实，把它从「计算者」降级为「解读」。
     */
    private String buildPrompt(WeeklyFacts facts) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("以下是用户本周的真实训练数据，由系统统计得出，数值准确，你不需要也不应该重新计算。\n\n");

        sb.append("【总览】有效组 ").append(facts.workingSetCount())
                .append(" 组，总容量 ").append(String.format("%.0f", facts.totalVolume())).append(" kg\n\n");

        sb.append("【各肌群有效组数】\n");
        for (Map.Entry<String, Integer> entry : facts.muscleSets().entrySet()) {
            VolumeVerdict verdict = facts.muscleVerdicts().get(entry.getKey());
            sb.append("- ").append(entry.getKey()).append("：").append(entry.getValue()).append(" 组");
            if (verdict != null) {
                sb.append("，").append(verdict.advice());
            }
            sb.append('\n');
        }

        sb.append("\n【主要动作 e1RM 变化】\n");
        if (facts.liftTrends().isEmpty()) {
            sb.append("- 本周没有足够的历史数据\n");
        }
        for (LiftTrend t : facts.liftTrends()) {
            sb.append("- ").append(t.exerciseName()).append("：")
                    .append(String.format("%.1f", t.startE1rm())).append(" → ")
                    .append(String.format("%.1f", t.endE1rm())).append(" kg");
            if (t.plateau()) {
                sb.append("（连续多次无增长，已判定平台期）");
            }
            sb.append('\n');
        }

        sb.append("\n请基于以上事实输出周报复盘，要求：\n");
        sb.append("1. 只做评价与建议，不要复述上面的数据\n");
        sb.append("2. 肌群组数不足或过量时必须点名，并给出「加几个动作」的具体建议\n");
        sb.append("3. 存在平台期时，给出破局手段：换变式动作、调整次数区间、安排减载周\n");
        sb.append("4. 不要建议需要特定器械才能执行的方案，除非数据里已出现该器械\n");
        sb.append("5. 控制在 300 字以内，语言直接，不要客套");
        return sb.toString();
    }

    private Map<Long, Exercise> loadExercises(List<SetLog> logs) {
        Set<Long> ids = logs.stream().map(SetLog::getExerciseId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return exerciseMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Exercise::getId, e -> e));
    }

    private double nvl(BigDecimal value) {
        return value == null ? 0 : value.doubleValue();
    }

    private boolean isWarmup(SetLog log) {
        return log.getIsWarmup() != null && log.getIsWarmup() == 1;
    }
}
