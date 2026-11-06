package com.gym.training.metric;

import com.gym.training.metric.TrainMetrics.ActivityLevel;
import com.gym.training.metric.TrainMetrics.Lift;
import com.gym.training.metric.TrainMetrics.Level;
import com.gym.training.metric.TrainMetrics.MuscleGroup;
import com.gym.training.metric.TrainMetrics.SetRecord;
import com.gym.training.metric.TrainMetrics.VolumeVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 七个算法的验证。
 *
 * <p>这一层是整个 AI 链路的输入源。如果它算错，
 * 上层模型给出的建议再通顺也毫无意义——所以必须锁死。
 */
class TrainMetricsTest {

    private static final double EPS = 0.01;

    @Test
    @DisplayName("Epley 基础换算：60kg×8 次 = 76kg")
    void epley() {
        assertEquals(76.0, TrainMetrics.epley(60, 8), EPS);
        assertEquals(100.0, TrainMetrics.epley(60, 20), EPS);
    }

    @Test
    @DisplayName("RPE 折算：非力竭组必须还原成等效力竭次数再计算")
    void rpeAdjust() {
        // 60kg×8 但 RPE=8（还能再做 2 次），等效力竭次数为 10
        assertEquals(80.0, TrainMetrics.e1rm(60, 8, 8.0), EPS);
        // 无 RPE 时按力竭处理
        assertEquals(76.0, TrainMetrics.e1rm(60, 8, null), EPS);
        // 力竭组 RPE=10，RIR=0，等效次数不变
        assertEquals(76.0, TrainMetrics.e1rm(60, 8, 10.0), EPS);
    }

    @Test
    @DisplayName("RPE=7 的组若不做折算，会低估真实力量水平")
    void rpeUnderestimate() {
        double naive = TrainMetrics.e1rm(60, 8, null);
        // RPE 7 = 还能再做 3 次，等效力竭次数 11，60 × (1 + 11/30) = 82
        double adjusted = TrainMetrics.e1rm(60, 8, 7.0);
        assertTrue(adjusted > naive, "折算后的 e1RM 应高于朴素计算");
        assertEquals(82.0, adjusted, EPS);
        // 朴素计算会低估约 6kg，长期累积足以把「稳步进步」误判成平台期
        assertEquals(6.0, adjusted - naive, EPS);
    }

    @Test
    @DisplayName("高次数外推应标记为低置信度")
    void confidence() {
        assertTrue(TrainMetrics.e1rmConfident(8, null));
        assertFalse(TrainMetrics.e1rmConfident(15, null));
    }

    @Test
    @DisplayName("容量只统计有效组，热身组不计入")
    void volume() {
        List<SetRecord> sets = List.of(
                new SetRecord(1L, "胸", 20, 10, 3.0, true),
                new SetRecord(1L, "胸", 60, 8, 9.0, false),
                new SetRecord(1L, "胸", 60, 8, 9.0, false));
        // 热身组 20×10 被排除，剩余两组各 480
        assertEquals(960.0, TrainMetrics.volume(sets), EPS);
    }

    @Test
    @DisplayName("有效组判定：重量低于 65% e1RM 且 RPE 偏低的不计入")
    void workingSet() {
        assertTrue(TrainMetrics.isWorkingSet(60, 9.0, 80));
        // 40 / 80 = 50%，低于 65% 阈值，且 RPE 6 低于 7
        assertFalse(TrainMetrics.isWorkingSet(40, 6.0, 80));
        // 重量不够但 RPE 达标，仍算有效组
        assertTrue(TrainMetrics.isWorkingSet(40, 8.0, 80));
    }

    @Test
    @DisplayName("平台期：连续三次增幅低于 1% 判定停滞")
    void plateau() {
        assertTrue(TrainMetrics.isPlateau(List.of(76.0, 76.2, 76.3)));
        assertFalse(TrainMetrics.isPlateau(List.of(76.0, 78.0, 80.0)));
        // 样本不足时不应误报
        assertFalse(TrainMetrics.isPlateau(List.of(76.0, 76.1)));
    }

