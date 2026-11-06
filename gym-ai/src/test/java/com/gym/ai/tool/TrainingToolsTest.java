package com.gym.ai.tool;

import com.gym.common.security.UserContext;
import com.gym.training.entity.Exercise;
import com.gym.training.entity.SetLog;
import com.gym.training.entity.WorkoutSession;
import com.gym.training.mapper.BodyRecordMapper;
import com.gym.training.mapper.DietLogMapper;
import com.gym.training.mapper.ExerciseMapper;
import com.gym.training.mapper.SetLogMapper;
import com.gym.training.mapper.WorkoutSessionMapper;
import com.gym.training.metric.TrainMetrics.MuscleGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 工具集的行为测试。
 *
 * <p>这里最值钱的是第 1 组断言（安全）和第 3 组（数据不足要明说）：
 * 前者把「userId 不能进工具入参」这条决策用测试锁死，
 * 谁以后为了省事加一个 {@code @ToolParam long userId}，测试立刻变红；
 * 后者保证模型在样本不够时收到的是「数据不足」，而不是一条编出来的趋势。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("智能体工具集")
class TrainingToolsTest {

    private static final long USER_ID = 1001L;
    private static final long BENCH_ID = 1L;
    private static final String BENCH_NAME = "杠铃卧推";

    @Mock
    private SetLogMapper setLogMapper;
    @Mock
    private ExerciseMapper exerciseMapper;
    @Mock
    private WorkoutSessionMapper sessionMapper;
    @Mock
    private BodyRecordMapper bodyRecordMapper;
    @Mock
    private DietLogMapper dietLogMapper;

    private TrainingTools tools;

    @BeforeEach
    void setUp() {
        tools = new TrainingTools(setLogMapper, exerciseMapper, sessionMapper, bodyRecordMapper, dietLogMapper);
        UserContext.setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ==================== 第一组：安全边界 ====================

    @Test
    @DisplayName("没有绑定用户时工具拒绝执行，而不是静默查出一个空结果")
    void 没有用户上下文时拒绝执行() {
        UserContext.clear();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> tools.getMuscleVolume(4));
        assertTrue(ex.getMessage().contains("没有绑定用户"),
                "工具宁可抛异常也不能在无身份状态下查库");
    }

