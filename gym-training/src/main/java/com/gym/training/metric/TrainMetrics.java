package com.gym.training.metric;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 训练指标的确定性计算器。
 *
 * <p>设计原则：<b>代码负责精确，模型负责模糊。</b>
 * 容量、e1RM、有效组、肌群组数、平台期判定全部由这里算出，
 * 大模型的职责只有一件事——看着这些确定的事实，给出训练建议。
 * 这一层一旦交给模型，数字就会变成幻觉。
 *
 * <p>本类是整个 AI 链路的输入源，所有 AI 结论都必须先经过这里。
 */
public final class TrainMetrics {

    /** e1RM 外推可靠的等效次数上限，超过后耐力因素会稀释力量信号 */
    private static final int CONFIDENT_REPS_UPPER = 12;
    private static final int E1RM_DENOMINATOR = 30;

    /** 有效组判定阈值 */
    private static final double WORKING_SET_RATIO = 0.65;
    private static final double WORKING_SET_RPE = 7.0;

    /** 平台期判定：最近 N 次训练、累计增幅低于该比例 */
    private static final int PLATEAU_MIN_SIZE = 3;
    private static final double PLATEAU_RELATIVE_THRESHOLD = 0.01;

    private TrainMetrics() {
    }

    public record SetRecord(long exerciseId,
                            String muscle,
                            double weightKg,
                            int reps,
                            Double rpe,
                            boolean warmup) {
    }

    // ==================== 1. e1RM 估算 ====================

    /** Epley 原式：1RM = w × (1 + r/30) */
    public static double epley(double weightKg, int reps) {
        return weightKg * (1.0 + reps / (double) E1RM_DENOMINATOR);
    }

    /** Brzycki：1RM = w × 36 / (37 - r)，次数封顶 20 防止分母趋零导致数值爆炸 */
    public static double brzycki(double weightKg, int reps) {
        int r = Math.min(Math.max(reps, 1), 20);
        return weightKg * 36.0 / (37.0 - r);
    }

    /**
     * RPE 折算为等效力竭次数。
     * RPE 9 表示「还能再做 1 次」，即 RIR = 1，等效力竭次数 = 实际次数 + RIR。
     */
    public static int toEffectiveReps(int reps, Double rpe) {
        if (rpe == null || rpe <= 0) {
            return reps;
        }
        int rir = (int) Math.round(10.0 - rpe);
        if (rir < 0) {
            rir = 0;
        }
        // RPE 低于 5 说明是热身组，不参与力量外推
        if (rir > 5) {
            rir = 5;
        }
        return reps + rir;
    }

    /**
     * 主入口。
     *
     * <p>关键认知：<b>e1RM 公式只对力竭组成立。</b>
     * 60kg×8 如果 RPE 只有 7（还能再做 3 次），直接套 Epley 会低估真实力量约 7%，
     * 连续几周的低估足以把「稳步进步」误判成「平台期」。
     * 所以先由 RPE 还原等效力竭次数，再代入公式。
     */
    public static double e1rm(double weightKg, int reps, Double rpe) {
        return epley(weightKg, toEffectiveReps(reps, rpe));
    }

    /** 等效次数过高时外推不可靠，前端应标注「低置信度」而非当作精确值展示 */
    public static boolean e1rmConfident(int reps, Double rpe) {
        return toEffectiveReps(reps, rpe) <= CONFIDENT_REPS_UPPER;
    }

    // ==================== 2. 训练容量 ====================

    /**
     * 建立每个动作的 e1RM 基准：区间内该动作所有正式组里最好的一个。
     *
     * <p>必须有这个基准，否则有效组判定会退化成恒真——见 {@link #volume} 的说明。
     */
    public static Map<Long, Double> e1rmBaseline(Collection<SetRecord> sets) {
        Map<Long, Double> best = new HashMap<>();
        for (SetRecord s : sets) {
            if (s.warmup() || s.reps() <= 0) {
                continue;
            }
            double e = e1rm(s.weightKg(), s.reps(), s.rpe());
            // 区间内没有别的正式组时可以回退到热身组，但正式组永远优先
            best.merge(s.exerciseId(), e, Math::max);
        }
        return best;
    }

