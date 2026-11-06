package com.gym.ai.tool;

import com.gym.common.security.UserContext;
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
import com.gym.training.metric.TrainMetrics.ActivityLevel;
import com.gym.training.metric.TrainMetrics.MuscleGroup;
import com.gym.training.metric.TrainMetrics.SetRecord;
import com.gym.training.metric.TrainMetrics.VolumeVerdict;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 健身智能体的工具箱。
 *
 * <p><b>有没有这个东西，是「聊天机器人」和「智能体」的分界线。</b>
 * 只有 RAG 时，模型拿到的是书上的通识；有了工具，它才能读到这个用户自己的数据，
 * 说出「你卧推最近六周停在 82kg，是平台期」这种需要对私有数据才能下的结论。
 *
 * <p>四条设计约束，缺一条这套工具就会把模型带进沟里：
 *
 * <p>1. <b>userId 不入参。</b>
 *     这是最重要的一条，也是和普通 Function Calling 教程最大的区别。
 *     工具方法的入参会被翻译成模型可见的 JSON Schema，
 *     把 userId 写进去，等于允许用户用一句话把查询指到别人头上。
 *     所以四个数据类工具没有一个以 userId 为参数——
 *     用户身份一律在方法内部从 {@link UserContext} 取，
 *     模型无论怎么输出都构造不出一次跨用户的查询。
 *     边界要落在结构上，不能落在 prompt 里，prompt 是建议不是边界。
 *
 * <p>2. <b>只读。</b>全部工具只 SELECT，没有任何一个能写库。
 *     把写操作交给大模型，等于让它凭一句自然语言去改用户数据，
 *     改错了无法回滚也无法追责。需要写入的动作，一定发生在用户点击按钮之后的确定链路里。
 *
 * <p>3. <b>返回结论，不返回原始数据。</b>
 *     工具给的是「算好的 e1RM 序列 + 增长率 + 是否平台」，不是一堆 set_log 行。
 *     让模型去读几十行原始记录再自己算增长率，
 *     等于把最需要精确的部分外包给最不精确的一方。
 *
 * <p>4. <b>数据不足要明说。</b>
 *     样本不够时返回 available=false 并说明原因，
 *     让模型自己说「数据还不够判断」，而不是硬凑一个结论出来安慰用户。
 *
 * <p>工具数量控制在 7 个（力量趋势/肌群容量/近期训练/体重/营养/个人最好/当日饮食）。
 *     每多一个工具都要在每次请求里占用一段 schema 的 token，
 *     而且工具越相似，模型选错的概率越高。宁可少而准。
 *
 * <p><b>关于 userId：</b>需求文档里工具签名常写成 getXxx(userId, …)，
 *     但 userId 绝不能进 @ToolParam——否则模型可见、可被诱导查别人。
 *     身份一律从 {@link UserContext} 取，这是「智能体」和「会越权的聊天机器人」的分界。
 */
@Service
public class TrainingTools {

    private static final int MAX_WEEKS = 52;
    private static final int DEFAULT_TREND_WEEKS = 8;
    private static final int DEFAULT_VOLUME_WEEKS = 4;
    private static final int DEFAULT_RECENT_DAYS = 14;
    private static final int MAX_SERIES_POINTS = 20;
    private static final int MAX_WORKOUTS = 10;

    private final SetLogMapper setLogMapper;
    private final ExerciseMapper exerciseMapper;
    private final WorkoutSessionMapper sessionMapper;
    private final BodyRecordMapper bodyRecordMapper;
    private final DietLogMapper dietLogMapper;

    public TrainingTools(SetLogMapper setLogMapper,
                         ExerciseMapper exerciseMapper,
                         WorkoutSessionMapper sessionMapper,
                         BodyRecordMapper bodyRecordMapper,
                         DietLogMapper dietLogMapper) {
        this.setLogMapper = setLogMapper;
        this.exerciseMapper = exerciseMapper;
        this.sessionMapper = sessionMapper;
        this.bodyRecordMapper = bodyRecordMapper;
        this.dietLogMapper = dietLogMapper;
    }

    // ==================== 工具 1：力量趋势 ====================

