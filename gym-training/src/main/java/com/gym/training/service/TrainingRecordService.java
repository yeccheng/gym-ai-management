package com.gym.training.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gym.common.exception.BusinessException;
import com.gym.common.result.ResultCode;
import com.gym.training.entity.BodyRecord;
import com.gym.training.entity.DietLog;
import com.gym.training.entity.Exercise;
import com.gym.training.entity.SetLog;
import com.gym.training.entity.WorkoutSession;
import com.gym.training.mapper.BodyRecordMapper;
import com.gym.training.mapper.DietLogMapper;
import com.gym.training.mapper.ExerciseMapper;
import com.gym.training.mapper.SetLogMapper;
import com.gym.training.mapper.WorkoutSessionMapper;
import com.gym.training.metric.TrainMetrics;
import com.gym.training.metric.TrainMetrics.MuscleGroup;
import com.gym.training.metric.TrainMetrics.SetRecord;
import com.gym.training.metric.TrainMetrics.VolumeVerdict;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 训练打卡业务。
 *
 * <p>设计目标：从打开 APP 到完成一组记录 ≤ 3 次点击。
 * 默认展开「上次同部位」动作列表，点「再来一组」带出上次重量×次数；
 * 保存由用户点「保存本组」确认——调重量时不能偷偷先存一版。
 */
@Service
@RequiredArgsConstructor
public class TrainingRecordService {

    /** 器材标签 → equipment 字段值 */
    private static final Map<String, List<Integer>> TAG_EQUIPMENT = Map.of(
            "free", List.of(1, 6),       // 自由重量：杠铃 / 壶铃
            "machine", List.of(3, 5),    // 固定器械 / 绳索
            "dumbbell", List.of(2),      // 居家哑铃
            "bodyweight", List.of(4)     // 自重
    );

    private final WorkoutSessionMapper sessionMapper;
    private final SetLogMapper setLogMapper;
    private final ExerciseMapper exerciseMapper;
    private final BodyRecordMapper bodyRecordMapper;
    private final DietLogMapper dietLogMapper;

    /** demo 账号禁止用正式组冲掉卧推/肩推平台期剧本 */
    @Value("${gym.demo.story-lock:true}")
    private boolean demoStoryLock;

    // ==================== 上次同部位 ====================

    /**
     * 打开训练页时的默认列表：上一次训练做过的动作 + 每项最后一组的重量×次数。
     * 「同部位」这里取最近一次完整会话——用户每天都练同部位切分时，这就是正确的默认；
     * 全身分化时也比空列表强，至少能一键复用上周的动作。
     */
    public LastSessionView lastSamePart(long userId) {
        WorkoutSession last = sessionMapper.selectLastBefore(userId, LocalDate.now().plusDays(1));
        if (last == null) {
            return new LastSessionView(null, null, null, List.of());
        }
        List<SetLog> logs = setLogMapper.selectBySession(last.getId()).stream()
                .filter(s -> s.getIsWarmup() == null || s.getIsWarmup() == 0)
                .toList();
        if (logs.isEmpty()) {
            return new LastSessionView(last.getId(), last.getWorkoutDate().toString(),
                    last.getSessionType(), List.of());
        }
        Map<Long, Exercise> exercises = loadExercises(logs.stream()
                .map(SetLog::getExerciseId).filter(Objects::nonNull).collect(Collectors.toSet()));

        // 按动作聚合：取最后一组作为「再来一组」模板
        LinkedHashMap<Long, SetLog> lastSetByEx = new LinkedHashMap<>();
        LinkedHashMap<Long, Integer> countByEx = new LinkedHashMap<>();
        for (SetLog s : logs) {
            lastSetByEx.put(s.getExerciseId(), s);
            countByEx.merge(s.getExerciseId(), 1, Integer::sum);
        }
        List<LastExercise> items = new ArrayList<>();
        for (Map.Entry<Long, SetLog> e : lastSetByEx.entrySet()) {
            Exercise ex = exercises.get(e.getKey());
            SetLog s = e.getValue();
            items.add(new LastExercise(
                    e.getKey(),
                    ex == null ? ("动作#" + e.getKey()) : ex.getName(),
                    ex == null ? null : ex.getPrimaryMuscle(),
                    s.getWeightKg() == null ? 0 : s.getWeightKg().doubleValue(),
                    s.getReps() == null ? 0 : s.getReps(),
                    countByEx.getOrDefault(e.getKey(), 0)));
        }
        return new LastSessionView(last.getId(), last.getWorkoutDate().toString(),
                last.getSessionType(), items);
    }

    // ==================== 今日会话 + 记一组 ====================

    /** 获取或创建今天的训练会话；用户打卡会话 is_demo 必须为 0 */
    @Transactional
    public WorkoutSession ensureTodaySession(long userId, Integer sessionType) {
        WorkoutSession today = sessionMapper.selectByUserAndDate(userId, LocalDate.now());
        if (today != null) {
            // 历史脏数据：今日会话曾被 Demo 回填成 is_demo=1，进打卡页就纠正
            if (today.getIsDemo() == null || today.getIsDemo() != 0) {
                sessionMapper.markReal(today.getId(), userId);
                today.setIsDemo(0);
            }
            return today;
        }
        WorkoutSession s = new WorkoutSession();
        s.setUserId(userId);
        s.setWorkoutDate(LocalDate.now());
        s.setSessionType(sessionType == null ? 6 : sessionType);
        s.setStartTime(LocalDateTime.now());
        s.setIsDemo(0); // 用户打卡永远是真实数据
        s.setDeleted(0);
        s.setCreateTime(LocalDateTime.now());
        s.setUpdateTime(LocalDateTime.now());
        sessionMapper.insert(s);
        return s;
    }

    /**
     * 「我的」页：有没有可画的训练曲线。
     * 以任意组记录为准（含样板历史）——样板是该用户账号上的半年轨迹，
     * 不能因为 is_demo=1 就退回「示例图 / 一个黄点」。
     */
    public boolean hasRealTrainingData(long userId) {
        return setLogMapper.countAnySets(userId) > 0
                || setLogMapper.countRealSets(userId) > 0
                || sessionMapper.countRealSessions(userId) > 0;
    }

    public TodaySessionView todaySession(long userId) {
        WorkoutSession today = sessionMapper.selectByUserAndDate(userId, LocalDate.now());
        if (today == null) {
            return new TodaySessionView(null, LocalDate.now().toString(), List.of());
        }
        List<SetLog> logs = setLogMapper.selectBySession(today.getId());
        Map<Long, Exercise> exercises = loadExercises(logs.stream()
                .map(SetLog::getExerciseId).filter(Objects::nonNull).collect(Collectors.toSet()));
        List<SetView> views = logs.stream().map(s -> {
            Exercise ex = exercises.get(s.getExerciseId());
            return new SetView(s.getId(), s.getExerciseId(),
                    ex == null ? "" : ex.getName(),
                    s.getSetIndex(),
                    s.getWeightKg() == null ? 0 : s.getWeightKg().doubleValue(),
                    s.getReps() == null ? 0 : s.getReps(),
                    s.getRpe() == null ? null : s.getRpe().doubleValue(),
                    s.getIsWarmup() != null && s.getIsWarmup() == 1,
                    s.getE1rm() == null ? null : s.getE1rm().doubleValue());
        }).toList();
        return new TodaySessionView(today.getId(), today.getWorkoutDate().toString(), views);
    }