    @Test
    @DisplayName("平台期：开头恰好是状态差的那周，不能被误判成还在进步")
    void plateauMustNotFlipOnALowFirstPoint() {
        // 真实记录里，卡住的人某周状态差、e1RM 偏低是常态。
        // 若拿第一个点当基准，后面回到正常水平就会被算成 +3% 的「增长」，
        // 判定直接翻转。平台期看的是天花板有没有被抬起来，不是首点之后涨没涨。
        assertTrue(TrainMetrics.isPlateau(List.of(86.0, 88.7, 88.7, 86.0, 88.7, 88.7)));

        // 反过来，天花板真的被突破时必须报出来
        assertFalse(TrainMetrics.isPlateau(List.of(86.0, 88.0, 88.0, 92.0, 93.0, 94.0)));
    }

    @Test
    @DisplayName("平台期：尾部最后一下冲上去了，就该判定为已突破")
    void plateauEndsWhenCeilingBreaksAtTheEnd() {
        // 卡了很久，最后两周终于冲过去 —— 这时必须报「已突破」，
        // 否则用户刚有起色就被继续告知「你在平台期」，建议也就跟着错了
        assertFalse(TrainMetrics.isPlateau(List.of(88.7, 88.0, 88.7, 88.0, 88.7, 90.5)));
    }

    @Test
    @DisplayName("趋势斜率：稳步上升应为正值")
    void slope() {
        double slope = TrainMetrics.slopePerSession(List.of(70.0, 72.0, 74.0, 76.0));
        assertEquals(2.0, slope, EPS);
    }

    @Test
    @DisplayName("肌群周容量区间：胸 4/12/18/24 组分别落在四档")
    void muscleVolume() {
        assertEquals(VolumeVerdict.BELOW_MEV, TrainMetrics.verdict(MuscleGroup.CHEST, 4));
        assertEquals(VolumeVerdict.MEV_TO_MAV, TrainMetrics.verdict(MuscleGroup.CHEST, 12));
        assertEquals(VolumeVerdict.MAV_TO_MRV, TrainMetrics.verdict(MuscleGroup.CHEST, 18));
        assertEquals(VolumeVerdict.OVER_MRV, TrainMetrics.verdict(MuscleGroup.CHEST, 24));
    }

    @Test
    @DisplayName("水平分级按相对力量，而非绝对重量")
    void level() {
        // 卧推 60kg、体重 80kg，比值 0.75 正好达到中级线
        assertEquals(Level.INTERMEDIATE, TrainMetrics.levelOf(Lift.BENCH, 60, 80));
        // 同样 60kg，体重只有 45kg 的人相对力量更强
        assertEquals(Level.ADVANCED, TrainMetrics.levelOf(Lift.BENCH, 60, 45));
        // 体重 90kg 推 45kg，比值 0.5，仅到新手线
        assertEquals(Level.NOVICE, TrainMetrics.levelOf(Lift.BENCH, 45, 90));
        assertEquals(Level.UNTRAINED, TrainMetrics.levelOf(Lift.BENCH, 30, 90));
    }

    @Test
    @DisplayName("TDEE 与宏量营养素")
    void nutrition() {
        // 70kg 175cm 25 岁男性：700 + 1093.75 - 125 + 5 = 1673.75
        assertEquals(1673.75, TrainMetrics.bmr(70, 175, 25, true), EPS);
        double tdee = TrainMetrics.tdee(1673.75, ActivityLevel.MODERATE);
        assertEquals(2594.3, tdee, 0.1);

        var macro = TrainMetrics.macro(2600, 70);
        assertEquals(140.0, macro.proteinG(), EPS);
        assertEquals(63.0, macro.fatG(), EPS);
        // 2600 - 140×4 - 63×9 = 1473，除以 4 得碳水
        assertEquals(368.25, macro.carbG(), 0.1);
    }
}