    /**
     * 用户问「我卧推卡住了」时，模型必须先调它。
     * 这是整个工具箱里被调用频率最高的一个。
     */
    @Tool(description = "查询用户某个动作最近几周的力量趋势：返回推算1RM序列、总增长幅度、" +
            "每次训练的平均增幅、是否进入平台期。当用户问『XX卡住了』『XX没进步了』" +
            "'还能涨吗'『我是不是到瓶颈了』时，必须先调用它，不要凭印象回答。")
    public StrengthTrend getStrengthTrend(
            @ToolParam(description = "动作名称，支持部分匹配，例如『卧推』『深蹲』『硬拉』『引体向上』") String exerciseName,
            @ToolParam(description = "回看周数，1-52之间，不传按8周处理", required = false) Integer weeks) {

        long userId = UserContext.requireUserId();
        int w = clampWeeks(weeks, DEFAULT_TREND_WEEKS);
        Exercise ex = matchExercise(exerciseName);
        if (ex == null) {
            return StrengthTrend.notFound(exerciseName);
        }
        List<SetLog> logs = workingLogsOfExercise(userId, ex.getId(), w);
        if (logs.isEmpty()) {
            return new StrengthTrend(false,
                    "近" + w + "周内没有「" + ex.getName() + "」的正式组记录（热身组不计）",
                    ex.getName(), 0, List.of(), 0.0, 0.0, false, false);
        }

        // 每次训练只取该次最好的一组：用户当天冲的那组才代表真实力量水平，
        // 后面的掉重组是疲劳表现，混进去会把趋势算成负增长。
        LinkedHashMap<Long, SetLog> bestSetPerSession = new LinkedHashMap<>();
        for (SetLog s : logs) {
            SetLog prev = bestSetPerSession.get(s.getSessionId());
            if (prev == null
                    || e1rmOf(s) > e1rmOf(prev)) {
                bestSetPerSession.put(s.getSessionId(), s);
            }
        }
        // LinkedHashMap 的插入顺序就是遍历顺序，而遍历已按时间排序，这里直接是时间序列
        List<Double> series = bestSetPerSession.values().stream()
                .map(TrainingTools::e1rmOf).toList();
        if (series.size() > MAX_SERIES_POINTS) {
            series = series.subList(series.size() - MAX_SERIES_POINTS, series.size());
        }
        if (series.size() < 2) {
            // 只有 1 个训练日：把「今天练了什么」写进 reason，禁止模型编造历史或假破纪录
            SetLog only = bestSetPerSession.values().iterator().next();
            int setCount = (int) logs.stream()
                    .filter(s -> only.getSessionId() != null && only.getSessionId().equals(s.getSessionId()))
                    .count();
            String day = only.getPerformedAt() == null ? "该日"
                    : only.getPerformedAt().toLocalDate().toString();
            double wKg = round1(only.getWeightKg().doubleValue());
            String snapshot = day + " 最佳 " + wKg + "kg×" + only.getReps()
                    + "（约能推 " + round1(series.get(0)) + "kg），共 " + setCount + " 组正式组";
            return new StrengthTrend(false,
                    "近" + w + "周「" + ex.getName() + "」只有 1 个训练日——" + snapshot
                            + "。这就是该用户目前的全部历史，没有更早的重量可对比。"
                            + "必须答复：今天练了 " + wKg + "×" + only.getReps()
                            + "；还不能判断涨跌/平台期/破纪录；下次再记才能比较。"
                            + "严禁编造 88kg、历史最好、破纪录、平台期或别人的成绩；"
                            + "严禁批评『练太少』",
                    ex.getName(), 1, round1(series), 0.0, 0.0, false,
                    confidentEnough(bestSetPerSession.values()));
        }

        double first = series.get(0);
        double last = series.get(series.size() - 1);
        double growthPct = first > 0 ? (last - first) / first * 100 : 0;
        boolean plateauNow = TrainMetrics.isPlateau(series);
        String reason = "";
        // 去掉最后一次后仍是平台、加上最后一次就不是 → 刚破平台，故事不能被冲掉
        if (!plateauNow && series.size() >= 4) {
            List<Double> prior = series.subList(0, series.size() - 1);
            if (TrainMetrics.isPlateau(prior)) {
                reason = "此前 e1RM 序列处于平台期，最近一次刚突破。"
                        + "必须说「刚破平台期」，禁止说「没卡住 / 不存在平台」。";
            }
        }
        return new StrengthTrend(true, reason, ex.getName(), series.size(), round1(series),
                round1(growthPct), round2(TrainMetrics.slopePerSession(series)),
                plateauNow,
                confidentEnough(bestSetPerSession.values()));
    }

