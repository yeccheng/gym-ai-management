package com.gym.training.demo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gym.training.demo.DemoDataPlanner.BodyRecordPoint;
import com.gym.training.demo.DemoDataPlanner.PlannedSession;
import com.gym.training.demo.DemoDataPlanner.PlannedSet;
import com.gym.training.demo.DemoDataPlanner.Plan;
import com.gym.training.entity.BodyRecord;
import com.gym.training.entity.Exercise;
import com.gym.training.entity.SetLog;
import com.gym.training.entity.WorkoutSession;
import com.gym.training.mapper.BodyRecordMapper;
import com.gym.training.mapper.ExerciseMapper;
import com.gym.training.mapper.SetLogMapper;
import com.gym.training.mapper.WorkoutSessionMapper;
import com.gym.training.metric.TrainMetrics;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示数据落库器。
 *
 * <p>它只做一件事：把 {@link DemoDataPlanner} 编排好的剧本翻译成数据库记录。
 * 所有业务判断都已经在编排器里做完了，这里没有第二个版本的算法——
 * <b>e1RM、有效组、容量全部走生产路径的 {@link TrainMetrics}，不在这里重写一遍。</b>
 * 演示数据最常见的翻车方式，就是生成时用一套简化的算法，
 * 结果库里的数字和分析时算出来的对不上。
 *
 * <p>落库前先跑 {@link DemoDataPlanner#verify}。自检不过就不写库——
 * 宁可启动失败，也不要灌一份演示时会答非所问的数据。
 */
@Service
public class DemoDataGenerator {

    /** 分批大小。越过 max_allowed_packet 会让整批失败，且报错看不出是包太大 */
    private static final int BATCH_SIZE = 200;

    private final ExerciseMapper exerciseMapper;
    private final WorkoutSessionMapper sessionMapper;
    private final SetLogMapper setLogMapper;
    private final BodyRecordMapper bodyRecordMapper;

    public DemoDataGenerator(ExerciseMapper exerciseMapper,
                             WorkoutSessionMapper sessionMapper,
                             SetLogMapper setLogMapper,
                             BodyRecordMapper bodyRecordMapper) {
        this.exerciseMapper = exerciseMapper;
        this.sessionMapper = sessionMapper;
        this.setLogMapper = setLogMapper;
        this.bodyRecordMapper = bodyRecordMapper;
    }

    public record DemoResult(int sessionCount, int setCount, int bodyRecordCount,
                             List<String> highlights) {
    }

    public DemoResult generate(long userId) {
        return generate(DemoDataPlanner.Config.of(userId));
    }

    public DemoResult generate(DemoDataPlanner.Config config) {
        Plan plan = DemoDataPlanner.plan(config);

        DemoDataPlanner.SelfCheck check = DemoDataPlanner.verify(plan);
        if (!check.pass()) {
            throw new IllegalStateException("演示数据自检未通过：" + String.join("；", check.problems()));
        }

        Map<String, Exercise> exercises = loadExercises();
        Map<Long, Double> e1rmBaseline = buildBaseline(plan, exercises);
        Map<LocalDate, Double> weightByDate = new HashMap<>();
        for (BodyRecordPoint p : plan.bodyRecords()) {
            weightByDate.put(p.date(), p.weightKg());
        }

        List<SetLog> pending = new ArrayList<>();
        // 动作键 -> 目前见过的最好 e1RM，用于标记个人记录
        Map<String, Double> bestSoFar = new HashMap<>();
        int bodyCount = 0;

        for (PlannedSession ps : plan.sessions()) {
            double volume = TrainMetrics.volume(recordsOf(ps, exercises), e1rmBaseline);
            LocalDateTime start = LocalDateTime.of(ps.date(), ps.startTime());

            WorkoutSession ws = new WorkoutSession();
            ws.setUserId(config.userId());
            ws.setWorkoutDate(ps.date());
            ws.setSessionType(ps.sessionType());
            ws.setStartTime(start);
            ws.setEndTime(start.plusMinutes(ps.durationMin()));
            ws.setDurationMin(ps.durationMin());
            ws.setReadinessScore(ps.readinessScore());
            ws.setSleepHours(BigDecimal.valueOf(ps.sleepHours()));
            Double bw = weightByDate.get(ps.date()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
            if (bw != null) {
                ws.setBodyWeightKg(BigDecimal.valueOf(bw));
            }
            // 总容量是确定性计算的结果，不交给大模型
            ws.setTotalVolume(BigDecimal.valueOf(round1(volume)));
            ws.setNote(ps.note());
            // 样板可单独 clear；曲线 / AI 仍计入该用户（见 SetLogMapper 注释）
            ws.setIsDemo(1);
            ws.setDeleted(0);
            ws.setCreateTime(start);
            ws.setUpdateTime(start);
            sessionMapper.insert(ws);

            LocalDateTime performed = start;
            int index = 1;
            for (PlannedSet pset : ps.sets()) {
                Exercise ex = exercises.get(pset.exerciseKey());
                double e1rm = TrainMetrics.e1rm(pset.weightKg(), pset.reps(), pset.rpe());

                SetLog log = new SetLog();
                log.setSessionId(ws.getId());
                log.setUserId(config.userId());
                log.setExerciseId(ex.getId());
                log.setSetIndex(index++);
                log.setReps(pset.reps());
                log.setWeightKg(BigDecimal.valueOf(pset.weightKg()));
                log.setRpe(BigDecimal.valueOf(pset.rpe()));
                log.setIsWarmup(pset.warmup() ? 1 : 0);
                log.setIsWorkingSet(pset.warmup() ? 0 : isWorking(pset, ex, e1rmBaseline));
                log.setE1rm(BigDecimal.valueOf(round1(e1rm)));
                log.setIsPr(newRecord(pset, ex, e1rm, bestSoFar));
                log.setTempo("2-0-2-0");
                log.setRestSec(pset.restSec());
                log.setPerformedAt(performed);
                // 历史数据的录入时间就是发生时间，不要落成 now——
                // 否则全部记录挤在今天，按时间窗口的查询会把半年数据当成一天的数据
                log.setCreateTime(performed);
                performed = performed.plusMinutes(3);
                pending.add(log);
            }
            if (pending.size() >= BATCH_SIZE) {
                flush(pending);
            }
        }
        flush(pending);

        for (BodyRecordPoint p : plan.bodyRecords()) {
            BodyRecord br = new BodyRecord();
            br.setUserId(config.userId());
            br.setRecordDate(p.date());
            br.setWeightKg(BigDecimal.valueOf(p.weightKg()));
            br.setBodyFatPct(BigDecimal.valueOf(p.bodyFatPct()));
            br.setSkeletalMuscleKg(BigDecimal.valueOf(p.skeletalMuscleKg()));
            br.setCreateTime(p.date().atStartOfDay());
            bodyRecordMapper.insert(br);
            bodyCount++;
        }

        int setCount = 0;
        for (PlannedSession ps : plan.sessions()) {
            setCount += ps.sets().size();
        }
        return new DemoResult(plan.sessions().size(), setCount, bodyCount, plan.highlights());
    }

    /**
     * 只清示例数据（is_demo=1），不动用户真实打卡。
     * 旧版 clear 会把用户刚记的 82.5×5 一起删掉，这里改掉。
     */
    public void clear(long userId) {
        List<WorkoutSession> demos = sessionMapper.selectList(new LambdaQueryWrapper<WorkoutSession>()
                .eq(WorkoutSession::getUserId, userId)
                .eq(WorkoutSession::getIsDemo, 1));
        for (WorkoutSession ws : demos) {
            setLogMapper.delete(new LambdaQueryWrapper<SetLog>().eq(SetLog::getSessionId, ws.getId()));
            sessionMapper.deleteById(ws.getId());
        }
        // 体重样板仍随演示清掉（无 is_demo 字段，整表按用户清；真实打卡一般不靠这张表）
        if (!demos.isEmpty()) {
            bodyRecordMapper.delete(new LambdaQueryWrapper<BodyRecord>()
                    .eq(BodyRecord::getUserId, userId));
        }
    }

    /**
     * 答辩护栏：清掉会冲垮「卧推/肩推平台期」剧本的近期真实组。
     * 否则页面显示「刚破 13 周平台」，剧本却说还在卡住——一问就翻车。
     */
    public int clearStoryBreakingRealSets(long userId) {
        List<String> storyLifts = List.of(
                DemoDataPlanner.BENCH, DemoDataPlanner.OHP,
                "杠铃卧推", "杠铃肩推");
        List<Long> exerciseIds = new ArrayList<>();
        for (String name : storyLifts) {
            Exercise ex = exerciseMapper.selectByName(name);
            if (ex != null) {
                exerciseIds.add(ex.getId());
            }
        }
        if (exerciseIds.isEmpty()) {
            return 0;
        }
        LocalDateTime since = LocalDate.now().minusDays(21).atStartOfDay();
        List<WorkoutSession> realSessions = sessionMapper.selectList(new LambdaQueryWrapper<WorkoutSession>()
                .eq(WorkoutSession::getUserId, userId)
                .and(w -> w.eq(WorkoutSession::getIsDemo, 0).or().isNull(WorkoutSession::getIsDemo)));
        int removed = 0;
        for (WorkoutSession ws : realSessions) {
            if (ws.getId() == null) {
                continue;
            }
            // 只动近 21 天真实组，更早的真实历史留给用户
            if (ws.getWorkoutDate() != null && ws.getWorkoutDate().isBefore(since.toLocalDate())) {
                continue;
            }
            removed += setLogMapper.delete(new LambdaQueryWrapper<SetLog>()
                    .eq(SetLog::getSessionId, ws.getId())
                    .in(SetLog::getExerciseId, exerciseIds)
                    .ge(SetLog::getPerformedAt, since));
        }
        return removed;
    }

    /** 是否有任意会话（含示例），演示页自检用 */
    public boolean hasData(long userId) {
        return sessionMapper.selectCount(new LambdaQueryWrapper<WorkoutSession>()
                .eq(WorkoutSession::getUserId, userId)) > 0;
    }

    /** 是否已有样板历史；启动灌数看这个，不能因为今天真实打卡了就跳过半年剧本 */
    public boolean hasDemoData(long userId) {
        return sessionMapper.countDemoSessions(userId) > 0;
    }

    /** 是否有真实打卡（is_demo=0） */
    public boolean hasRealData(long userId) {
        return sessionMapper.countRealSessions(userId) > 0;
    }

    // ==================== 内部 ====================

    /**
     * 建立每个动作的 e1RM 基准。必须先于落库建立——
     * 有效组判定要拿它当分母，而它来自全量数据，边插边算会算出中间态。
     */
    private Map<Long, Double> buildBaseline(Plan plan, Map<String, Exercise> exercises) {
        List<TrainMetrics.SetRecord> records = new ArrayList<>();
        for (PlannedSession ps : plan.sessions()) {
            records.addAll(recordsOf(ps, exercises));
        }
        return TrainMetrics.e1rmBaseline(records);
    }

    private List<TrainMetrics.SetRecord> recordsOf(PlannedSession ps,
                                                   Map<String, Exercise> exercises) {
        List<TrainMetrics.SetRecord> records = new ArrayList<>();
        for (PlannedSet pset : ps.sets()) {
            if (pset.warmup()) {
                continue;
            }
            Exercise ex = exercises.get(pset.exerciseKey());
            records.add(new TrainMetrics.SetRecord(ex.getId(), ex.getPrimaryMuscle(),
                    pset.weightKg(), pset.reps(), pset.rpe(), false));
        }
        return records;
    }

    private int isWorking(PlannedSet pset, Exercise ex, Map<Long, Double> baseline) {
        return TrainMetrics.isWorkingSet(pset.weightKg(), pset.rpe(),
                baseline.getOrDefault(ex.getId(), 0.0)) ? 1 : 0;
    }

    private int newRecord(PlannedSet pset, Exercise ex, double e1rm,
                          Map<String, Double> bestSoFar) {
        if (pset.warmup()) {
            return 0;
        }
        double best = bestSoFar.getOrDefault(ex.getName(), 0.0);
        if (e1rm <= best + 0.01) {
            return 0;
        }
        bestSoFar.put(ex.getName(), e1rm);
        return 1;
    }

    private void flush(List<SetLog> buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        setLogMapper.insertBatch(buffer);
        buffer.clear();
    }

    /**
     * 载入剧本用到的十个动作。
     *
     * <p>顺带校验编排器里的肌群映射没有和数据库漂移——
     * 演示数据工具里有一份硬编码的映射是必要的代价，
     * 但必须有一道关卡保证它和真值一致，否则自检过的结论在真库上不成立。
     */
    private Map<String, Exercise> loadExercises() {
        Map<String, Exercise> result = new LinkedHashMap<>();
        for (String key : DemoDataPlanner.MUSCLE.keySet()) {
            Exercise ex = exerciseMapper.selectByName(key);
            if (ex == null) {
                throw new IllegalStateException(
                        "动作库缺少「" + key + "」，请先执行 docs/sql/schema.sql 的种子数据");
            }
            String expected = DemoDataPlanner.MUSCLE.get(key);
            if (expected != null && !expected.equals(ex.getPrimaryMuscle())) {
                throw new IllegalStateException("动作「" + key + "」的主肌群在库中是「"
                        + ex.getPrimaryMuscle() + "」，与演示剧本记录的「" + expected + "」不一致");
            }
            result.put(key, ex);
        }
        return result;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