    /**
     * 容量 = Σ(次数 × 重量)。只统计有效组，热身组不计入。
     *
     * <p><b>有效组判定必须用外部基准，不能用本组自己算出的 e1RM。</b>
     * 拿本组的 e1RM 当分母时，w / e1rm 恒等于 1/(1+eff/30)，
     * 与重量无关，只与次数和 RPE 有关。
     * 于是 20kg×10 RPE3 的热身组算出 0.70，同样越过 0.65 的阈值被当成有效组——
     * 判定条件形同虚设，周容量必然虚高。
     * 真正的语义是「这个重量占你本人 1RM 的百分之几」，
     * 分母必须是这个人在这个动作上的历史最好成绩。
     */
    public static double volume(Collection<SetRecord> sets) {
        return volume(sets, e1rmBaseline(sets));
    }

    public static double volume(Collection<SetRecord> sets, Map<Long, Double> baseline) {
        return sets.stream()
                .filter(s -> !s.warmup())
                .filter(s -> isWorkingSet(s.weightKg(), s.rpe(),
                        baselineOf(s, baseline)))
                .mapToDouble(s -> s.weightKg() * s.reps())
                .sum();
    }

    /** 总容量（含热身组），仅用于展示，不参与任何区间判断 */
    public static double rawVolume(Collection<SetRecord> sets) {
        return sets.stream()
                .mapToDouble(s -> s.weightKg() * s.reps())
                .sum();
    }

    // ==================== 3. 有效组判定 ====================

    /**
     * 有效组 = 重量 ≥ 65% 当前 e1RM，或 RPE ≥ 7。
     *
     * <p>热身组必须排除。把 20kg×10 的热身算进周容量，会让胸的周组数虚高到 15 组，
     * 系统据此给出「已达 MAV 区间，无需加量」的结论，用户反而练不到位。
     */
    public static boolean isWorkingSet(double weightKg, Double rpe, double currentE1rm) {
        if (rpe != null && rpe >= WORKING_SET_RPE) {
            return true;
        }
        if (currentE1rm > 0) {
            return weightKg >= WORKING_SET_RATIO * currentE1rm;
        }
        // 无历史 e1RM 时保守计入，避免新用户第一周数据全空
        return true;
    }

    // ==================== 4. 平台期检测 ====================

    /** 最小二乘拟合，返回每次训练的平均增幅（绝对值，单位与入参一致） */
    public static double slopePerSession(List<Double> series) {
        int n = series.size();
        if (n < 2) {
            return 0;
        }
        double meanX = (n - 1) / 2.0;
        double meanY = series.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double numerator = 0;
        double denominator = 0;
        for (int i = 0; i < n; i++) {
            double dx = i - meanX;
            numerator += dx * (series.get(i) - meanY);
            denominator += dx * dx;
        }
        return denominator == 0 ? 0 : numerator / denominator;
    }