    /**
     * 这组 e1RM 值敢不敢当成力量指标。
     *
     * <p>弯举做到 12 次时，Epley 外推出来的「1RM」连方向都不可信——
     * 那已经是在测耐力而不是力量了。
     * 工具必须把这个判断一起交给模型，否则模型会拿着弯举的 e1RM 去谈力量水平，
     * 用户还会觉得「系统说我弯举 1RM 40kg，挺有道理」。
     */
    private static boolean confidentEnough(java.util.Collection<SetLog> bestSets) {
        return bestSets.stream().allMatch(
                s -> TrainMetrics.e1rmConfident(s.getReps(), rpeOf(s)));
    }

    // ==================== 工具 2：肌群容量 ====================

    @Tool(description = "统计用户近几周各肌群的周均有效训练组数，与该肌群的最低有效容量(MEV)、" +
            "最佳增肌区间(MAV)、最大可恢复容量(MRV)对比，给出容量不足/合适/过量的判定。" +
            "当用户问『我胸练够了吗』『是不是练太多了』『该加量还是减量』『手臂怎么练都不长』时调用。")
    public MuscleVolumeReport getMuscleVolume(
            @ToolParam(description = "回看周数，1-52之间，不传按4周处理", required = false) Integer weeks) {

        long userId = UserContext.requireUserId();
        int w = clampWeeks(weeks, DEFAULT_VOLUME_WEEKS);
        List<SetRecord> records = setRecordsOfRange(userId, w);
        if (records.isEmpty()) {
            return new MuscleVolumeReport(false, "近" + w + "周内没有任何训练记录", w, List.of());
        }

        // 基准只算一次，十个肌群共用。
        // 每个肌群各算一遍的话，同一个动作的集合会被重复扫十次
        Map<Long, Double> baseline = TrainMetrics.e1rmBaseline(records);

        List<MuscleVolume> items = new ArrayList<>();
        for (MuscleGroup group : MuscleGroup.values()) {
            int total = TrainMetrics.weeklyWorkingSets(records, group, baseline);
            if (total <= 0) {
                continue;
            }
            // 周均 = 区间内总有效组数 / 周数。除以周数是为了让不同回看窗口的结论可比
            int perWeek = (int) Math.round(total / (double) w);
            VolumeVerdict verdict = TrainMetrics.verdict(group, perWeek);
            items.add(new MuscleVolume(group.label(), perWeek, group.mev(), group.mavUpper(),
                    group.mrv(), verdict.name(), verdict.advice()));
        }
        return new MuscleVolumeReport(true, "", w, items);
    }

    // ==================== 工具 3：近期训练 ====================

