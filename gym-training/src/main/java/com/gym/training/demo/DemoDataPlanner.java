package com.gym.training.demo;

import com.gym.training.metric.TrainMetrics;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 演示数据编排器：<b>纯函数，不碰数据库，不依赖 Spring。</b>
 *
 * <p>为什么不直接写一堆 INSERT 塞进 schema.sql：
 * 手写的数据经不起算法的反向检验。随手编的「卧推 60kg×8」在 Epley 下算出来是 76，
 * 但你写数据时心里的目标是 82，于是演示现场系统给出的结论和你预期的不一样，
 * 你却看不出是自己数据写错了还是算法算错了。
 *
 * <p>这个类的做法反过来：<b>先定剧本，再用生产算法把剧本翻译成组记录，最后用同一套算法验收。</b>
 * 于是数据天生自洽——存在 set_log 里的 e1rm 字段，必然等于 TrainMetrics 对该行的计算结果。
 *
 * <p>三个刻意的设计：
 *
 * <p>1. <b>可复现。</b>所有随机（缺练、体重日波动）都来自固定种子的 Random，
 *      同一个 seed 永远得到同一份数据。论文里做三组检索对比实验时，
 *      输入数据必须先固定，否则对比的是数据差异而不是检索策略差异。
 *
 * <p>2. <b>剧本里埋了四个必须被检出的结论。</b>
 *      演示数据如果一切正常，智能体就没有东西可说，演示也就没有意义。
 *
 * <p>3. <b>生成完立刻自检。</b>{@link #verify} 复用的是生产算法，
 *      一旦剧本改坏了，验收会当场失败，而不是等到答辩现场才发现。
 */
public final class DemoDataPlanner {

    /** 半年。短于这个跨度，平台期在数据上和「正常波动」无法区分 */
    public static final int WEEKS = 26;

    /** 固定随机种子。论文实验要对比检索策略，数据必须先钉死 */
    public static final long DEFAULT_SEED = 20260701L;
    /** 演示用户起始体重 */
    public static final double DEFAULT_BODY_WEIGHT_KG = 72.0;

    // ---------- 动作键，必须与 schema.sql 种子数据的 name 严格一致 ----------
    public static final String BENCH = "杠铃卧推";
    public static final String SQUAT = "杠铃深蹲";
    public static final String DEADLIFT = "硬拉";
    public static final String OHP = "肩上推举";
    public static final String PULLUP = "引体向上";
    public static final String DB_BENCH = "哑铃卧推";
    public static final String ROW = "杠铃划船";
    public static final String LEG_PRESS = "腿举";
    public static final String CURL = "二头弯举";
    public static final String PUSHDOWN = "绳索下压";

    /** 减载周强度系数 */
    private static final double DELOAD_FACTOR = 0.8;
    /** 减载周只做一半的组 */
    private static final boolean DELOAD_HALVE_SETS = true;
    /** 临时缺练概率 */
    private static final double SKIP_RATE = 0.06;
    /** 第 12 周整周缺练（出差），真实训练记录不可能连续半年不断 */
    private static final int TRAVEL_WEEK_INDEX = 11;

    /**
     * 状态欠佳的训练日占比。
     *
     * <p>不加这个，平台期的 e1RM 序列会是一串完全相同的数字，
     * 答辩时一眼假。真实的卡住是「上限抬不动，下限更低」，
     * 所以只在状态差的日子少做一次，好日子也不会突破——
     * 两边都加噪声，趋势就变成在涨了，平台期判定当场失效。
     */
    private static final double BAD_DAY_RATE = 0.28;
    /** 内容与缺练两条随机流的种子错开量。取个素数，避免两条流产生相关 */
    private static final long CONTENT_RND_OFFSET = 1_000_003L;

    private DemoDataPlanner() {
    }

    // ==================== 剧本 ====================

    /**
     * 训练阶段。
     *
     * <p>分期的意义在于：<b>新手红利是有尽头的。</b>
     * 前八周每周加 2.5kg 是因为神经适应还在快速兑现；
     * 之后必须放慢，再之后必然卡住——这个剧本模拟的就是这条真实曲线。
     */
    public enum Phase {
        /** 新手线性期 */
        LINEAR,
        /** 增速减半 */
        SLOWED,
        /** 卡住了 */
        PLATEAU,
        /** 减载周 */
        DELOAD
    }

    /**
     * 26 周剧本，最后十周停在平台期。
     *
     * <p>结尾必须是平台期：用户问「我卧推是不是卡住了」时，
     * 智能体调的 getStrengthTrend 默认回看 8 周，
     * 只有最近 8 周确实不动，它才能给出「是平台期」而不是「你还在涨」。
     */
    private static final Phase[] SCRIPT = buildScript();

    private static Phase[] buildScript() {
        Phase[] p = new Phase[WEEKS];
        fill(p, 0, 8, Phase.LINEAR);      // W1-W8   新手线性期
        p[8] = Phase.DELOAD;              // W9      第一次减载
        fill(p, 9, 6, Phase.SLOWED);      // W10-W15 增速减半
        p[15] = Phase.DELOAD;             // W16     第二次减载
        fill(p, 16, 10, Phase.PLATEAU);   // W17-W26 卡住，至今没出来
        return p;
    }

    /** 各阶段每周 e1RM 增量，按 Phase 的 ordinal 索引。减载周不推进曲线 */
    private static final Map<String, double[]> WEEKLY_GAIN = Map.of(
            BENCH, new double[]{2.5, 1.25, 0.0, 0.0},
            SQUAT, new double[]{5.0, 2.5, 1.25, 0.0},
            DEADLIFT, new double[]{5.0, 2.5, 1.25, 0.0},
            // 肩推与卧推同为推类动作，卧推卡住时肩推常常一起卡——
            // 两个动作同时停，说明问题出在整个推的模式上，而不是某个动作没练对
            OHP, new double[]{1.25, 0.625, 0.0, 0.0},
            // 引体向上记录的是「体重 + 附加负重」的总负荷，
            // 背阔肌分不清你背上是杠铃片还是自己的体重
            PULLUP, new double[]{1.25, 0.625, 0.0, 0.0},
            ROW, new double[]{2.5, 1.25, 1.25, 0.0},
            LEG_PRESS, new double[]{5.0, 2.5, 2.5, 0.0},
            DB_BENCH, new double[]{1.25, 0.625, 0.625, 0.0},
            CURL, new double[]{0.5, 0.25, 0.25, 0.0},
            PUSHDOWN, new double[]{1.0, 0.5, 0.5, 0.0});

    private static final Map<String, Double> START_E1RM = Map.of(
            BENCH, 60.0,
            SQUAT, 80.0,
            DEADLIFT, 100.0,
            OHP, 40.0,
            PULLUP, 98.0,
            ROW, 60.0,
            LEG_PRESS, 280.0,
            DB_BENCH, 28.0,
            CURL, 30.0,
            PUSHDOWN, 25.0);

    /** 每个动作的组数、次数、RPE 模板 */
    private record Scheme(int[] reps, double[] rpe, double step, int warmupSets, int restSec) {
    }

    /**
     * 组次数模板。
     *
     * <p>孤立动作刻意不做 12 次：12 次 RPE7 折算成 15 次等效力竭，
     * 早就越出 Epley 外推的可靠范围了。
     * 拿这样的组去外推 1RM，算出来的数字连方向都不可信。
     * 所以弯举、下压一律走 10 次 RPE8——既满足增肌的次数区间，
     * 也让存在库里的 e1RM 落在算法敢用的范围内。
     */
    private static final Map<String, Scheme> SCHEMES = Map.of(
            BENCH, new Scheme(new int[]{5, 5, 5, 4}, new double[]{7, 8, 8.5, 9}, 2.5, 2, 180),
            SQUAT, new Scheme(new int[]{5, 5, 5, 4}, new double[]{7, 8, 8.5, 9}, 2.5, 2, 180),
            DEADLIFT, new Scheme(new int[]{5, 5, 4}, new double[]{7, 8.5, 9}, 2.5, 2, 180),
            OHP, new Scheme(new int[]{6, 6, 5}, new double[]{7, 8, 9}, 2.5, 1, 150),
            PULLUP, new Scheme(new int[]{8, 7, 6}, new double[]{7, 8, 9}, 1.0, 0, 150),
            ROW, new Scheme(new int[]{8, 8, 8, 7}, new double[]{7, 8, 8.5, 9}, 2.5, 1, 120),
            LEG_PRESS, new Scheme(new int[]{10, 10, 9}, new double[]{8, 8.5, 9}, 5.0, 1, 120),
            DB_BENCH, new Scheme(new int[]{8, 8, 7}, new double[]{8, 8.5, 9}, 5.0, 1, 120),
            CURL, new Scheme(new int[]{10, 10, 9}, new double[]{8, 8.5, 9}, 2.5, 0, 60),
            PUSHDOWN, new Scheme(new int[]{10, 10, 9}, new double[]{8, 8.5, 9}, 2.5, 0, 60));

    /** 自检用的肌群映射。与 schema.sql 的 primary_muscle 一致，生成器会校验两者不漂移 */
    static final Map<String, String> MUSCLE = Map.of(
            BENCH, "胸",
            DB_BENCH, "胸",
            SQUAT, "股四头肌",
            LEG_PRESS, "股四头肌",
            DEADLIFT, "腘绳肌",
            OHP, "肩",
            PULLUP, "背",
            ROW, "背",
            CURL, "肱二头肌",
            PUSHDOWN, "肱三头肌");

    /** 一个动作在某次训练里做几个正式组 */
    private record Slot(String key, int workingSets) {
    }

    private record SessionTemplate(int sessionType, int dayOffset, LocalTime time,
                                   int durationMin, List<Slot> slots) {
    }

    /**
     * 一周四练的推拉腿分化。
     *
     * <p>刻意留了两个容量缺口：<b>肩只有 5 组/周（MEV 是 6），腘绳肌只有 3 组/周（MEV 是 4）。</b>
     * 智能体如果只报「一切正常」，说明它没在真的看数据。
     */
    private static final List<SessionTemplate> TEMPLATES = List.of(
            new SessionTemplate(1, 0, LocalTime.of(19, 0), 75, List.of(
                    new Slot(BENCH, 4), new Slot(OHP, 3), new Slot(PUSHDOWN, 3))),
            new SessionTemplate(2, 2, LocalTime.of(19, 30), 70, List.of(
                    new Slot(PULLUP, 3), new Slot(ROW, 4), new Slot(CURL, 3))),
            new SessionTemplate(3, 4, LocalTime.of(19, 0), 85, List.of(
                    new Slot(SQUAT, 4), new Slot(LEG_PRESS, 3), new Slot(DEADLIFT, 3))),
            new SessionTemplate(4, 5, LocalTime.of(10, 30), 60, List.of(
                    new Slot(DB_BENCH, 3), new Slot(OHP, 2), new Slot(CURL, 2),
                    new Slot(PUSHDOWN, 2))));

    // ==================== 对外接口 ====================

    public record Config(long userId, int weeks, LocalDate endDate, long seed,
                         double startBodyWeightKg) {

        public static Config of(long userId) {
            return new Config(userId, WEEKS, LocalDate.now(), DEFAULT_SEED,
                    DEFAULT_BODY_WEIGHT_KG);
        }
    }

    public record PlannedSet(String exerciseKey, int setIndex, int reps, double weightKg,
                             double rpe, boolean warmup, int restSec) {
    }

    public record PlannedSession(LocalDate date, LocalTime startTime, int sessionType,
                                 int durationMin, int readinessScore, double sleepHours,
                                 String note, List<PlannedSet> sets) {
    }

    public record BodyRecordPoint(LocalDate date, double weightKg, double bodyFatPct,
                                  double skeletalMuscleKg) {
    }

    public record Plan(long userId,
                       List<PlannedSession> sessions,
                       List<BodyRecordPoint> bodyRecords,
                       List<String> highlights) {
    }

    public record SelfCheck(boolean pass, List<String> problems) {
    }

    /**
     * 生成整份计划。
     *
     * <p>同一个 Config 永远得到同一份 Plan——随机数全部来自固定种子，
     * 且调用顺序固定。这是论文实验能成立的前提。
     */
    public static Plan plan(Config config) {
        // 两条独立随机流。
        // 共用一条的话，SKIP_RATE 从 0.06 调到 0.05 会连带改变每次训练的「当天状态」——
        // 改一个参数却污染了另一件事的分布，结果既难调也讲不清。
        // 随机数是稀缺资源，不同语义的随机源必须隔离。
        Random skipRnd = new Random(config.seed());
        Random contentRnd = new Random(config.seed() + CONTENT_RND_OFFSET);

        int weeks = Math.min(config.weeks(), WEEKS);
        LocalDate firstMonday = firstMonday(config.endDate(), weeks);

        List<PlannedSession> sessions = new ArrayList<>();
        List<BodyRecordPoint> bodies = new ArrayList<>();

        for (int w = 0; w < weeks; w++) {
            Phase phase = SCRIPT[w];
            LocalDate monday = firstMonday.plusWeeks(w);

            // 体测先记，保证随机调用顺序固定
            bodies.add(bodyRecord(monday, w, config.startBodyWeightKg(), contentRnd));

            if (w == TRAVEL_WEEK_INDEX) {
                continue;
            }
            for (SessionTemplate t : TEMPLATES) {
                if (skipRnd.nextDouble() < SKIP_RATE) {
                    continue;
                }
                sessions.add(buildSession(monday.plusDays(t.dayOffset()), t, phase, w, contentRnd));
            }
        }
        return new Plan(config.userId(), List.copyOf(sessions), List.copyOf(bodies),
                List.of(
                        "卧推与肩推在最后 10 周同时停滞 —— 问题在推的模式，不是单个动作",
                        "深蹲与硬拉同期仍在稳步上升 —— 证明停滞是局部的，不是训练整体失效",
                        "肩 5 组/周、腘绳肌 3 组/周，双双低于最低有效量，是刻意留的容量缺口",
                        "第 9、16 周为减载周，第 12 周整周出差未训练"));
    }

    // ==================== 自检 ====================

    /**
     * 用生产算法验收生成的数据。
     *
     * <p>这是整个演示数据环节最值钱的三行：
     * 剧本改坏了，这里的断言会当场失败，而不是等到答辩现场才发现智能体答非所问。
     *
     * <p>注意这里<b>复用了生产算法的同一套口径</b>——
     * 每次训练只取该动作最好的那一组（与 getStrengthTrend 一致），
     * 而不是随手拿全部组去算。口径不一致的自检等于没检。
     */
    public static SelfCheck verify(Plan plan) {
        List<String> problems = new ArrayList<>();

        List<Double> bench = e1rmSeries(plan, BENCH, 8);
        if (bench.size() < 3) {
            problems.add("卧推近 8 周只有 " + bench.size() + " 次记录，不足以构成趋势");
        } else if (!TrainMetrics.isPlateau(bench)) {
            problems.add("卧推近 8 周没有被判定为平台期，主演示场景失效：" + bench);
        }

        List<Double> squat = e1rmSeries(plan, SQUAT, 8);
        if (squat.size() < 3) {
            problems.add("深蹲近 8 周只有 " + squat.size() + " 次记录，对照组失效");
        } else if (TrainMetrics.isPlateau(squat)) {
            problems.add("深蹲也被判成平台期，对照组失效，无法证明诊断有区分度：" + squat);
        }

        Map<String, Integer> weekly = weeklyWorkingSets(plan, 4);
        for (String muscle : List.of("肩", "腘绳肌")) {
            int sets = weekly.getOrDefault(muscle, 0);
            var group = TrainMetrics.MuscleGroup.ofLabel(muscle);
            if (group == null) {
                problems.add("肌群名「" + muscle + "」在 MuscleGroup 里不存在");
            } else if (sets >= group.mev()) {
                problems.add("「" + muscle + "」周均 " + sets + " 组，未低于 MEV "
                        + group.mev() + "，容量缺口的演示点丢失");
            }
        }
        int chest = weekly.getOrDefault("胸", 0);
        if (chest > 0 && chest < TrainMetrics.MuscleGroup.CHEST.mev()) {
            problems.add("胸周均 " + chest + " 组，连主项肌群都低于 MEV，数据失衡");
        }

        return new SelfCheck(problems.isEmpty(), List.copyOf(problems));
    }

    /**
     * 模拟 getStrengthTrend 的取数口径：区间内每次训练只取该动作 e1RM 最好的一组。
     *
     * <p>必须取最好的一组。用户当天冲的那组才代表真实力量水平，
     * 后面因疲劳掉重量的组混进去，会把趋势算成负增长。
     */
    public static List<Double> e1rmSeries(Plan plan, String exerciseKey, int recentWeeks) {
        if (plan.sessions().isEmpty()) {
            return List.of();
        }
        LocalDate latest = plan.sessions().get(plan.sessions().size() - 1).date();
        LocalDate from = latest.minusWeeks(recentWeeks);
        Map<LocalDate, Double> bestPerSession = new LinkedHashMap<>();
        for (PlannedSession s : plan.sessions()) {
            if (s.date().isBefore(from)) {
                continue;
            }
            for (PlannedSet set : s.sets()) {
                if (set.warmup() || !set.exerciseKey().equals(exerciseKey)) {
                    continue;
                }
                bestPerSession.merge(s.date(),
                        TrainMetrics.e1rm(set.weightKg(), set.reps(), set.rpe()), Math::max);
            }
        }
        return new ArrayList<>(bestPerSession.values());
    }

    /** 模拟 getMuscleVolume 的取数口径：区间内各肌群的周均有效组数 */
    public static Map<String, Integer> weeklyWorkingSets(Plan plan, int recentWeeks) {
        if (plan.sessions().isEmpty()) {
            return Map.of();
        }
        LocalDate latest = plan.sessions().get(plan.sessions().size() - 1).date();
        LocalDate from = latest.minusWeeks(recentWeeks);

        // 每个动作键分配一个稳定的合成 id。
        // 全部塞成同一个 id 的话，e1rmBaseline 会把十个动作的最好成绩并成一个，
        // 拿卧推的 1RM 去衡量弯举的重量，所有弯举都会被判成无效组
        Map<String, Long> ids = new LinkedHashMap<>();
        for (String key : MUSCLE.keySet()) {
            ids.put(key, (long) (ids.size() + 1));
        }

        List<TrainMetrics.SetRecord> records = new ArrayList<>();
        for (PlannedSession s : plan.sessions()) {
            if (s.date().isBefore(from)) {
                continue;
            }
            for (PlannedSet set : s.sets()) {
                records.add(new TrainMetrics.SetRecord(ids.get(set.exerciseKey()),
                        MUSCLE.get(set.exerciseKey()),
                        set.weightKg(), set.reps(), set.rpe(), set.warmup()));
            }
        }
        Map<Long, Double> baseline = TrainMetrics.e1rmBaseline(records);
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String muscle : MUSCLE.values()) {
            if (TrainMetrics.MuscleGroup.ofLabel(muscle) == null) {
                continue;
            }
            int total = (int) records.stream()
                    .filter(r -> !r.warmup())
                    .filter(r -> muscle.equals(r.muscle()))
                    .filter(r -> TrainMetrics.isWorkingSet(r.weightKg(), r.rpe(),
                            baseline.getOrDefault(r.exerciseId(),
                                    TrainMetrics.e1rm(r.weightKg(), r.reps(), r.rpe()))))
                    .count();
            if (total > 0) {
                result.put(muscle, (int) Math.round(total / (double) recentWeeks));
            }
        }
        return result;
    }

    // ==================== 内部实现 ====================

    private static PlannedSession buildSession(LocalDate date, SessionTemplate template,
                                               Phase phase, int weekIndex, Random rnd) {
        // 当天状态必须先抽。
        // 力量水平（e1rmAtWeek）和当天表现（badDay）是两回事，
        // 混成一个数，趋势就变成了噪声的连线，看不出人到底有没有进步。
        boolean badDay = rnd.nextDouble() < BAD_DAY_RATE;
        int repAdjust = badDay ? -1 : 0;

        List<PlannedSet> sets = new ArrayList<>();
        int index = 1;
        for (Slot slot : template.slots()) {
            Scheme scheme = SCHEMES.get(slot.key());
            double target = e1rmAtWeek(slot.key(), weekIndex);
            int working = slot.workingSets();
            if (phase == Phase.DELOAD && DELOAD_HALVE_SETS) {
                working = (working + 1) / 2;
            }
            double scaled = target * (phase == Phase.DELOAD ? DELOAD_FACTOR : 1.0);
            double firstWeight = workingWeight(
                    scaled, scheme.reps()[0], scheme.rpe()[0], scheme.step());

            for (int i = 0; i < scheme.warmupSets(); i++) {
                double ratio = scheme.warmupSets() == 1 ? 0.5 : (i == 0 ? 0.45 : 0.65);
                sets.add(new PlannedSet(slot.key(), index++,
                        i == 0 ? 10 : 8,
                        roundTo(firstWeight * ratio, scheme.step()),
                        i == 0 ? 3.0 : 4.5, true, 60));
            }
            for (int i = 0; i < working; i++) {
                // 重量是按计划反解出来的，次数是当天打出来的。
                // 顺序反过来立刻穿帮：拿着少做一次的次数去反解重量，
                // 会算出「状态越差上得越重」，而力量水平其实一点没变。
                // 真实训练就是这样：杠铃上多少是事先定好的，能做几个看当天状态。
                double weight = workingWeight(
                        scaled, scheme.reps()[i], scheme.rpe()[i], scheme.step());
                int reps = Math.max(1, scheme.reps()[i] + repAdjust);
                sets.add(new PlannedSet(slot.key(), index++, reps, weight,
                        scheme.rpe()[i], false, scheme.restSec()));
            }
        }

        String note = phase == Phase.DELOAD ? "减载周：容量与强度同时下调，为下一阶段蓄力" : "";
        if (badDay && note.isEmpty()) {
            note = "当天状态一般，组次数未完成预期";
        }
        // 三个字段必须互相印证：状态差的日子准备度低、睡得也少。
        // 各噪各的假数据一眼就能看穿——老师问一句「你凭什么说这天状态差」就答不上来。
        int readiness = badDay ? 3 : 4;
        // 减载前一周的准备度必然偏低——那正是安排减载的原因
        if (weekIndex + 1 < WEEKS && SCRIPT[weekIndex + 1] == Phase.DELOAD) {
            readiness = Math.min(readiness, 3);
        }
        double sleep = clamp((badDay ? 6.2 : 7.2) + rnd.nextGaussian() * 0.6, 5.0, 9.0);
        int duration = template.durationMin() + (int) (rnd.nextGaussian() * 4);
        return new PlannedSession(date, template.time(), template.sessionType(),
                Math.max(duration, 40), readiness, round1(sleep), note, List.copyOf(sets));
    }

    private static BodyRecordPoint bodyRecord(LocalDate monday, int weekIndex,
                                              double startWeight, Random rnd) {
        // 精益增肌：每周 +0.15kg。再快长的主要是脂肪
        double trend = startWeight + 0.15 * weekIndex;
        // 单日体重波动 1~2kg 几乎全是水分与肠道内容物，不叠加噪声的体重数据是假数据
        double measured = trend + rnd.nextGaussian() * 0.45;
        double fat = 16.0 - 0.04 * weekIndex;
        double muscle = 32.0 + 0.085 * weekIndex;
        return new BodyRecordPoint(monday, round1(measured), round1(fat), round1(muscle));
    }

    /** 该动作在第 weekIndex 周的目标 e1RM。减载周不推进曲线 */
    static double e1rmAtWeek(String key, int weekIndex) {
        double[] gains = WEEKLY_GAIN.get(key);
        double e = START_E1RM.get(key);
        for (int i = 0; i < weekIndex; i++) {
            e += gains[SCRIPT[i].ordinal()];
        }
        return e;
    }

    /** 由目标 e1RM 反解出该组该举多重 */
    private static double workingWeight(double targetE1rm, int reps, double rpe, double step) {
        int eff = TrainMetrics.toEffectiveReps(reps, rpe);
        return roundTo(targetE1rm / (1.0 + eff / 30.0), step);
    }

    private static double roundTo(double v, double step) {
        return Math.round(v / step) * step;
    }

    /**
     * 起点周一。
     * 最后一周固定排在「上一周」，保证全部训练都发生在今天之前——
     * 未来的训练记录会被时间窗口查询直接过滤掉，等于白生成。
     */
    private static LocalDate firstMonday(LocalDate endDate, int weeks) {
        LocalDate lastWeekMonday = endDate
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .minusWeeks(1);
        return lastWeekMonday.minusWeeks(weeks - 1);
    }

    private static void fill(Phase[] target, int from, int count, Phase phase) {
        for (int i = from; i < from + count; i++) {
            target[i] = phase;
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.min(Math.max(v, min), max);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