    /**
     * 记一组。前端防抖 1 秒后调这里；也可点「保存本组」。
     * e1RM / 有效组判定在服务端算，不信任前端传值。
     */
    @Transactional
    public SetView saveSet(long userId, SaveSetRequest req) {
        Long exerciseId = parseId(req.exerciseId());
        if (exerciseId == null || req.weightKg() == null || req.reps() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "动作、重量、次数不能为空");
        }
        if (req.reps() <= 0 || req.weightKg() < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "次数必须为正，重量不能为负");
        }
        Exercise ex = exerciseMapper.selectById(exerciseId);
        if (ex == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "动作不存在");
        }
        boolean warmup = Boolean.TRUE.equals(req.warmup());
        // 演示硬锁：由调用方传入 username；空则跳过（单测）
        if (req.username() != null) {
            assertDemoStoryAllows(req.username(), ex.getName(), warmup);
        }

        // 一律用「今日会话」：完全忽略前端 sessionId。
        WorkoutSession session = ensureTodaySession(userId, null);
        // 强制标成真实：防止会话曾被 Demo 回填成 is_demo=1，导致「我的」页看不见刚打的卡
        if (session.getIsDemo() == null || session.getIsDemo() != 0) {
            session.setIsDemo(0);
            sessionMapper.markReal(session.getId(), userId);
            session.setIsDemo(0);
        }

        Double rpe = req.rpe();
        double e1rm = TrainMetrics.e1rm(req.weightKg(), req.reps(), rpe);
        Long setId = parseId(req.setId());

        SetLog log = null;
        if (setId != null) {
            // 防抖：同一组微调重量反复提交 → 更新；查不到（精度丢失）则当新组插入
            SetLog existing = setLogMapper.selectById(setId);
            if (existing != null && Objects.equals(existing.getUserId(), userId)) {
                log = existing;
                log.setWeightKg(BigDecimal.valueOf(req.weightKg()));
                log.setReps(req.reps());
                log.setRpe(rpe == null ? null : BigDecimal.valueOf(rpe));
                log.setIsWarmup(warmup ? 1 : 0);
                log.setE1rm(BigDecimal.valueOf(e1rm).setScale(2, RoundingMode.HALF_UP));
                log.setIsWorkingSet(warmup ? 0 : 1);
                setLogMapper.updateById(log);
            }
        }
        if (log == null) {
            int nextIndex = setLogMapper.maxSetIndex(session.getId(), exerciseId) + 1;
            log = new SetLog();
            log.setSessionId(session.getId());
            log.setUserId(userId);
            log.setExerciseId(exerciseId);
            log.setSetIndex(nextIndex);
            log.setWeightKg(BigDecimal.valueOf(req.weightKg()));
            log.setReps(req.reps());
            log.setRpe(rpe == null ? null : BigDecimal.valueOf(rpe));
            log.setIsWarmup(warmup ? 1 : 0);
            log.setIsWorkingSet(warmup ? 0 : 1);
            log.setE1rm(BigDecimal.valueOf(e1rm).setScale(2, RoundingMode.HALF_UP));
            log.setIsPr(0);
            log.setPerformedAt(LocalDateTime.now());
            log.setCreateTime(LocalDateTime.now());
            setLogMapper.insert(log);
        }
        return new SetView(log.getId(), log.getExerciseId(), ex.getName(), log.getSetIndex(),
                log.getWeightKg().doubleValue(), log.getReps(),
                log.getRpe() == null ? null : log.getRpe().doubleValue(),
                warmup, log.getE1rm().doubleValue());
    }

    /**
     * 演示剧本硬锁：demo + 卧推/肩推 + 正式组 → 直接拒绝。
     * 热身组仍可记；要冲 PR 请关 gym.demo.story-lock 或换账号。
     */
    public void assertDemoStoryAllows(String username, String exerciseName, boolean warmup) {
        if (!demoStoryLock || warmup || !StringUtils.hasText(username) || !StringUtils.hasText(exerciseName)) {
            return;
        }
        if (!"demo".equalsIgnoreCase(username.trim())) {
            return;
        }
        String n = exerciseName;
        if (n.contains("卧推") || n.contains("肩推") || n.contains("推举")) {
            throw new BusinessException(ResultCode.BIZ_ERROR,
                    "演示剧本保护中：demo 不能用正式组改写「" + n + "」平台期。"
                            + "可记热身组；要破平台请关 gym.demo.story-lock 或换自己的账号。");
        }
    }

    /**
     * 解析雪花 ID。前端应用 JSON 字符串传，避免 Number 丢精度；
     * 同时兼容 Long / Number / 数字字符串。
     */
    private static Long parseId(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Long l) {
            return l;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "ID 格式错误：" + s);
        }
    }

    // ==================== 动作库 ====================

    /**
     * 动作库检索。用 LambdaQueryWrapper 而不是注解里的 &lt;script&gt;——
     * 注解动态 SQL 在部分 MyBatis 版本上对 List 空参处理不稳，表现为「搜卧推永远空」。
     *
     * <p>排序（搜「卧推」时用户要先看到「杠铃卧推」，不能让上斜/下斜挤到最前）：
     * 完全匹配 → 前缀匹配 → 名称包含（同梯队短名优先）→ 别名匹配。
     */
    /**
     * 会员自建动作：库里没有时补一条，立刻可用于打卡。
     * 同名已存在则直接返回已有记录，不重复插。
     */
    @Transactional
    public ExerciseView createCustomExercise(CreateExerciseRequest req) {
        if (req == null || !StringUtils.hasText(req.name())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "动作名不能为空");
        }
        String name = req.name().trim();
        if (name.length() > 64) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "动作名请控制在 64 字以内");
        }
        if (name.contains("�") || name.chars().filter(ch -> ch == '?' || ch == '？').count() >= 2) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "动作名无效，请用手打中文名");
        }
        Exercise existing = exerciseMapper.selectByName(name);
        if (existing != null) {
            return new ExerciseView(existing.getId(), existing.getName(), existing.getEquipment(),
                    equipmentLabel(existing.getEquipment()), existing.getPrimaryMuscle(), existing.getAlias());
        }
        int equipment = req.equipment() == null ? 1 : req.equipment();
        if (equipment < 1 || equipment > 6) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "器材类型无效");
        }
        String muscle = StringUtils.hasText(req.primaryMuscle()) ? req.primaryMuscle().trim() : "其他";
        if (muscle.length() > 32) {
            muscle = muscle.substring(0, 32);
        }
        Exercise e = new Exercise();
        e.setName(name);
        e.setAlias(StringUtils.hasText(req.alias()) ? req.alias().trim() : "自定义");
        e.setEquipment(equipment);
        e.setMovementType(2);
        e.setMovementPattern(guessPattern(muscle));
        e.setPrimaryMuscle(muscle);
        e.setDifficulty(2);
        e.setActionLevel(3); // 自定义默认靠后，别抢三大项
        e.setSteps(StringUtils.hasText(req.steps()) ? req.steps().trim() : null);
        e.setDeleted(0);
        e.setCreateTime(LocalDateTime.now());
        e.setUpdateTime(LocalDateTime.now());
        exerciseMapper.insert(e);
        return new ExerciseView(e.getId(), e.getName(), e.getEquipment(),
                equipmentLabel(e.getEquipment()), e.getPrimaryMuscle(), e.getAlias());
    }

    private static int guessPattern(String muscle) {
        if (muscle == null) {
            return 1;
        }
        if (muscle.contains("胸") || muscle.contains("肩") || muscle.contains("三头") || muscle.contains("肱三")) {
            return 1;
        }
        if (muscle.contains("背") || muscle.contains("二头") || muscle.contains("肱二")) {
            return 2;
        }
        if (muscle.contains("股四") || muscle.contains("腿") || muscle.contains("蹲")) {
            return 3;
        }
        if (muscle.contains("腘") || muscle.contains("臀") || muscle.contains("硬拉")) {
            return 4;
        }
        if (muscle.contains("腹") || muscle.contains("核心")) {
            return 5;
        }
        return 1;
    }

    public List<ExerciseView> searchExercises(String tag, String keyword) {
        // deleted 由 @TableLogic 自动过滤，不必再手写 eq
        LambdaQueryWrapper<Exercise> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(tag)) {
            List<Integer> equipments = TAG_EQUIPMENT.get(tag.trim().toLowerCase());
            if (equipments == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "标签只能是 free / machine / dumbbell / bodyweight");
            }
            q.in(Exercise::getEquipment, equipments);
        }
        final String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        if (kw != null) {
            q.and(w -> w.like(Exercise::getName, kw)
                    .or().like(Exercise::getNameEn, kw)
                    .or().like(Exercise::getAlias, kw));
        }
        // 先多取，再按 action_level + 相关度排序（杠铃卧推 level=1 压过上斜 level=2）
        q.last("LIMIT 200");
        List<Exercise> list = new ArrayList<>(exerciseMapper.selectList(q));
        if (kw != null) {
            list.sort(Comparator
                    .comparingInt((Exercise e) -> e.getActionLevel() == null ? 2 : e.getActionLevel())
                    .thenComparingInt(e -> relevanceScore(e, kw))
                    .thenComparingInt(e -> e.getName() == null ? 999 : e.getName().length())
                    .thenComparing(e -> e.getName() == null ? "" : e.getName()));
        } else {
            // 无关键词：三大项钉死最前，再按基础动作 → 肌群，少让用户往下翻找卧推
            list.sort(Comparator
                    .comparingInt((Exercise e) -> big3PinRank(e.getName()))
                    .thenComparingInt((Exercise e) -> e.getActionLevel() == null ? 2 : e.getActionLevel())
                    .thenComparing(e -> e.getPrimaryMuscle() == null ? "" : e.getPrimaryMuscle())
                    .thenComparing(e -> e.getName() == null ? "" : e.getName()));
        }
        return list.stream()
                .limit(80)
                .map(e -> new ExerciseView(e.getId(), e.getName(), e.getEquipment(),
                        equipmentLabel(e.getEquipment()), e.getPrimaryMuscle(), e.getAlias()))
                .toList();
    }

    /** 杠铃卧推 / 深蹲 / 硬拉 钉在列表最前；其它动作统一靠后 */
    private static int big3PinRank(String name) {
        if (name == null) {
            return 9;
        }
        if (name.equals("杠铃卧推") || name.equals("卧推")) {
            return 0;
        }
        if (name.equals("杠铃深蹲") || name.equals("深蹲")) {
            return 1;
        }
        if (name.equals("硬拉")) {
            return 2;
        }
        return 9;
    }

    /**
     * 最近常用动作，打卡页快捷栏。
     * 窗口默认放宽到能盖住样板里最近几次；没有记录时回落三大项，避免空白还要翻动作库。
     */
    public List<ExerciseView> recentFrequentExercises(long userId, int days, int limit) {
        int d = Math.min(Math.max(days, 1), 90);
        int lim = Math.min(Math.max(limit, 1), 10);
        LocalDateTime from = LocalDateTime.now().minusDays(d);
        List<Long> ids = setLogMapper.selectTopExerciseIds(userId, from, lim);
        List<ExerciseView> out = new ArrayList<>();
        if (ids != null && !ids.isEmpty()) {
            Map<Long, Exercise> map = loadExercises(ids.stream().collect(Collectors.toSet()));
            for (Long id : ids) {
                Exercise e = map.get(id);
                if (e == null) {
                    continue;
                }
                out.add(new ExerciseView(e.getId(), e.getName(), e.getEquipment(),
                        equipmentLabel(e.getEquipment()), e.getPrimaryMuscle(), e.getAlias()));
            }
        }
        if (out.isEmpty()) {
            for (String name : List.of("杠铃卧推", "杠铃深蹲", "硬拉")) {
                Exercise e = exerciseMapper.selectByName(name);
                if (e != null) {
                    out.add(new ExerciseView(e.getId(), e.getName(), e.getEquipment(),
                            equipmentLabel(e.getEquipment()), e.getPrimaryMuscle(), e.getAlias()));
                }
            }
        }
        return out;
    }

    /**
     * 相关度分数，越小越靠前。
     * 0 完全等于；100+ 前缀；200+ 名称包含（再按长度区分基础变式）；300+ 仅别名。
     */
    static int relevanceScore(Exercise e, String kw) {
        String name = e.getName() == null ? "" : e.getName();
        String alias = e.getAlias() == null ? "" : e.getAlias();
        String nameEn = e.getNameEn() == null ? "" : e.getNameEn();
        if (name.equals(kw)) {
            return 0;
        }
        if (name.startsWith(kw)) {
            return 100 + name.length();
        }
        if (name.contains(kw)) {
            // 「杠铃卧推」(4) 优于「上斜杠铃卧推」(6)
            return 200 + name.length() * 10;
        }
        if (alias.contains(kw)) {
            return 300 + name.length();
        }
        if (nameEn.toLowerCase().contains(kw.toLowerCase())) {
            return 400 + name.length();
        }
        return 500;
    }

    private static String equipmentLabel(Integer eq) {
        if (eq == null) {
            return "";
        }
        return switch (eq) {
            case 1 -> "杠铃";
            case 2 -> "哑铃";
            case 3 -> "固定器械";
            case 4 -> "自重";
            case 5 -> "绳索";
            case 6 -> "壶铃";
            default -> "其他";
        };
    }

    // ==================== 饮食粗记 ====================

    /**
     * 把昨天（或指定日）的饮食原样抄到今天——减脂用户最高频的「和昨天一样」。
     */
    @Transactional
    public DietDayView copyDietDay(long userId, LocalDate fromDate) {
        LocalDate from = fromDate == null ? LocalDate.now().minusDays(1) : fromDate;
        LocalDate today = LocalDate.now();
        if (from.equals(today)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不能从今天复制到今天");
        }
        List<DietLog> src = dietLogMapper.selectByUserAndDate(userId, from);
        if (src == null || src.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND,
                    from + " 没有饮食记录，没法「和昨天一样」");
        }
        for (DietLog s : src) {
            DietLog d = new DietLog();
            d.setUserId(userId);
            d.setLogDate(today);
            d.setMealType(s.getMealType() == null ? 4 : s.getMealType());
            d.setFoodName(s.getFoodName());
            d.setCalories(s.getCalories() == null ? 0 : s.getCalories());
            d.setProteinG(s.getProteinG());
            d.setNote(s.getNote());
            d.setCreateTime(LocalDateTime.now());
            dietLogMapper.insert(d);
        }
        return dietDay(userId, today);
    }

    @Transactional
    public DietLog saveDiet(long userId, SaveDietRequest req) {
        if (!StringUtils.hasText(req.foodName()) || req.calories() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "食物名和热量不能为空");
        }
        String foodName = sanitizeFoodName(req.foodName());
        DietLog d = new DietLog();
        d.setUserId(userId);
        d.setLogDate(req.date() == null ? LocalDate.now() : req.date());
        d.setMealType(req.mealType() == null ? 4 : req.mealType());
        d.setFoodName(foodName);
        d.setCalories(req.calories());
        d.setProteinG(req.proteinG() == null ? null : BigDecimal.valueOf(req.proteinG()));
        d.setNote(req.note());
        d.setCreateTime(LocalDateTime.now());
        dietLogMapper.insert(d);
        return d;
    }

    /** 拒收「??? 150g」这类编码炸掉的残名，别让看板看起来像假数据 */
    private static String sanitizeFoodName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "食物名不能为空");
        }
        long qmarks = name.chars().filter(ch -> ch == '?' || ch == '？').count();
        if (qmarks >= 2) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "食物名乱码了，请重新选快捷项或手打中文名");
        }
        if (name.contains("�")) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "食物名编码异常，请重试");
        }
        return name;
    }

    @Transactional
    public void deleteDiet(long userId, long dietId) {
        DietLog d = dietLogMapper.selectById(dietId);
        if (d == null || d.getUserId() == null || d.getUserId() != userId) {
            throw new BusinessException(ResultCode.NOT_FOUND, "饮食记录不存在");
        }
        dietLogMapper.deleteById(dietId);
    }

    /** 近 N 天饮食，给历史日历用 */
    public List<DietDayView> dietHistory(long userId, int days) {
        int n = Math.min(Math.max(days, 1), 31);
        List<DietDayView> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < n; i++) {
            out.add(dietDay(userId, today.minusDays(i)));
        }
        return out;
    }

    /** 清掉今日饮食里的乱码残名（看板自愈） */
    @Transactional
    public int purgeBadDietNames(long userId, LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        List<DietLog> logs = dietLogMapper.selectByUserAndDate(userId, day);
        int n = 0;
        for (DietLog d : logs) {
            String name = d.getFoodName() == null ? "" : d.getFoodName();
            long qmarks = name.chars().filter(ch -> ch == '?' || ch == '？').count();
            if (qmarks >= 2 || name.contains("�")) {
                dietLogMapper.deleteById(d.getId());
                n++;
            }
        }
        return n;
    }

    public List<DietLog> listDiet(long userId, LocalDate date) {
        return dietLogMapper.selectByUserAndDate(userId, date == null ? LocalDate.now() : date);
    }

    /** 今日饮食汇总：热量/蛋白一眼能看，给「我的」页用 */
    public DietDayView dietDay(long userId, LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        purgeBadDietNames(userId, day);
        List<DietLog> logs = dietLogMapper.selectByUserAndDate(userId, day);
        int cal = 0;
        double protein = 0;
        List<DietItemView> items = new ArrayList<>();
        for (DietLog d : logs) {
            int c = d.getCalories() == null ? 0 : d.getCalories();
            double p = d.getProteinG() == null ? 0 : d.getProteinG().doubleValue();
            cal += c;
            protein += p;
            items.add(new DietItemView(
                    d.getId() == null ? null : String.valueOf(d.getId()),
                    d.getMealType() == null ? 4 : d.getMealType(),
                    mealLabel(d.getMealType()),
                    d.getFoodName(), c, p));
        }
        return new DietDayView(day.toString(), cal, Math.round(protein * 10) / 10.0, items);
    }

    private static String mealLabel(Integer mealType) {
        if (mealType == null) {
            return "加餐";
        }
        return switch (mealType) {
            case 1 -> "早餐";
            case 2 -> "午餐";
            case 3 -> "晚餐";
            default -> "加餐";
        };
    }

    /**
     * 记体重：同一天覆盖更新，避免连按出一串噪声点。
     */
    @Transactional
    public BodyRecord saveBodyWeight(long userId, SaveBodyRequest req) {
        if (req.weightKg() == null || req.weightKg() <= 0 || req.weightKg() > 400) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "体重需在 0～400 kg");
        }
        LocalDate day = req.date() == null ? LocalDate.now() : req.date();
        List<BodyRecord> existing = bodyRecordMapper.selectByRange(userId, day, day);
        BodyRecord br;
        if (existing != null && !existing.isEmpty()) {
            br = existing.get(0);
            br.setWeightKg(BigDecimal.valueOf(req.weightKg()).setScale(2, RoundingMode.HALF_UP));
            if (req.bodyFatPct() != null) {
                br.setBodyFatPct(BigDecimal.valueOf(req.bodyFatPct()));
            }
            if (StringUtils.hasText(req.note())) {
                br.setNote(req.note().trim());
            }
            bodyRecordMapper.updateById(br);
        } else {
            br = new BodyRecord();
            br.setUserId(userId);
            br.setRecordDate(day);
            br.setWeightKg(BigDecimal.valueOf(req.weightKg()).setScale(2, RoundingMode.HALF_UP));
            br.setBodyFatPct(req.bodyFatPct() == null ? null : BigDecimal.valueOf(req.bodyFatPct()));
            br.setNote(req.note());
            br.setCreateTime(LocalDateTime.now());
            bodyRecordMapper.insert(br);
        }
        return br;
    }

    /** 最近一条体重（可空） */
    public BodySnapshot latestBody(long userId) {
        List<BodyRecord> recent = bodyRecordMapper.selectRecent(userId, 1);
        if (recent == null || recent.isEmpty() || recent.get(0).getWeightKg() == null) {
            return new BodySnapshot(false, null, null, null);
        }
        BodyRecord b = recent.get(0);
        return new BodySnapshot(true, b.getRecordDate().toString(),
                b.getWeightKg().doubleValue(),
                b.getBodyFatPct() == null ? null : b.getBodyFatPct().doubleValue());
    }

    /** 动作要领：步骤 / 常见错误 / 呼吸 / 安全——记组弹层展示，别只躺在库里 */
    public ExerciseCueView exerciseCue(long exerciseId) {
        Exercise e = exerciseMapper.selectById(exerciseId);
        if (e == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "动作不存在");
        }
        return new ExerciseCueView(
                String.valueOf(e.getId()),
                e.getName(),
                e.getPrimaryMuscle(),
                equipmentLabel(e.getEquipment()),
                blankToNull(e.getSteps()),
                blankToNull(e.getCommonMistakes()),
                blankToNull(e.getBreathing()),
                blankToNull(e.getSafetyTips()));
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    // ==================== 肌群容量看板（与 AI getMuscleVolume 同源） ====================

    /**
     * 近几周各肌群周均有效组数 vs MEV/MAV/MRV。
     * 「我的」页一眼看缺口，别只藏在对话框里。
     */
    public MuscleVolumeBoard muscleVolumeBoard(long userId, int weeks) {
        int w = Math.min(Math.max(weeks, 1), 52);
        LocalDateTime now = LocalDateTime.now();
        List<SetLog> logs = setLogMapper.selectByUserAndRange(userId, now.minusWeeks(w), now);
        if (logs == null || logs.isEmpty()) {
            return new MuscleVolumeBoard(false, "近" + w + "周还没有训练记录", w, List.of());
        }
        Map<Long, Exercise> exercises = loadExercises(logs.stream()
                .map(SetLog::getExerciseId).filter(Objects::nonNull).collect(Collectors.toSet()));
        List<SetRecord> records = logs.stream()
                .filter(s -> s.getWeightKg() != null && s.getReps() != null)
                .map(s -> {
                    Exercise ex = exercises.get(s.getExerciseId());
                    Double rpe = s.getRpe() == null ? null : s.getRpe().doubleValue();
                    boolean warmup = s.getIsWarmup() != null && s.getIsWarmup() == 1;
                    return new SetRecord(s.getExerciseId(),
                            ex == null ? null : ex.getPrimaryMuscle(),
                            s.getWeightKg().doubleValue(), s.getReps(), rpe, warmup);
                })
                .toList();
        if (records.isEmpty()) {
            return new MuscleVolumeBoard(false, "近" + w + "周没有可统计的有效组", w, List.of());
        }
        Map<Long, Double> baseline = TrainMetrics.e1rmBaseline(records);
        List<MuscleVolumeItem> items = new ArrayList<>();
        for (MuscleGroup group : MuscleGroup.values()) {
            int total = TrainMetrics.weeklyWorkingSets(records, group, baseline);
            if (total <= 0) {
                continue;
            }
            int perWeek = (int) Math.round(total / (double) w);
            VolumeVerdict verdict = TrainMetrics.verdict(group, perWeek);
            items.add(new MuscleVolumeItem(
                    group.label(), perWeek, group.mev(), group.mavUpper(), group.mrv(),
                    verdict.name(), verdict.advice()));
        }
        // 缺口优先：低于 MEV 的排前面，练铁人一眼看见该补哪
        items.sort(Comparator
                .comparingInt((MuscleVolumeItem i) -> "BELOW_MEV".equals(i.verdict()) ? 0 : 1)
                .thenComparingInt(i -> i.setsPerWeek() - i.mev()));
        return new MuscleVolumeBoard(true, "", w, items);
    }

    // ==================== 图表数据（P2 前端消费） ====================

    /**
     * 主力力量曲线：三大项 + 肩推 / 划船（当天最好一组推算 e1RM）。
     * 与 AI 工具同一公式 {@link TrainMetrics#e1rm}；并附带「坑」的人话解释
     * （减载 / 长时间没练），避免用户把故意掉重量当成受伤退步。
     */
    public ChartSeries big3Trend(long userId, int weeks) {
        int w = Math.min(Math.max(weeks, 1), 52);
        LocalDateTime from = LocalDateTime.now().minusWeeks(w);
        List<SetLog> logs = setLogMapper.selectByUserAndRange(userId, from, LocalDateTime.now());
        // LinkedHashMap 保序；肩推/划船也进图，别只剩三大项
        Map<String, String[]> targets = new LinkedHashMap<>();
        targets.put("bench", new String[]{"卧推", "杠铃卧推"});
        targets.put("squat", new String[]{"深蹲", "杠铃深蹲"});
        targets.put("deadlift", new String[]{"硬拉"});
        targets.put("ohp", new String[]{"杠铃肩推", "哑铃肩推", "推举", "杠铃推举", "肩推", "过头推举"});
        targets.put("row", new String[]{"杠铃划船", "哑铃划船", "划船", "俯身划船"});

        Map<Long, Exercise> allEx = loadExercises(logs.stream()
                .map(SetLog::getExerciseId).filter(Objects::nonNull).collect(Collectors.toSet()));

        Map<String, List<ChartPoint>> series = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> t : targets.entrySet()) {
            Long exerciseId = resolveExerciseId(allEx, t.getValue());
            if (exerciseId == null) {
                series.put(t.getKey(), List.of());
                continue;
            }
            Long eid = exerciseId;
            LinkedHashMap<LocalDate, Double> bestByDay = new LinkedHashMap<>();
            logs.stream()
                    .filter(s -> eid.equals(s.getExerciseId()))
                    .filter(s -> s.getIsWarmup() == null || s.getIsWarmup() == 0)
                    .filter(s -> s.getWeightKg() != null && s.getReps() != null && s.getPerformedAt() != null)
                    .sorted((a, b) -> a.getPerformedAt().compareTo(b.getPerformedAt()))
                    .forEach(s -> {
                        LocalDate day = s.getPerformedAt().toLocalDate();
                        double e1 = resolveE1rm(s);
                        bestByDay.merge(day, e1, Math::max);
                    });
            series.put(t.getKey(), bestByDay.entrySet().stream()
                    .map(e -> new ChartPoint(e.getKey().toString(),
                            Math.round(e.getValue() * 10) / 10.0))
                    .toList());
        }
        return new ChartSeries(series, buildChartNotes(userId, from.toLocalDate(), series));
    }

    /**
     * 「我的」页主动提示：谁卡住了 / 谁刚破平台——不用先去问 AI。
     */
    public List<PlateauAlert> plateauAlerts(long userId) {
        int w = 16;
        LocalDateTime from = LocalDateTime.now().minusWeeks(w);
        List<SetLog> logs = setLogMapper.selectByUserAndRange(userId, from, LocalDateTime.now());
        Map<String, String[]> targets = new LinkedHashMap<>();
        targets.put("bench", new String[]{"卧推", "杠铃卧推"});
        targets.put("squat", new String[]{"深蹲", "杠铃深蹲"});
        targets.put("deadlift", new String[]{"硬拉"});
        targets.put("ohp", new String[]{"杠铃肩推", "哑铃肩推", "推举", "杠铃推举", "肩推", "过头推举"});
        targets.put("row", new String[]{"杠铃划船", "哑铃划船", "划船", "俯身划船"});
        Map<String, String> labels = Map.of(
                "bench", "卧推", "squat", "深蹲", "deadlift", "硬拉",
                "ohp", "肩推", "row", "划船");

        Map<Long, Exercise> allEx = loadExercises(logs.stream()
                .map(SetLog::getExerciseId).filter(Objects::nonNull).collect(Collectors.toSet()));

        List<PlateauAlert> out = new ArrayList<>();
        for (Map.Entry<String, String[]> t : targets.entrySet()) {
            Long eid = resolveExerciseId(allEx, t.getValue());
            if (eid == null) {
                continue;
            }
            LinkedHashMap<LocalDate, Double> bestByDay = new LinkedHashMap<>();
            logs.stream()
                    .filter(s -> eid.equals(s.getExerciseId()))
                    .filter(s -> s.getIsWarmup() == null || s.getIsWarmup() == 0)
                    .filter(s -> s.getWeightKg() != null && s.getReps() != null && s.getPerformedAt() != null)
                    .sorted(Comparator.comparing(SetLog::getPerformedAt))
                    .forEach(s -> {
                        LocalDate day = s.getPerformedAt().toLocalDate();
                        bestByDay.merge(day, resolveE1rm(s), Math::max);
                    });
            if (bestByDay.size() < 4) {
                continue;
            }
            List<Double> series = new ArrayList<>(bestByDay.values());
            List<LocalDate> days = new ArrayList<>(bestByDay.keySet());
            boolean plateauNow = TrainMetrics.isPlateau(series);
            boolean justBroke = false;
            if (!plateauNow && series.size() >= 4) {
                justBroke = TrainMetrics.isPlateau(series.subList(0, series.size() - 1));
            }
            if (!plateauNow && !justBroke) {
                continue;
            }
            int stuckWeeks;
            if (justBroke) {
                LocalDate start = days.get(0);
                LocalDate end = days.get(days.size() - 2);
                stuckWeeks = Math.max(2, (int) java.time.temporal.ChronoUnit.WEEKS.between(start, end));
            } else {
                LocalDate start = days.get(Math.max(0, days.size() / 2 - 1));
                LocalDate end = days.get(days.size() - 1);
                stuckWeeks = Math.max(2, (int) java.time.temporal.ChronoUnit.WEEKS.between(start, end));
            }
            double current = Math.round(series.get(series.size() - 1) * 10) / 10.0;
            String name = labels.getOrDefault(t.getKey(), t.getKey());
            String tip = justBroke
                    ? (name + " 刚破约 " + stuckWeeks + " 周的平台，估测最大力量约 " + current + " kg")
                    : (name + " 大约卡了 " + stuckWeeks + " 周，估测最大力量约 " + current + " kg · 该换刺激或减载");
            out.add(new PlateauAlert(t.getKey(), name, plateauNow, justBroke, stuckWeeks, current, tip));
        }
        // 卡住的排前面，刚破的次之
        out.sort((a, b) -> {
            if (a.plateau() != b.plateau()) {
                return a.plateau() ? -1 : 1;
            }
            return Integer.compare(b.weeksStuck(), a.weeksStuck());
        });
        return out;
    }

    private Long resolveExerciseId(Map<Long, Exercise> allEx, String[] names) {
        Long exerciseId = matchExerciseId(allEx, names);
        if (exerciseId != null) {
            return exerciseId;
        }
        for (String name : names) {
            Exercise hit = exerciseMapper.selectByName(name);
            if (hit != null) {
                return hit.getId();
            }
            List<Exercise> fuzzy = exerciseMapper.searchByName(name, 1);
            if (!fuzzy.isEmpty()) {
                return fuzzy.get(0).getId();
            }
        }
        return null;
    }

    /**
     * 给曲线上的「坑」写人话：减载周掉下去是故意的；中间空白是没练，不是受伤。
     */
    private List<ChartNote> buildChartNotes(long userId, LocalDate from,
                                            Map<String, List<ChartPoint>> series) {
        List<ChartNote> notes = new ArrayList<>();
        // 1) 会话备注里的减载周
        List<WorkoutSession> sessions = sessionMapper.selectAllFromDate(userId, from);
        LinkedHashMap<String, LocalDate> deloadWeeks = new LinkedHashMap<>();
        for (WorkoutSession ws : sessions) {
            if (ws.getWorkoutDate() == null || !StringUtils.hasText(ws.getNote())) {
                continue;
            }
            if (ws.getNote().contains("减载")) {
                LocalDate monday = ws.getWorkoutDate()
                        .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
                deloadWeeks.putIfAbsent(monday.toString(), monday);
            }
        }
        // 多段减载合成一条说明，别复读「减载周…」贴两遍
        if (!deloadWeeks.isEmpty()) {
            List<LocalDate> mondays = new ArrayList<>(deloadWeeks.values());
            LocalDate first = mondays.get(0);
            LocalDate last = mondays.get(mondays.size() - 1);
            String when = mondays.size() == 1
                    ? first.toString()
                    : ("共 " + mondays.size() + " 段，约 " + first + " 起");
            notes.add(new ChartNote(
                    first.toString(),
                    last.plusDays(6).toString(),
                    "淡色区间是减载周（" + when + "）：重量故意打到约八成蓄力，三条线一起掉是正常的，不是退步",
                    "deload"));
        }
        // 2) 相邻训练日间隔 ≥ 9 天 → 合成一条「中间没练」说明（取最长空窗）
        java.util.TreeSet<LocalDate> allDays = new java.util.TreeSet<>();
        for (List<ChartPoint> pts : series.values()) {
            for (ChartPoint p : pts) {
                try {
                    allDays.add(LocalDate.parse(p.x()));
                } catch (Exception ignored) {
                    // 坏日期跳过
                }
            }
        }
        LocalDate prev = null;
        LocalDate gapFrom = null;
        LocalDate gapTo = null;
        long bestGap = 0;
        for (LocalDate day : allDays) {
            if (prev != null) {
                long gap = java.time.temporal.ChronoUnit.DAYS.between(prev, day);
                if (gap >= 9 && gap > bestGap) {
                    bestGap = gap;
                    gapFrom = prev.plusDays(1);
                    gapTo = day.minusDays(1);
                }
            }
            prev = day;
        }
        if (gapFrom != null && gapTo != null) {
            final LocalDate gapStart = gapFrom;
            final LocalDate gapEnd = gapTo;
            boolean coveredByDeload = false;
            for (ChartNote o : notes) {
                if (!"deload".equals(o.kind())) {
                    continue;
                }
                if (!LocalDate.parse(o.to()).isBefore(gapStart)
                        && !LocalDate.parse(o.from()).isAfter(gapEnd)) {
                    coveredByDeload = true;
                    break;
                }
            }
            if (!coveredByDeload) {
                notes.add(new ChartNote(
                        gapStart.toString(),
                        gapEnd.toString(),
                        "最长有 " + (bestGap - 1) + " 天没有三大项记录（出差/请假一类），空白不是受伤断崖",
                        "gap"));
            }
        }
        return notes;
    }

    /** 与 AI 工具一致：有入库 e1rm 就用，否则 TrainMetrics.e1rm 现场算 */
    private static double resolveE1rm(SetLog s) {
        if (s.getE1rm() != null) {
            return s.getE1rm().doubleValue();
        }
        Double rpe = s.getRpe() == null ? null : s.getRpe().doubleValue();
        return TrainMetrics.e1rm(s.getWeightKg().doubleValue(), s.getReps(), rpe);
    }

    public List<WeeklyVolumePoint> weeklyVolume(long userId, int weeks) {
        int w = Math.min(Math.max(weeks, 1), 26);
        LocalDate from = LocalDate.now().minusWeeks(w);
        List<SetLog> logs = setLogMapper.selectByUserAndRange(userId,
                from.atStartOfDay(), LocalDateTime.now());
        Map<String, Double> byWeek = new LinkedHashMap<>();
        for (SetLog s : logs) {
            if (s.getWeightKg() == null || s.getReps() == null || s.getPerformedAt() == null) {
                continue;
            }
            if (s.getIsWarmup() != null && s.getIsWarmup() == 1) {
                continue;
            }
            // 用 ISO 周年标识周：yyyy-Www
            LocalDate d = s.getPerformedAt().toLocalDate();
            String key = d.getYear() + "-W" + String.format("%02d", d.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()));
            byWeek.merge(key, s.getWeightKg().doubleValue() * s.getReps(), Double::sum);
        }
        return byWeek.entrySet().stream()
                .map(e -> new WeeklyVolumePoint(e.getKey(), Math.round(e.getValue() * 10) / 10.0))
                .toList();
    }

    public List<ChartPoint> bodyWeightTrend(long userId, int weeks) {
        int w = Math.min(Math.max(weeks, 1), 52);
        LocalDate today = LocalDate.now();
        return bodyRecordMapper.selectByRange(userId, today.minusWeeks(w), today).stream()
                .filter(b -> b.getWeightKg() != null)
                .map(b -> new ChartPoint(b.getRecordDate().toString(), b.getWeightKg().doubleValue()))
                .toList();
    }

    // ==================== 辅助 ====================

    private Map<Long, Exercise> loadExercises(java.util.Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return exerciseMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Exercise::getId, e -> e, (a, b) -> a));
    }

    /**
     * 从用户练过的动作里挑最贴关键词的一个。
     * 必须用相关度排序，否则「卧推」可能误命中「上斜杠铃卧推」，图表和 AI 对不上。
     */
    private static Long matchExerciseId(Map<Long, Exercise> all, String[] keywords) {
        Exercise best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Exercise e : all.values()) {
            for (String kw : keywords) {
                int score = relevanceScore(e, kw);
                if (score < bestScore) {
                    bestScore = score;
                    best = e;
                }
            }
        }
        return best == null || bestScore >= 500 ? null : best.getId();
    }

    // ==================== 训练历史 / 建议重量 / 习惯 ====================

    /** 近 N 次训练课摘要（日历/列表） */
    public List<SessionSummary> historySessions(long userId, int limit) {
        int lim = Math.max(1, Math.min(limit, 90));
        List<WorkoutSession> sessions = sessionMapper.selectRecent(userId, lim);
        List<SessionSummary> out = new ArrayList<>();
        for (WorkoutSession ws : sessions) {
            List<SetLog> logs = setLogMapper.selectBySession(ws.getId());
            Map<Long, Exercise> exMap = loadExercises(logs.stream()
                    .map(SetLog::getExerciseId).filter(Objects::nonNull).collect(Collectors.toSet()));
            LinkedHashMap<Long, Integer> setCounts = new LinkedHashMap<>();
            double volume = 0;
            for (SetLog s : logs) {
                if (s.getIsWarmup() != null && s.getIsWarmup() == 1) {
                    continue;
                }
                setCounts.merge(s.getExerciseId(), 1, Integer::sum);
                if (s.getWeightKg() != null && s.getReps() != null) {
                    volume += s.getWeightKg().doubleValue() * s.getReps();
                }
            }
            List<String> names = setCounts.keySet().stream()
                    .map(id -> {
                        Exercise e = exMap.get(id);
                        return e == null ? ("动作#" + id) : e.getName();
                    })
                    .limit(6)
                    .toList();
            out.add(new SessionSummary(
                    ws.getId(),
                    ws.getWorkoutDate() == null ? "" : ws.getWorkoutDate().toString(),
                    ws.getSessionType(),
                    sessionTypeLabel(ws.getSessionType()),
                    ws.getIsDemo() != null && ws.getIsDemo() == 1,
                    logs.size(),
                    setCounts.values().stream().mapToInt(Integer::intValue).sum(),
                    Math.round(volume * 10.0) / 10.0,
                    names));
        }
        return out;
    }

    /** 某次课全部组 */
    public SessionDetail sessionDetail(long userId, long sessionId) {
        WorkoutSession ws = sessionMapper.selectById(sessionId);
        if (ws == null || !Objects.equals(ws.getUserId(), userId)
                || (ws.getDeleted() != null && ws.getDeleted() == 1)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "找不到这次训练");
        }
        List<SetLog> logs = setLogMapper.selectBySession(sessionId);
        Map<Long, Exercise> exMap = loadExercises(logs.stream()
                .map(SetLog::getExerciseId).filter(Objects::nonNull).collect(Collectors.toSet()));
        List<SetView> sets = logs.stream().map(s -> {
            Exercise ex = exMap.get(s.getExerciseId());
            return new SetView(s.getId(), s.getExerciseId(),
                    ex == null ? "" : ex.getName(),
                    s.getSetIndex() == null ? 0 : s.getSetIndex(),
                    s.getWeightKg() == null ? 0 : s.getWeightKg().doubleValue(),
                    s.getReps() == null ? 0 : s.getReps(),
                    s.getRpe() == null ? null : s.getRpe().doubleValue(),
                    s.getIsWarmup() != null && s.getIsWarmup() == 1,
                    s.getE1rm() == null ? null : s.getE1rm().doubleValue());
        }).toList();
        return new SessionDetail(
                ws.getId(),
                ws.getWorkoutDate() == null ? "" : ws.getWorkoutDate().toString(),
                ws.getSessionType(),
                sessionTypeLabel(ws.getSessionType()),
                ws.getIsDemo() != null && ws.getIsDemo() == 1,
                sets);
    }

    /** 删一组（只能删自己的） */
    @Transactional
    public void deleteSet(long userId, long setId) {
        SetLog log = setLogMapper.selectById(setId);
        if (log == null || !Objects.equals(log.getUserId(), userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "组记录不存在");
        }
        setLogMapper.deleteById(setId);
    }

    /**
     * 整课复用：返回该课各动作的处方（目标重量×次数×组数），前端填入今日打卡。
     */
    public List<ReuseExercise> reuseSession(long userId, long sessionId) {
        SessionDetail detail = sessionDetail(userId, sessionId);
        LinkedHashMap<Long, ReuseExercise> map = new LinkedHashMap<>();
        for (SetView s : detail.sets()) {
            if (s.warmup()) {
                continue;
            }
            ReuseExercise cur = map.get(s.exerciseId());
            if (cur == null) {
                map.put(s.exerciseId(), new ReuseExercise(
                        s.exerciseId(), s.exerciseName(), s.weightKg(), s.reps(), 1));
            } else {
                // 取最重一组当目标重量，组数累加
                double w = Math.max(cur.targetWeightKg(), s.weightKg());
                int reps = s.weightKg() >= cur.targetWeightKg() ? s.reps() : cur.targetReps();
                map.put(s.exerciseId(), new ReuseExercise(
                        s.exerciseId(), s.exerciseName(), w, reps, cur.targetSets() + 1));
            }
        }
        return new ArrayList<>(map.values());
    }

    /**
     * 下一组建议重量：确定性规则。
     * 次数打满（≥目标）→ +2.5kg；差 1 次 → 维持；更差 → −2.5kg。
     */
    public NextWeightSuggestion suggestNextWeight(long userId, long exerciseId, Integer targetReps) {
        Exercise ex = exerciseMapper.selectById(exerciseId);
        if (ex == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "动作不存在");
        }
        int goalReps = targetReps == null || targetReps <= 0 ? 5 : targetReps;
        List<SetLog> recent = setLogMapper.selectRecentByExercise(userId, exerciseId, 40);
        SetLog last = recent.stream()
                .filter(s -> s.getIsWarmup() == null || s.getIsWarmup() == 0)
                .findFirst()
                .orElse(null);
        if (last == null || last.getWeightKg() == null || last.getReps() == null) {
            return new NextWeightSuggestion(
                    false, ex.getName(), null, goalReps, null, null,
                    "还没有这个动作的正式组，先按空杠或自选重量记一组");
        }
        double lastW = last.getWeightKg().doubleValue();
        int lastR = last.getReps();
        double suggested;
        String reason;
        if (lastR >= goalReps) {
            suggested = round2p5(lastW + 2.5);
            reason = "上次 " + trimNum(lastW) + "kg × " + lastR
                    + " 已打满目标 " + goalReps + " 次 → 建议加到 " + trimNum(suggested) + "kg";
        } else if (lastR >= goalReps - 1) {
            suggested = lastW;
            reason = "上次 " + trimNum(lastW) + "kg × " + lastR
                    + "，差一点点打满 → 维持 " + trimNum(suggested) + "kg 再冲";
        } else {
            suggested = Math.max(0, round2p5(lastW - 2.5));
            reason = "上次 " + trimNum(lastW) + "kg × " + lastR
                    + " 明显没打满 → 建议降到 " + trimNum(suggested) + "kg 把次数做完整";
        }
        return new NextWeightSuggestion(
                true, ex.getName(), lastW, lastR, suggested, goalReps, reason);
    }

    /** 连续打卡 / 本周完成率 */
    public HabitStats habitStats(long userId) {
        LocalDate today = LocalDate.now();
        List<LocalDate> dates = sessionMapper.selectRealDatesFrom(userId, today.minusDays(120));
        int streak = 0;
        LocalDate cursor = today;
        // 今天没练也不立刻断——从昨天开始往回算；今天练了则含今天
        if (!dates.contains(today)) {
            cursor = today.minusDays(1);
        }
        while (dates.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        LocalDate weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        long trainedThisWeek = dates.stream().filter(d -> !d.isBefore(weekStart) && !d.isAfter(today)).count();
        int weekGoal = 4;
        double weekRate = Math.min(1.0, trainedThisWeek / (double) weekGoal);
        return new HabitStats(streak, (int) trainedThisWeek, weekGoal,
                Math.round(weekRate * 1000.0) / 10.0,
                streak > 0
                        ? ("已连续打卡 " + streak + " 天 · 本周 " + trainedThisWeek + "/" + weekGoal + " 练次")
                        : ("本周 " + trainedThisWeek + "/" + weekGoal + " 练次——今天记一组就能续上"));
    }

    private static String sessionTypeLabel(Integer t) {
        if (t == null) {
            return "训练";
        }
        return switch (t) {
            case 1 -> "推";
            case 2 -> "拉";
            case 3 -> "腿";
            case 4 -> "上肢";
            case 5 -> "下肢";
            case 6 -> "全身";
            case 7 -> "有氧";
            default -> "训练";
        };
    }

    private static double round2p5(double v) {
        return Math.round(v * 2.0) / 2.0;
    }

    private static String trimNum(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) {
            return String.valueOf((long) Math.rint(v));
        }
        return String.valueOf(v);
    }

    // ==================== DTO ====================

    public record SessionSummary(Long sessionId, String date, Integer sessionType, String sessionTypeLabel,
                                 boolean demo, int totalSets, int workingSets, double volumeKg,
                                 List<String> exerciseNames) {
    }

    public record SessionDetail(Long sessionId, String date, Integer sessionType, String sessionTypeLabel,
                                boolean demo, List<SetView> sets) {
    }

    public record ReuseExercise(Long exerciseId, String name, double targetWeightKg,
                                int targetReps, int targetSets) {
    }

    public record NextWeightSuggestion(boolean available, String exerciseName,
                                       Double lastWeightKg, Integer lastReps,
                                       Double suggestedWeightKg, Integer targetReps,
                                       String reason) {
    }

    public record HabitStats(int streakDays, int trainedDaysThisWeek, int weekGoal,
                             double weekCompletePct, String tip) {
    }

    public record LastSessionView(Long sessionId, String date, Integer sessionType,
                                  List<LastExercise> exercises) {
    }

    public record LastExercise(Long exerciseId, String name, String muscle,
                               double lastWeightKg, int lastReps, int setCount) {
    }

    public record TodaySessionView(Long sessionId, String date, List<SetView> sets) {
    }

    public record SetView(Long setId, Long exerciseId, String exerciseName, int setIndex,
                          double weightKg, int reps, Double rpe, boolean warmup, Double e1rm) {
    }

    /**
     * sessionId / setId / exerciseId 用 Object：前端应传字符串雪花 ID，
     * 也兼容数字。服务端 {@link #parseId} 统一解析，sessionId 实际被忽略。
     */
    /**
     * username 仅服务端填充（演示锁），前端可不传。
     */
    public record SaveSetRequest(Object sessionId, Object setId, Object exerciseId,
                                 Double weightKg, Integer reps, Double rpe, Boolean warmup,
                                 String username) {
        public SaveSetRequest(Object sessionId, Object setId, Object exerciseId,
                              Double weightKg, Integer reps, Double rpe, Boolean warmup) {
            this(sessionId, setId, exerciseId, weightKg, reps, rpe, warmup, null);
        }
    }

    public record ExerciseView(Long id, String name, Integer equipment, String equipmentLabel,
                               String primaryMuscle, String alias) {
    }

    /** 自定义动作入参 */
    public record CreateExerciseRequest(String name, Integer equipment, String primaryMuscle,
                                        String alias, String steps) {
    }

    public record SaveDietRequest(LocalDate date, Integer mealType, String foodName,
                                  Integer calories, Double proteinG, String note) {
    }

    public record SaveBodyRequest(LocalDate date, Double weightKg, Double bodyFatPct, String note) {
    }

    public record DietDayView(String date, int totalCalories, double totalProteinG,
                              List<DietItemView> items) {
    }

    public record DietItemView(String id, int mealType, String mealLabel,
                               String foodName, int calories, double proteinG) {
    }

    public record BodySnapshot(boolean available, String date, Double weightKg, Double bodyFatPct) {
    }

    public record ExerciseCueView(String id, String name, String primaryMuscle, String equipmentLabel,
                                  String steps, String commonMistakes, String breathing, String safetyTips) {
        public boolean hasAny() {
            return steps != null || commonMistakes != null || breathing != null || safetyTips != null;
        }
    }

    public record ChartPoint(String x, double y) {
    }

    /**
     * @param notes 曲线上的「坑」解释：减载 / 缺练，前端画色带 + 文案，别让用户自己猜
     */
    public record ChartSeries(Map<String, List<ChartPoint>> series, List<ChartNote> notes) {
        public ChartSeries {
            if (notes == null) {
                notes = List.of();
            }
        }
    }

    /** kind=deload|gap */
    public record ChartNote(String from, String to, String label, String kind) {
    }

    public record MuscleVolumeBoard(boolean available, String reason, int weeks,
                                    List<MuscleVolumeItem> items) {
    }

    public record MuscleVolumeItem(String muscle, int setsPerWeek, int mev, int mavUpper, int mrv,
                                   String verdict, String advice) {
    }

    public record WeeklyVolumePoint(String week, double volumeKg) {
    }

    /** 「我的」页平台期提示卡 */
    public record PlateauAlert(String exerciseKey, String exerciseName,
                               boolean plateau, boolean justBrokePlateau,
                               int weeksStuck, double currentE1rm, String tip) {
    }
}