    @Tool(description = "查看用户最近几天的训练概况：每次练了哪些动作、做了几组、总容量多少、" +
            "距离上一次训练已经过去几天。当用户问『我最近练得怎么样』『上次练到哪了』" +
            "'我是不是偷懒了'时调用。")
    public RecentWorkouts getRecentWorkouts(
            @ToolParam(description = "回看天数，1-180之间，不传按14天处理", required = false) Integer days) {

        long userId = UserContext.requireUserId();
        int d = clamp(days == null ? DEFAULT_RECENT_DAYS : days, 1, 180);
        LocalDateTime now = LocalDateTime.now();
        List<SetLog> logs = setLogMapper.selectByUserAndRange(userId, now.minusDays(d), now);
        if (logs.isEmpty()) {
            return new RecentWorkouts(false, "近" + d + "天内没有训练记录", 0, -1, List.of());
        }

        Map<Long, List<SetLog>> bySession = new LinkedHashMap<>();
        Map<Long, LocalDateTime> earliest = new HashMap<>();
        for (SetLog s : logs) {
            if (s.getSessionId() == null || s.getWeightKg() == null || s.getReps() == null) {
                continue;
            }
            bySession.computeIfAbsent(s.getSessionId(), k -> new ArrayList<>()).add(s);
            if (s.getPerformedAt() != null) {
                earliest.merge(s.getSessionId(), s.getPerformedAt(),
                        (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        List<Long> ordered = bySession.keySet().stream()
                .sorted(Comparator.comparing(
                        (Long id) -> earliest.getOrDefault(id, LocalDateTime.now())).reversed())
                .limit(MAX_WORKOUTS)
                .toList();

        Map<Long, WorkoutSession> sessions = sessionMapper.selectBatchIds(ordered).stream()
                .collect(Collectors.toMap(WorkoutSession::getId, s -> s, (a, b) -> a));
        Map<Long, Exercise> exercises = loadExercises(
                logs.stream().map(SetLog::getExerciseId).filter(Objects::nonNull)
                        .collect(Collectors.toSet()));

        List<WorkoutBrief> briefs = new ArrayList<>();
        for (Long sid : ordered) {
            List<SetLog> group = bySession.get(sid);
            WorkoutSession ws = sessions.get(sid);
            LocalDate date = ws != null && ws.getWorkoutDate() != null
                    ? ws.getWorkoutDate()
                    : earliest.getOrDefault(sid, now).toLocalDate();
            List<String> names = group.stream()
                    .map(s -> exercises.get(s.getExerciseId()))
                    .filter(Objects::nonNull)
                    .map(Exercise::getName)
                    .distinct()
                    .toList();
            // 复用循环外批量加载好的字典，不要在循环里逐组 selectById。
            // 一次训练十几组、回看十次，逐组查就是一百多次单表查询，
            // 而这些动作主数据在请求内根本不变，属于典型的 N+1
            double volume = TrainMetrics.volume(group.stream().map(s -> toRecord(s, exercises)).toList());
            briefs.add(new WorkoutBrief(date.toString(), names, group.size(),
                    round1(volume), ws != null && ws.getDurationMin() != null ? ws.getDurationMin() : 0));
        }

        LocalDate lastDate = LocalDate.parse(briefs.get(0).date());
        int daysSinceLast = (int) ChronoUnit.DAYS.between(lastDate, LocalDate.now());
        return new RecentWorkouts(true, "", bySession.size(), daysSinceLast, briefs);
    }

    // ==================== 工具 4：体重趋势 ====================

    @Tool(description = "查看用户最近几周的体重变化趋势，返回首末体重、总变化率、每周平均变化速度。" +
            "判断减脂或增肌进度时必须先调用它。注意单日体重波动没有参考价值，必须看趋势。")
    public BodyWeightTrend getBodyWeightTrend(
            @ToolParam(description = "回看周数，1-52之间，不传按8周处理", required = false) Integer weeks) {

        long userId = UserContext.requireUserId();
        int w = clampWeeks(weeks, DEFAULT_TREND_WEEKS);
        LocalDate today = LocalDate.now();
        List<BodyRecord> records = bodyRecordMapper.selectByRange(userId, today.minusWeeks(w), today);
        List<Double> weights = records.stream()
                .map(BodyRecord::getWeightKg)
                .filter(Objects::nonNull)
                .map(BigDecimal::doubleValue)
                .filter(v -> v > 0)
                .toList();
        if (weights.size() < 2) {
            return new BodyWeightTrend(false,
                    "近" + w + "周内的有效体重记录不足 2 条，无法判断趋势", 0, 0, 0, 0, 0, "");
        }

        double first = weights.get(0);
        double last = weights.get(weights.size() - 1);
        double changePct = first > 0 ? (last - first) / first * 100 : 0;
        double weeksSpan = Math.max(1.0,
                ChronoUnit.DAYS.between(today.minusWeeks(w), today) / 7.0);
        double ratePerWeek = (last - first) / weeksSpan;
        String direction = ratePerWeek < -0.1 ? "下降"
                : ratePerWeek > 0.1 ? "上升" : "基本持平";
        return new BodyWeightTrend(true, "", weights.size(), round1(first), round1(last),
                round1(changePct), round2(ratePerWeek), direction);
    }

    // ==================== 工具 5：个人最好成绩 ====================

    /**
     * 用户问「卧推怎么涨重量」时必须先调它。
     * 和 getStrengthTrend 的分工：本工具给「当前最好是多少、卡了几周」的快照；
     * 趋势工具给「这一段时间怎么走过来的」曲线。涨重量建议两个都看。
     */
    @Tool(description = "查询用户某个动作的个人最好成绩：历史最大重量、对应次数、历史最高推算1RM、" +
            "最近一次该动作的重量×次数、以及当前工作重量已停滞了几周。" +
            "当用户问『卧推怎么涨』『我XX能推多少』『帮我看看现在水平』时必须先调用，" +
            "禁止凭印象给通用建议。")
    public PersonalBest getPersonalBest(
            @ToolParam(description = "动作名称，支持部分匹配，例如『卧推』『深蹲』『硬拉』") String exerciseName) {

        long userId = UserContext.requireUserId();
        Exercise ex = matchExercise(exerciseName);
        if (ex == null) {
            return PersonalBest.notFound(exerciseName);
        }
        // 取该动作全部正式组（上限 200 次训练窗口够覆盖个人纪录）
        List<SetLog> logs = setLogMapper.selectRecentByExercise(userId, ex.getId(), 500).stream()
                .filter(s -> s.getWeightKg() != null && s.getReps() != null && s.getReps() > 0)
                .filter(s -> s.getIsWarmup() == null || s.getIsWarmup() == 0)
                .toList();
        if (logs.isEmpty()) {
            return new PersonalBest(false, "没有「" + ex.getName() + "」的正式组记录",
                    ex.getName(), 0, 0, 0, 0, 0, "", 0, true, false, 0);
        }

        SetLog bestWeight = logs.stream()
                .max(Comparator.comparing((SetLog s) -> s.getWeightKg().doubleValue())
                        .thenComparingInt(SetLog::getReps))
                .orElseThrow();
        SetLog bestE1rm = logs.stream()
                .max(Comparator.comparingDouble(TrainingTools::e1rmOf))
                .orElseThrow();
        // selectRecentByExercise 已按 performed_at DESC，第一条即最近一次
        SetLog latest = logs.get(0);

        // 按训练日去重：只有 1 天 = 新号刚起步，禁止「破纪录」话术
        long distinctDays = logs.stream()
                .map(s -> s.getPerformedAt() == null ? null : s.getPerformedAt().toLocalDate())
                .filter(Objects::nonNull)
                .distinct()
                .count();
        boolean firstDayOnly = distinctDays <= 1;

        // 停滞周数：从最近一次往前扫，工作重量（当天最好一组的重量）连续不变的跨度
        int stuckWeeks = firstDayOnly ? 0
                : weeksStuckAtWeight(logs, latest.getWeightKg().doubleValue());

        // 刚破平台：最新一次明显高于「此前」最好，且此前同重量已卡 ≥2 周
        // ——避免模型只看见新纪录就说「没卡住」，把平台期故事冲掉
        boolean justBroke = false;
        int stuckBeforeBreak = 0;
        double plateauWeightKg = 0;
        if (!firstDayOnly && latest.getSessionId() != null) {
            Long latestSid = latest.getSessionId();
            List<SetLog> prior = logs.stream()
                    .filter(s -> s.getSessionId() != null && !latestSid.equals(s.getSessionId()))
                    .toList();
            if (prior.size() >= 2) {
                double priorBestW = prior.stream()
                        .mapToDouble(s -> s.getWeightKg().doubleValue())
                        .max().orElse(0);
                double priorBestE = prior.stream().mapToDouble(TrainingTools::e1rmOf).max().orElse(0);
                double latestW = latest.getWeightKg().doubleValue();
                double latestE = e1rmOf(latest);
                boolean brokeWeight = latestW > priorBestW + 0.6;
                boolean brokeE1rm = priorBestE > 0
                        && (latestE - priorBestE) / priorBestE >= 0.01;
                if (brokeWeight || brokeE1rm) {
                    Long priorSid = prior.get(0).getSessionId();
                    double priorWorkW = 0;
                    for (SetLog s : prior) {
                        if (priorSid != null && priorSid.equals(s.getSessionId())) {
                            priorWorkW = Math.max(priorWorkW, s.getWeightKg().doubleValue());
                        } else {
                            break;
                        }
                    }
                    stuckBeforeBreak = weeksStuckAtWeight(prior, priorWorkW);
                    justBroke = stuckBeforeBreak >= 2;
                    plateauWeightKg = priorWorkW;
                }
            }
        }

        String reason;
        if (firstDayOnly) {
            reason = "「" + ex.getName() + "」目前只有 1 个训练日的记录，今天 "
                    + round1(latest.getWeightKg().doubleValue()) + "kg×" + latest.getReps()
                    + " 既是最近一次也是历史最好——不是破了更早的纪录。"
                    + "禁止编造更早的重量（如 88kg），禁止说破纪录/卡住/平台期；"
                    + "应鼓励记下一次，有 ≥2 个训练日再谈涨跌";
        } else if (justBroke) {
            reason = "「" + ex.getName() + "」刚突破此前 "
                    + stuckBeforeBreak + " 周的平台（突破前工作重量约 "
                    + round1(plateauWeightKg) + "kg），今天 "
                    + round1(latest.getWeightKg().doubleValue()) + "kg×" + latest.getReps()
                    + "。结论必须说「刚破平台期」，禁止说「没卡住 / 一直在涨 / 不存在平台」。";
        } else {
            reason = "";
        }

        return new PersonalBest(true, reason, ex.getName(),
                round1(bestWeight.getWeightKg().doubleValue()),
                bestWeight.getReps(),
                round1(e1rmOf(bestE1rm)),
                round1(latest.getWeightKg().doubleValue()),
                latest.getReps(),
                latest.getPerformedAt() == null ? "" : latest.getPerformedAt().toLocalDate().toString(),
                stuckWeeks,
                firstDayOnly,
                justBroke,
                stuckBeforeBreak);
    }

    /**
     * 从最近一次训练往前，统计工作重量（按 session 取当天最大重量）连续不变了几周。
     * 用来支撑「你卧推 82.5kg 卡了 4 周」这类结论——这是通识回答给不出的数字。
     */
    private static int weeksStuckAtWeight(List<SetLog> logsDesc, double currentWeight) {
        // 按 session 聚合当天最大重量，保持时间倒序
        LinkedHashMap<Long, Double> maxWeightBySession = new LinkedHashMap<>();
        LinkedHashMap<Long, LocalDateTime> timeBySession = new LinkedHashMap<>();
        for (SetLog s : logsDesc) {
            if (s.getSessionId() == null) {
                continue;
            }
            double w = s.getWeightKg().doubleValue();
            maxWeightBySession.merge(s.getSessionId(), w, Math::max);
            if (s.getPerformedAt() != null) {
                timeBySession.merge(s.getSessionId(), s.getPerformedAt(),
                        (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        LocalDateTime firstStuck = null;
        LocalDateTime lastStuck = null;
        for (Map.Entry<Long, Double> e : maxWeightBySession.entrySet()) {
            if (Math.abs(e.getValue() - currentWeight) > 0.6) {
                // 允许 0.5kg 浮动（杠铃片精度），超过说明重量变了，停滞结束
                break;
            }
            LocalDateTime t = timeBySession.get(e.getKey());
            if (t == null) {
                continue;
            }
            if (firstStuck == null) {
                firstStuck = t;
            }
            lastStuck = t;
        }
        if (firstStuck == null || lastStuck == null) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(lastStuck.toLocalDate(), firstStuck.toLocalDate());
        return (int) Math.max(0, days / 7);
    }

    // ==================== 工具 6：当日饮食 ====================

    @Tool(description = "查询用户某一天的饮食记录：每餐吃了什么、热量与蛋白合计。" +
            "当用户问『今天吃了多少』『热量够不够』『蛋白达标了吗』时必须先调用。" +
            "日期不传则查今天。")
    public CurrentDietLog getCurrentDietLog(
            @ToolParam(description = "日期，格式 yyyy-MM-dd，不传按今天", required = false) String date) {

        long userId = UserContext.requireUserId();
        LocalDate day;
        try {
            day = (date == null || date.isBlank()) ? LocalDate.now() : LocalDate.parse(date.trim());
        } catch (Exception e) {
            return new CurrentDietLog(false, "日期格式错误，请用 yyyy-MM-dd", "", 0, 0, 0, List.of());
        }
        List<DietLog> logs = dietLogMapper.selectByUserAndDate(userId, day);
        if (logs.isEmpty()) {
            return new CurrentDietLog(false, day + " 没有饮食记录", day.toString(), 0, 0, 0, List.of());
        }
        int totalCal = logs.stream().mapToInt(d -> d.getCalories() == null ? 0 : d.getCalories()).sum();
        double totalProtein = logs.stream()
                .map(DietLog::getProteinG)
                .filter(Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .sum();
        List<DietItem> items = logs.stream()
                .map(d -> new DietItem(mealLabel(d.getMealType()), d.getFoodName(),
                        d.getCalories() == null ? 0 : d.getCalories(),
                        d.getProteinG() == null ? 0 : round1(d.getProteinG().doubleValue())))
                .toList();
        return new CurrentDietLog(true, "", day.toString(), items.size(), totalCal,
                round1(totalProtein), items);
    }

    private static String mealLabel(Integer type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case 1 -> "早餐";
            case 2 -> "午餐";
            case 3 -> "晚餐";
            case 4 -> "加餐";
            default -> "其他";
        };
    }

    // ==================== 工具 7：热量与营养（纯计算） ====================

    /**
     * 纯计算工具，不查库。存在的意义是：
     * 让模型<b>不要自己算热量</b>。TDEE 和宏量分配是确定性算术，
     * 大模型算这类数字时错得很自信，而且用户看不出来。
     */
    @Tool(description = "根据身体数据与活动水平，计算基础代谢BMR、每日总消耗TDEE，" +
            "并给出减脂(-500kcal)与增肌(+300kcal)两种目标下的热量与三大宏量营养素目标。" +
            "需要给出饮食建议时必须调用它，不要自己估算任何热量数字。" +
            "若要对照用户实际摄入，先调 getCurrentDietLog 再调本工具。")
    public NutritionPlan getNutritionPlan(
            @ToolParam(description = "体重，单位kg") double weightKg,
            @ToolParam(description = "身高，单位cm") double heightCm,
            @ToolParam(description = "年龄") int age,
            @ToolParam(description = "性别，male 或 female") String sex,
            @ToolParam(description = "活动水平：sedentary/light/moderate/high/very_high，" +
                    "不传按 moderate 处理", required = false) String activityLevel) {

        if (weightKg <= 0 || heightCm <= 0 || age <= 0) {
            return new NutritionPlan(false, "体重、身高、年龄必须为正数", 0, 0, 0, 0, null, null);
        }
        boolean male = !"female".equalsIgnoreCase(trimToNull(sex));
        ActivityLevel level = parseActivity(activityLevel);
        double bmr = TrainMetrics.bmr(weightKg, heightCm, age, male);
        double tdee = TrainMetrics.tdee(bmr, level);

        // 减脂 -500kcal/天 ≈ 每周 0.45kg，是掉脂肪而不是掉肌肉的上限速度；
        // 增肌 +300kcal/天，再多长的主要是脂肪。两个数字都是刻意取的保守值
        int cut = (int) Math.round(tdee - 500);
        int bulk = (int) Math.round(tdee + 300);
        return new NutritionPlan(true, "", (int) Math.round(bmr), (int) Math.round(tdee),
                cut, bulk, toGram(TrainMetrics.macro(cut, weightKg)),
                toGram(TrainMetrics.macro(bulk, weightKg)));
    }

    // ==================== 私有辅助 ====================

    /**
     * 动作名模糊匹配。
     * 模型给出的几乎不会是库里的标准名——用户说「卧推」，库里是「杠铃卧推」。
     * 拿标准名去等值匹配必然查不到，模型就会误判成「用户没练过这个动作」。
     */
    private Exercise matchExercise(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        List<Exercise> hits = exerciseMapper.searchByName(name.trim(), 5);
        return hits.isEmpty() ? null : hits.get(0);
    }

    private List<SetLog> workingLogsOfExercise(long userId, Long exerciseId, int weeks) {
        LocalDateTime now = LocalDateTime.now();
        return setLogMapper.selectByUserAndRange(userId, now.minusWeeks(weeks), now).stream()
                .filter(s -> exerciseId.equals(s.getExerciseId()))
                .filter(s -> s.getWeightKg() != null && s.getReps() != null && s.getReps() > 0)
                .filter(s -> s.getIsWarmup() == null || s.getIsWarmup() == 0)
                .sorted(Comparator.comparing(SetLog::getPerformedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * 把区间内的组记录转成算法层的 SetRecord。
     * 肌肉名从动作表带过来——容量是按肌群统计的，
     * 而用户记录的是动作，中间这一层映射必须由代码完成。
     */
    private List<SetRecord> setRecordsOfRange(long userId, int weeks) {
        LocalDateTime now = LocalDateTime.now();
        List<SetLog> logs = setLogMapper.selectByUserAndRange(userId, now.minusWeeks(weeks), now);
        Map<Long, Exercise> exercises = loadExercises(
                logs.stream().map(SetLog::getExerciseId).filter(Objects::nonNull)
                        .collect(Collectors.toSet()));
        return logs.stream()
                .filter(s -> s.getWeightKg() != null && s.getReps() != null)
                .map(s -> {
                    Exercise ex = exercises.get(s.getExerciseId());
                    return new SetRecord(s.getExerciseId(),
                            ex == null ? null : ex.getPrimaryMuscle(),
                            s.getWeightKg().doubleValue(), s.getReps(), rpeOf(s),
                            s.getIsWarmup() != null && s.getIsWarmup() == 1);
                })
                .toList();
    }

    private Map<Long, Exercise> loadExercises(java.util.Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return exerciseMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Exercise::getId, e -> e, (a, b) -> a));
    }

    private static SetRecord toRecord(SetLog s, Map<Long, Exercise> exercises) {
        Exercise ex = exercises.get(s.getExerciseId());
        return new SetRecord(s.getExerciseId(), ex == null ? null : ex.getPrimaryMuscle(),
                s.getWeightKg().doubleValue(), s.getReps(), rpeOf(s),
                s.getIsWarmup() != null && s.getIsWarmup() == 1);
    }

    private static Double rpeOf(SetLog s) {
        return s.getRpe() == null ? null : s.getRpe().doubleValue();
    }

    private static ActivityLevel parseActivity(String raw) {
        if (raw == null || raw.isBlank()) {
            return ActivityLevel.MODERATE;
        }
        String key = raw.trim().toUpperCase();
        for (ActivityLevel level : ActivityLevel.values()) {
            if (level.name().equals(key)) {
                return level;
            }
        }
        return ActivityLevel.MODERATE;
    }

    private static MacroGram toGram(TrainMetrics.Macro macro) {
        return new MacroGram((int) Math.round(macro.proteinG()),
                (int) Math.round(macro.fatG()),
                (int) Math.round(macro.carbG()));
    }

    private static String trimToNull(String s) {
        return s == null ? null : s.trim();
    }

    private static int clampWeeks(Integer weeks, int fallback) {
        return clamp(weeks == null || weeks <= 0 ? fallback : weeks, 1, MAX_WEEKS);
    }

    private static int clamp(int v, int min, int max) {
        return Math.min(Math.max(v, min), max);
    }

    /**
     * e1RM 本身是估算值，保留到小数点后两位是虚假精度。
     * 对外统一保留一位，避免模型把「82.5」和「82.7」当成两个真实存在的差异去解读。
     */
    private static List<Double> round1(List<Double> values) {
        return values.stream().map(TrainingTools::round1).toList();
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double e1rmOf(SetLog s) {
        return TrainMetrics.e1rm(s.getWeightKg().doubleValue(), s.getReps(), rpeOf(s));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ==================== 工具返回值 ====================

    public record StrengthTrend(
            boolean available,
            String reason,
            String exercise,
            int sessionCount,
            List<Double> e1rmSeries,
            double growthPct,
            double perSessionGainKg,
            boolean plateau,
            /** e1RM 值是否落在算法敢用的置信区间内，false 时不要拿它谈力量水平 */
            boolean confident) {

        static StrengthTrend notFound(String keyword) {
            return new StrengthTrend(false, "动作库中找不到与「" + keyword + "」匹配的动作",
                    "", 0, List.of(), 0, 0, false, false);
        }
    }

    public record MuscleVolumeReport(
            boolean available,
            String reason,
            int weeks,
            List<MuscleVolume> groups) {
    }

    public record MuscleVolume(
            String muscle,
            int weeklySets,
            int mev,
            int mavUpper,
            int mrv,
            String verdict,
            String advice) {
    }

    public record RecentWorkouts(
            boolean available,
            String reason,
            int workoutCount,
            int daysSinceLastWorkout,
            List<WorkoutBrief> workouts) {
    }

    public record WorkoutBrief(
            String date,
            List<String> exercises,
            int setCount,
            double volumeKg,
            int durationMin) {
    }

    public record BodyWeightTrend(
            boolean available,
            String reason,
            int recordCount,
            double firstKg,
            double latestKg,
            double changePct,
            double ratePerWeekKg,
            String direction) {
    }

    public record PersonalBest(
            boolean available,
            String reason,
            String exercise,
            double bestWeightKg,
            int bestReps,
            double bestE1rmKg,
            double latestWeightKg,
            int latestReps,
            String latestDate,
            /** 当前工作重量已连续停滞的周数，0 表示刚换重量或数据不足 */
            int weeksStuckAtCurrentWeight,
            /** true=只有 1 个训练日，禁止破纪录/与虚构历史对比 */
            boolean firstTrainingDayOnly,
            /** true=刚突破此前平台，结论要用「刚破平台」而不是「没卡住」 */
            boolean justBrokePlateau,
            /** 突破前卡在旧重量上的周数 */
            int weeksStuckBeforeBreakthrough) {

        static PersonalBest notFound(String keyword) {
            return new PersonalBest(false, "动作库中找不到与「" + keyword + "」匹配的动作",
                    "", 0, 0, 0, 0, 0, "", 0, true, false, 0);
        }
    }

    public record DietItem(String meal, String food, int calories, double proteinG) {
    }

    public record CurrentDietLog(
            boolean available,
            String reason,
            String date,
            int itemCount,
            int totalCalories,
            double totalProteinG,
            List<DietItem> items) {
    }

    public record MacroGram(int proteinG, int fatG, int carbG) {
    }

    public record NutritionPlan(
            boolean available,
            String reason,
            int bmr,
            int tdee,
            int cutCalories,
            int bulkCalories,
            MacroGram cutMacro,
            MacroGram bulkMacro) {
    }
}