    /**
     * 平台期判定：最近若干次训练内，e1RM 始终没能突破历史天花板 1% 以上。
     *
     * <p><b>基准取历史天花板，不取序列第一个点。</b>
     * 这一条是被真实数据逼出来的。卡住的人某周状态差、e1RM 偏低是常态，
     * 若拿第一个点当基准，恰好轮到状态差那周打头，
     * 后面任何一次正常发挥都会被算成「大幅增长」，判定直接翻转成一个假阴性。
     * 平台期的定义是「天花板抬不动了」，不是「第一个点之后有没有涨」。
     *
     * <p>用相对增幅而非绝对增幅：硬拉一次涨 2.5kg 算正常，弯举涨 2.5kg 是突破，
     * 用绝对值会给小重量动作放水。
     *
     * <p>序列前一半用来确定天花板，后一半用来检验是否被突破。
     * 这样做的原因是把「测量」和「检验」放在同一批数据上会自我印证：
     * 天花板若取自全序列，则永远不可能被突破。
     */
    public static boolean isPlateau(List<Double> e1rmSeries) {
        if (e1rmSeries == null || e1rmSeries.size() < PLATEAU_MIN_SIZE) {
            return false;
        }
        int headSize = Math.max(1, e1rmSeries.size() / 2);
        double ceiling = 0;
        for (int i = 0; i < headSize; i++) {
            Double v = e1rmSeries.get(i);
            if (v != null && v > ceiling) {
                ceiling = v;
            }
        }
        if (ceiling <= 0) {
            return false;
        }
        for (int i = headSize; i < e1rmSeries.size(); i++) {
            Double v = e1rmSeries.get(i);
            if (v == null) {
                continue;
            }
            if ((v - ceiling) / ceiling >= PLATEAU_RELATIVE_THRESHOLD) {
                return false;
            }
        }
        return true;
    }

    // ==================== 5. 肌群周容量 vs MEV / MAV / MRV ====================

    /**
     * 各肌群的每周有效组数区间，源自 Renaissance Periodization 体系。
     * MEV 最小有效量 / MAV 最大适应量区间 / MRV 最大可恢复量。
     */
    public enum MuscleGroup {
        CHEST("胸", 6, 16, 22),
        BACK("背", 6, 16, 25),
        QUADS("股四头肌", 6, 14, 20),
        HAMSTRINGS("腘绳肌", 4, 12, 18),
        GLUTES("臀", 4, 12, 18),
        SHOULDER("肩", 6, 20, 26),
        BICEPS("肱二头肌", 4, 14, 20),
        TRICEPS("肱三头肌", 4, 14, 20),
        CALVES("小腿", 3, 10, 16),
        ABS("腹肌", 2, 10, 16);

        private final String label;
        private final int mev;
        private final int mavUpper;
        private final int mrv;

        MuscleGroup(String label, int mev, int mavUpper, int mrv) {
            this.label = label;
            this.mev = mev;
            this.mavUpper = mavUpper;
            this.mrv = mrv;
        }

        public String label() {
            return label;
        }

        public int mev() {
            return mev;
        }

        public int mavUpper() {
            return mavUpper;
        }

        public int mrv() {
            return mrv;
        }

        /** 按中文名反查肌群枚举，数据库里存的是中文标签 */
        public static MuscleGroup ofLabel(String label) {
            for (MuscleGroup group : values()) {
                if (group.label().equals(label)) {
                    return group;
                }
            }
            return null;
        }
    }

    public enum VolumeVerdict {
        BELOW_MEV("低于最小有效量，需要加量"),
        MEV_TO_MAV("在有效区间内，继续保持"),
        MAV_TO_MRV("接近可恢复上限，注意恢复质量"),
        OVER_MRV("超出可恢复量，建议安排减载周");

        private final String advice;

        VolumeVerdict(String advice) {
            this.advice = advice;
        }

        public String advice() {
            return advice;
        }
    }

    /** 按肌群统计区间内该肌群的有效组数。容量判定必须与 {@link #volume} 用同一套基准 */
    public static int weeklyWorkingSets(Collection<SetRecord> sets, MuscleGroup group) {
        return weeklyWorkingSets(sets, group, e1rmBaseline(sets));
    }

    public static int weeklyWorkingSets(Collection<SetRecord> sets, MuscleGroup group,
                                        Map<Long, Double> baseline) {
        return (int) sets.stream()
                .filter(s -> !s.warmup())
                .filter(s -> group.label().equals(s.muscle()))
                .filter(s -> isWorkingSet(s.weightKg(), s.rpe(), baselineOf(s, baseline)))
                .count();
    }