    @Test
    @DisplayName("任何工具的入参里都不允许出现用户标识")
    void 工具入参不得暴露用户标识() {
        List<String> offenders = new ArrayList<>();
        for (Method method : TrainingTools.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }
            for (Parameter param : method.getParameters()) {
                ToolParam annotation = param.getAnnotation(ToolParam.class);
                if (annotation == null) {
                    continue;
                }
                if (annotation.description().contains("用户ID")
                        || param.getName().toLowerCase().contains("userid")) {
                    offenders.add(method.getName() + "." + param.getName());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "这些工具把用户标识暴露成了模型可见的入参，存在跨用户越权风险：" + offenders);
    }

    // ==================== 第二组：动作名匹配 ====================

    @Test
    @DisplayName("用户说「卧推」要能命中库里的「杠铃卧推」")
    void 口语化动作名命中标准名() {
        when(exerciseMapper.searchByName("卧推", 5)).thenReturn(List.of(bench()));
        when(setLogMapper.selectByUserAndRange(anyLong(), any(), any()))
                .thenReturn(progressLogs());

        TrainingTools.StrengthTrend trend = tools.getStrengthTrend("卧推", 8);

        assertTrue(trend.available());
        assertEquals(BENCH_NAME, trend.exercise(), "口语名必须映射到标准名，否则查不到数据");
    }

    @Test
    @DisplayName("匹配不到动作时明说找不到，不返回一条假装的趋势")
    void 动作名匹配不到时不硬凑结论() {
        when(exerciseMapper.searchByName("飞鸟", 5)).thenReturn(List.of());

        TrainingTools.StrengthTrend trend = tools.getStrengthTrend("飞鸟", 8);

        assertFalse(trend.available());
        assertTrue(trend.reason().contains("飞鸟"), "原因要带上关键词，模型才知道是哪个动作没找到");
        assertTrue(trend.e1rmSeries().isEmpty(), "没数据就绝不能返回一条空以外的趋势");
    }

    // ==================== 第三组：趋势计算 ====================

    @Test
    @DisplayName("每次训练只取当天最好的一组，后面的掉重组不能拉低趋势")
    void 每次训练只取最好的一组() {
        when(exerciseMapper.searchByName("卧推", 5)).thenReturn(List.of(bench()));
        LocalDateTime day1 = LocalDateTime.now().minusDays(14);
        LocalDateTime day2 = LocalDateTime.now().minusDays(11);
        when(setLogMapper.selectByUserAndRange(anyLong(), any(), any())).thenReturn(List.of(
                set(10L, day1, 60, 8, 9.0),
                set(10L, day1, 55, 8, 9.0),
                set(11L, day2, 62, 8, 9.0),
                set(11L, day2, 57, 8, 9.0)));

        TrainingTools.StrengthTrend trend = tools.getStrengthTrend("卧推", 8);

        // 60×(1+9/30)=78，62×(1+9/30)=80.6；若把掉重组也算进去会得到 66.5 和 68.9
        assertEquals(List.of(78.0, 80.6), trend.e1rmSeries(),
                "取当天最好一组，掉重组是疲劳产物，混算会把进步误判成退步");
    }

    @Test
    @DisplayName("稳步进步不能被误判成平台期")
    void 稳步进步不判平台期() {
        when(exerciseMapper.searchByName("卧推", 5)).thenReturn(List.of(bench()));
        when(setLogMapper.selectByUserAndRange(anyLong(), any(), any())).thenReturn(progressLogs());

        TrainingTools.StrengthTrend trend = tools.getStrengthTrend("卧推", 8);

        assertTrue(trend.available());
        assertEquals(3, trend.sessionCount());
        assertFalse(trend.plateau(), "三次训练涨了 8%，判成平台期会让用户去做无谓的减载");
        assertTrue(trend.growthPct() > 8.0);
        assertEquals(3.25, trend.perSessionGainKg(), 0.01);
    }

    @Test
    @DisplayName("连续三周纹丝不动判定为平台期")
    void 力量停滞判定为平台期() {
        when(exerciseMapper.searchByName("卧推", 5)).thenReturn(List.of(bench()));
        LocalDateTime now = LocalDateTime.now();
        when(setLogMapper.selectByUserAndRange(anyLong(), any(), any())).thenReturn(List.of(
                set(20L, now.minusDays(18), 80, 5, 9.0),
                set(21L, now.minusDays(12), 80, 5, 9.0),
                set(22L, now.minusDays(6), 80.5, 5, 9.0)));

        TrainingTools.StrengthTrend trend = tools.getStrengthTrend("卧推", 8);

        assertTrue(trend.available());
        assertTrue(trend.plateau(), "三次训练只涨了 0.6%，低于 1% 阈值，必须判定为平台期");
        assertTrue(trend.growthPct() < 1.0);
    }

    @Test
    @DisplayName("只有一次训练时如实说数据不足，不外推出一条趋势")
    void 样本不足时明说() {
        when(exerciseMapper.searchByName("卧推", 5)).thenReturn(List.of(bench()));
        when(setLogMapper.selectByUserAndRange(anyLong(), any(), any()))
                .thenReturn(List.of(set(30L, LocalDateTime.now().minusDays(3), 60, 8, 8.0)));

        TrainingTools.StrengthTrend trend = tools.getStrengthTrend("卧推", 8);

        assertFalse(trend.available(), "一次数据算不出趋势，硬算出来的斜率没有任何意义");
        assertTrue(trend.reason().contains("只有 1 个训练日"), "要说明样本不足的原因");
        assertTrue(trend.reason().contains("严禁编造") || trend.reason().contains("破纪录"),
                "reason 必须拦住新号假破纪录 / 编造 88kg 话术");
    }

    @Test
    @DisplayName("没有任何记录时直接说没有，不要让模型自由发挥")
    void 无记录时明说() {
        when(exerciseMapper.searchByName("卧推", 5)).thenReturn(List.of(bench()));
        when(setLogMapper.selectByUserAndRange(anyLong(), any(), any())).thenReturn(List.of());

        TrainingTools.StrengthTrend trend = tools.getStrengthTrend("卧推", 8);

        assertFalse(trend.available());
        assertTrue(trend.reason().contains("没有"));
    }

    // ==================== 第四组：容量判定 ====================

    @Test
    @DisplayName("四周三十二组胸算出周均八组，落在最佳增肌区间")
    void 容量落在最佳增肌区间() {
        mockExerciseDict();
        when(setLogMapper.selectByUserAndRange(anyLong(), any(), any()))
                .thenReturn(chestSets(32, 0));

        TrainingTools.MuscleVolumeReport report = tools.getMuscleVolume(4);

        assertTrue(report.available());
        TrainingTools.MuscleVolume chest = report.groups().get(0);
        assertEquals("胸", chest.muscle());
        assertEquals(8, chest.weeklySets(), "32 组 ÷ 4 周 = 每周 8 组");
        assertEquals("MEV_TO_MAV", chest.verdict(),
                "8 组在胸的 MEV(6) 与 MAV 上限(16) 之间，属于有效区间");
        assertTrue(chest.advice().contains("继续保持"));
    }

    @Test
    @DisplayName("把一半热身组算进去，容量判定立刻从够变成不够——所以热身组必须剔除")
    void 热身组剔除后容量判定翻转() {
        mockExerciseDict();
        when(setLogMapper.selectByUserAndRange(anyLong(), any(), any()))
                .thenReturn(chestSets(16, 16));

        TrainingTools.MuscleVolumeReport report = tools.getMuscleVolume(4);

        TrainingTools.MuscleVolume chest = report.groups().get(0);
        assertEquals(4, chest.weeklySets(), "16 组正式组 ÷ 4 周 = 每周 4 组，热身组不计");
        assertEquals("BELOW_MEV", chest.verdict(),
                "若不剔除热身组会得到每周 8 组的假象；剔除后真实容量低于胸的 MEV(6)，必须加量");
    }

    // ==================== 第五组：营养计算 ====================

    @Test
    @DisplayName("热量与三大营养素由代码算准，绝不让模型自己估")
    void 营养数值精确() {
        TrainingTools.NutritionPlan plan =
                tools.getNutritionPlan(80, 178, 25, "male", "moderate");

        assertTrue(plan.available());
        // Mifflin-St Jeor：10×80 + 6.25×178 - 5×25 + 5 = 1792.5；×1.55 = 2778.4
        assertEquals(1793, plan.bmr());
        assertEquals(2778, plan.tdee());
        assertEquals(2278, plan.cutCalories());
        assertEquals(3078, plan.bulkCalories());
        // 蛋白优先：2.0×80=160g，脂肪 0.9×80=72g，剩余热量全给碳水
        assertEquals(160, plan.cutMacro().proteinG());
        assertEquals(72, plan.cutMacro().fatG());
        assertEquals(248, plan.cutMacro().carbG());
        assertTrue(plan.bulkMacro().carbG() > plan.cutMacro().carbG(),
                "增肌期多的热量应落在碳水而不是蛋白上");
    }

    @Test
    @DisplayName("非法身体数据时拒绝计算，而不是返回一个 0")
    void 营养参数非法时拒绝() {
        assertFalse(tools.getNutritionPlan(0, 178, 25, "male", "moderate").available());
        assertFalse(tools.getNutritionPlan(80, 178, 0, "male", "moderate").available());
        assertTrue(tools.getNutritionPlan(80, 178, 25, "male", "moderate").available(),
                "活动水平不传时应退化到 moderate，而不是拒绝服务");
    }

    // ==================== 第六组：近期训练 ====================

    @Test
    @DisplayName("能算出距上次训练隔了几天，这是判断用户是否偷懒的依据")
    void 近期训练概览() {
        WorkoutSession session = new WorkoutSession();
        session.setId(40L);
        session.setWorkoutDate(LocalDate.now().minusDays(2));
        session.setDurationMin(65);
        when(setLogMapper.selectByUserAndRange(anyLong(), any(), any())).thenReturn(List.of(
                set(40L, LocalDateTime.now().minusDays(2), 60, 8, 8.0),
                set(40L, LocalDateTime.now().minusDays(2), 65, 6, 8.0)));
        when(sessionMapper.selectBatchIds(anyCollection())).thenReturn(List.of(session));
        when(exerciseMapper.selectBatchIds(anyCollection())).thenReturn(List.of(bench()));

        TrainingTools.RecentWorkouts recent = tools.getRecentWorkouts(14);

        assertTrue(recent.available());
        assertEquals(1, recent.workoutCount());
        assertEquals(2, recent.daysSinceLastWorkout());
        assertEquals(1, recent.workouts().get(0).exercises().size());
        assertEquals(65, recent.workouts().get(0).durationMin());
        // 60×8=480 与 65×6=390，合计 870
        assertEquals(870.0, recent.workouts().get(0).volumeKg(), 0.01);
    }

    // ==================== 第七组：个人最好成绩 ====================

    @Test
    @DisplayName("个人最好成绩要带回最大重量与停滞周数，支撑『卡了几周』结论")
    void 个人最好成绩含停滞周数() {
        when(exerciseMapper.searchByName("卧推", 5)).thenReturn(List.of(bench()));
        LocalDateTime now = LocalDateTime.now();
        // 倒序：最近三次都是 82.5kg，跨度约 3 周
        when(setLogMapper.selectRecentByExercise(anyLong(), anyLong(), anyInt())).thenReturn(List.of(
                set(33L, now.minusDays(1), 82.5, 5, 9.0),
                set(32L, now.minusDays(8), 82.5, 5, 9.0),
                set(31L, now.minusDays(22), 82.5, 5, 9.0),
                set(30L, now.minusDays(36), 80, 5, 9.0)));

        TrainingTools.PersonalBest pb = tools.getPersonalBest("卧推");

        assertTrue(pb.available());
        assertEquals(BENCH_NAME, pb.exercise());
        assertEquals(82.5, pb.bestWeightKg(), 0.01);
        assertEquals(82.5, pb.latestWeightKg(), 0.01);
        assertTrue(pb.weeksStuckAtCurrentWeight() >= 3,
                "最近三次同重量跨 3 周，停滞周数应 >= 3，实际=" + pb.weeksStuckAtCurrentWeight());
        assertFalse(pb.firstTrainingDayOnly());
    }

    @Test
    @DisplayName("新号只有一天记录时标明 firstTrainingDayOnly，拦住假破纪录")
    void 新号单日不配破纪录话术() {
        when(exerciseMapper.searchByName("卧推", 5)).thenReturn(List.of(bench()));
        when(setLogMapper.selectRecentByExercise(anyLong(), anyLong(), anyInt())).thenReturn(List.of(
                set(40L, LocalDateTime.now(), 82.5, 5, 9.0)));

        TrainingTools.PersonalBest pb = tools.getPersonalBest("卧推");

        assertTrue(pb.available());
        assertTrue(pb.firstTrainingDayOnly());
        assertEquals(82.5, pb.bestWeightKg(), 0.01);
        assertEquals(82.5, pb.latestWeightKg(), 0.01);
        assertTrue(pb.reason().contains("1 个训练日") || pb.reason().contains("禁止"));
        assertFalse(pb.justBrokePlateau());
    }

    @Test
    @DisplayName("刚加上重量时要标 justBrokePlateau，不能让『没卡住』冲掉平台期故事")
    void 刚破平台要保留卡住周数() {
        when(exerciseMapper.searchByName("卧推", 5)).thenReturn(List.of(bench()));
        LocalDateTime now = LocalDateTime.now();
        // 最近一次 85 破了此前三周的 82.5 平台
        when(setLogMapper.selectRecentByExercise(anyLong(), anyLong(), anyInt())).thenReturn(List.of(
                set(44L, now.minusDays(1), 85, 5, 9.0),
                set(43L, now.minusDays(8), 82.5, 5, 9.0),
                set(42L, now.minusDays(15), 82.5, 5, 9.0),
                set(41L, now.minusDays(22), 82.5, 5, 9.0)));

        TrainingTools.PersonalBest pb = tools.getPersonalBest("卧推");

        assertTrue(pb.available());
        assertTrue(pb.justBrokePlateau(), "刚从 82.5 加到 85，应标刚破平台");
        assertTrue(pb.weeksStuckBeforeBreakthrough() >= 2,
                "突破前卡住周数应保留，实际=" + pb.weeksStuckBeforeBreakthrough());
        assertTrue(pb.reason().contains("刚突破") || pb.reason().contains("刚破"),
                "reason 必须要求模型说刚破平台：" + pb.reason());
    }

    @Test
    @DisplayName("当日无饮食记录时明说，不让模型自由发挥热量数字")
    void 当日无饮食时明说() {
        when(dietLogMapper.selectByUserAndDate(anyLong(), any())).thenReturn(List.of());
        TrainingTools.CurrentDietLog diet = tools.getCurrentDietLog(null);
        assertFalse(diet.available());
        assertTrue(diet.reason().contains("没有饮食记录"));
    }

    // ==================== 辅助 ====================

    private static Exercise bench() {
        Exercise e = new Exercise();
        e.setId(BENCH_ID);
        e.setName(BENCH_NAME);
        e.setPrimaryMuscle(MuscleGroup.CHEST.label());
        return e;
    }

    /** 60→62.5→65 三次稳步加重，每次 8 下 RPE 9（还能再做 1 次） */
    private static List<SetLog> progressLogs() {
        LocalDateTime now = LocalDateTime.now();
        return List.of(
                set(1L, now.minusDays(20), 60, 8, 9.0),
                set(2L, now.minusDays(13), 62.5, 8, 9.0),
                set(3L, now.minusDays(6), 65, 8, 9.0));
    }

    private static SetLog set(long sessionId, LocalDateTime time, double weight, int reps, double rpe) {
        SetLog s = new SetLog();
        s.setSessionId(sessionId);
        s.setCreateTime(time);
        s.setPerformedAt(time);
        s.setExerciseId(BENCH_ID);
        s.setWeightKg(BigDecimal.valueOf(weight));
        s.setReps(reps);
        s.setRpe(BigDecimal.valueOf(rpe));
        s.setIsWarmup(0);
        return s;
    }

    /** 生成 n 组正式组与 w 组热身组，全部挂在胸上 */
    private static List<SetLog> chestSets(int working, int warmup) {
        List<SetLog> logs = new ArrayList<>();
        LocalDateTime base = LocalDateTime.now().minusDays(28);
        int idx = 0;
        for (int i = 0; i < working; i++) {
            logs.add(chestSet(100L + i, base.plusDays(i % 28), 60, 8, 8.0, 0));
            idx++;
        }
        for (int i = 0; i < warmup; i++) {
            logs.add(chestSet(200L + i, base.plusDays(i % 28), 20, 10, 5.0, 1));
            idx++;
        }
        return logs;
    }

    private static SetLog chestSet(long id, LocalDateTime time, double weight, int reps, double rpe, int warmup) {
        SetLog s = new SetLog();
        s.setSessionId(id);
        s.setCreateTime(time);
        s.setPerformedAt(time);
        s.setExerciseId(BENCH_ID);
        s.setWeightKg(BigDecimal.valueOf(weight));
        s.setReps(reps);
        s.setRpe(BigDecimal.valueOf(rpe));
        s.setIsWarmup(warmup);
        return s;
    }

    private void mockExerciseDict() {
        when(exerciseMapper.selectBatchIds(anyCollection())).thenReturn(List.of(bench()));
    }
}