    /**
     * 取该动作的历史最好成绩作基准。
     * 查不到时（这个人在区间内只练过这一次）才回退到本组自算，
     * 此时计入比丢弃好——新用户第一周不该显示 0 组。
     */
    private static double baselineOf(SetRecord s, Map<Long, Double> baseline) {
        Double best = baseline == null ? null : baseline.get(s.exerciseId());
        return best == null ? e1rm(s.weightKg(), s.reps(), s.rpe()) : best;
    }

    public static VolumeVerdict verdict(MuscleGroup group, int weeklyWorkingSets) {
        if (weeklyWorkingSets < group.mev()) {
            return VolumeVerdict.BELOW_MEV;
        }
        if (weeklyWorkingSets <= group.mavUpper()) {
            return VolumeVerdict.MEV_TO_MAV;
        }
        if (weeklyWorkingSets <= group.mrv()) {
            return VolumeVerdict.MAV_TO_MRV;
        }
        return VolumeVerdict.OVER_MRV;
    }

    // ==================== 6. 训练水平分级 ====================

    /** 三个主要动作的 e1RM / 体重 分级标准 */
    public enum Lift {
        BENCH(0.50, 0.75, 1.25),
        SQUAT(0.75, 1.25, 2.00),
        DEADLIFT(1.00, 1.50, 2.50),
        OVERHEAD_PRESS(0.35, 0.50, 0.90);

        private final double novice;
        private final double intermediate;
        private final double advanced;

        Lift(double novice, double intermediate, double advanced) {
            this.novice = novice;
            this.intermediate = intermediate;
            this.advanced = advanced;
        }
    }

    public enum Level {
        UNTRAINED, NOVICE, INTERMEDIATE, ADVANCED
    }

    /**
     * 相对力量分级：e1RM / 体重。
     *
     * <p>分级直接决定计划策略——新手可以线性加重（每次 +2.5kg），
     * 中级以上线性加重必然撞墙，必须引入周期化与减载周。
     * 用相对力量而非绝对重量，是为了让 60kg 和 90kg 的用户可比。
     */
    public static Level levelOf(Lift lift, double e1rm, double bodyWeightKg) {
        if (bodyWeightKg <= 0) {
            return Level.UNTRAINED;
        }
        double ratio = e1rm / bodyWeightKg;
        if (ratio < lift.novice) {
            return Level.UNTRAINED;
        }
        if (ratio < lift.intermediate) {
            return Level.NOVICE;
        }
        if (ratio < lift.advanced) {
            return Level.INTERMEDIATE;
        }
        return Level.ADVANCED;
    }

    // ==================== 7. TDEE 与宏量营养素 ====================

    public enum ActivityLevel {
        SEDENTARY(1.20),
        LIGHT(1.375),
        MODERATE(1.55),
        HIGH(1.725),
        VERY_HIGH(1.90);

        private final double factor;

        ActivityLevel(double factor) {
            this.factor = factor;
        }
    }

    /** Mifflin-St Jeor 基础代谢 */
    public static double bmr(double weightKg, double heightCm, int age, boolean male) {
        return 10 * weightKg + 6.25 * heightCm - 5 * age + (male ? 5 : -161);
    }

    public static double tdee(double bmr, ActivityLevel level) {
        return bmr * level.factor;
    }

    public record Macro(double proteinG, double fatG, double carbG) {
    }

    /**
     * 宏量营养素分配。
     * 蛋白质按体重给（先满足 1.6-2.2 g/kg），脂肪给到 0.9 g/kg，剩余热量全部由碳水补齐。
     * 顺序不能反——先算碳水再算蛋白，会导致蛋白摄入随热量波动而不足。
     */
    public static Macro macro(double targetCalories, double bodyWeightKg) {
        double proteinG = 2.0 * bodyWeightKg;
        double fatG = 0.9 * bodyWeightKg;
        double remain = targetCalories - proteinG * 4 - fatG * 9;
        double carbG = Math.max(remain / 4, 0);
        return new Macro(proteinG, fatG, carbG);
    }
}
