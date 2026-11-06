package com.gym.training.demo;

import com.gym.training.demo.DemoDataPlanner.BodyRecordPoint;
import com.gym.training.demo.DemoDataPlanner.Config;
import com.gym.training.demo.DemoDataPlanner.Phase;
import com.gym.training.demo.DemoDataPlanner.Plan;
import com.gym.training.demo.DemoDataPlanner.PlannedSession;
import com.gym.training.demo.DemoDataPlanner.PlannedSet;
import com.gym.training.demo.DemoDataPlanner.SelfCheck;
import com.gym.training.metric.TrainMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoDataPlannerTest {

    private static final Config CONFIG = new Config(1001L, DemoDataPlanner.WEEKS,
            LocalDate.of(2026, 10, 21), 20260701L, 72.0);

    @Test
    @DisplayName("剧本自检必须通过：四个演示结论都要能被算法检出")
    void selfCheckPasses() {
        SelfCheck check = DemoDataPlanner.verify(DemoDataPlanner.plan(CONFIG));

        assertTrue(check.pass(), "自检未通过：" + String.join("；", check.problems()));
    }

    @Test
    @DisplayName("卧推近八周必须被判成平台期")
    void benchIsStuck() {
        List<Double> bench = DemoDataPlanner.e1rmSeries(
                DemoDataPlanner.plan(CONFIG), DemoDataPlanner.BENCH, 8);

        assertTrue(bench.size() >= 3, "样本不足：" + bench.size());
        assertTrue(TrainMetrics.isPlateau(bench), "卧推序列应判定为平台期：" + bench);
    }

    @Test
    @DisplayName("深蹲同期仍在涨，否则『平台期』这个诊断就没有区分度了")
    void squatStillProgresses() {
        List<Double> squat = DemoDataPlanner.e1rmSeries(
                DemoDataPlanner.plan(CONFIG), DemoDataPlanner.SQUAT, 8);

        assertFalse(TrainMetrics.isPlateau(squat), "深蹲不该被判成平台期：" + squat);
        assertTrue(squat.get(squat.size() - 1) > squat.get(0), "深蹲应当在涨：" + squat);
    }

    @Test
    @DisplayName("肩与腘绳肌刻意留了容量缺口")
    void intentionalVolumeGaps() {
        Map<String, Integer> weekly = DemoDataPlanner.weeklyWorkingSets(
                DemoDataPlanner.plan(CONFIG), 4);

        assertTrue(weekly.get("肩") < TrainMetrics.MuscleGroup.SHOULDER.mev(),
                "肩应低于 MEV，实际 " + weekly.get("肩"));
        assertTrue(weekly.get("腘绳肌") < TrainMetrics.MuscleGroup.HAMSTRINGS.mev(),
                "腘绳肌应低于 MEV，实际 " + weekly.get("腘绳肌"));
        // 主项肌群不能连最低有效量都不到，否则数据是失衡的而不是真实的
        assertTrue(weekly.get("胸") >= TrainMetrics.MuscleGroup.CHEST.mev(),
                "胸不应低于 MEV，实际 " + weekly.get("胸"));
    }

    @Test
    @DisplayName("同一 seed 必须产出同一份数据，论文实验才成立")
    void reproducible() {
        Plan a = DemoDataPlanner.plan(CONFIG);
        Plan b = DemoDataPlanner.plan(CONFIG);

        assertEquals(a.sessions().size(), b.sessions().size());
        for (int i = 0; i < a.sessions().size(); i++) {
            assertEquals(a.sessions().get(i), b.sessions().get(i));
        }
        assertEquals(a.bodyRecords(), b.bodyRecords());
    }

    @Test
    @DisplayName("不同 seed 产出不同数据，证明随机确实生效了")
    void differentSeedDiffers() {
        Plan a = DemoDataPlanner.plan(CONFIG);
        Plan b = DemoDataPlanner.plan(new Config(1001L, DemoDataPlanner.WEEKS,
                LocalDate.of(2026, 10, 21), 999L, 72.0));

        assertFalse(a.sessions().equals(b.sessions()));
    }

    @Test
    @DisplayName("全部训练必须发生在今天之前，否则会被时间窗口查询过滤掉")
    void allInThePast() {
        Plan plan = DemoDataPlanner.plan(new Config(1001L, DemoDataPlanner.WEEKS,
                LocalDate.now(), 20260701L, 72.0));

        for (PlannedSession s : plan.sessions()) {
            assertTrue(s.date().isBefore(LocalDate.now()),
                    "存在未来日期的训练：" + s.date());
        }
    }

    @Test
    @DisplayName("热身组必须明显轻于正式组")
    void warmupsAreLight() {
        Plan plan = DemoDataPlanner.plan(CONFIG);
        PlannedSession session = plan.sessions().get(0);

        List<PlannedSet> warmups = session.sets().stream().filter(PlannedSet::warmup).toList();
        List<PlannedSet> working = session.sets().stream().filter(s -> !s.warmup()).toList();

        assertFalse(warmups.isEmpty());
        for (PlannedSet w : warmups) {
            // 卧推第一周正式组 47.5kg，热身不应超过它的 70%
            assertTrue(w.weightKg() < 35, "热身组过重：" + w.weightKg());
        }
        assertTrue(working.stream().anyMatch(s -> s.weightKg() >= 40));
    }

    @Test
    @DisplayName("减载周容量与强度必须同时下调")
    void deloadIsLighter() {
        Plan plan = DemoDataPlanner.plan(CONFIG);

        // 第 9 周（索引 8）是减载周
        PlannedSession deload = sessionOfWeek(plan, 8);
        PlannedSession normal = sessionOfWeek(plan, 6);

        assertTrue(deload.note().contains("减载"));
        double deloadMax = maxWorkingWeight(deload, DemoDataPlanner.BENCH);
        double normalMax = maxWorkingWeight(normal, DemoDataPlanner.BENCH);
        assertTrue(deloadMax < normalMax,
                "减载周强度应低于平时：" + deloadMax + " vs " + normalMax);
        assertTrue(countSets(deload, DemoDataPlanner.BENCH)
                < countSets(normal, DemoDataPlanner.BENCH));
    }

    @Test
    @DisplayName("正式组必须落在 e1RM 高置信度区间，热身组必须落在区间外")
    void confidenceSplit() {
        Plan plan = DemoDataPlanner.plan(CONFIG);

        for (PlannedSession s : plan.sessions()) {
            for (PlannedSet set : s.sets()) {
                boolean confident = TrainMetrics.e1rmConfident(set.reps(), set.rpe());
                if (set.warmup()) {
                    // 热身组刻意做到 10 次 RPE3，等效 15 次，本就不该拿去外推 1RM。
                    // 演示数据如果连热身组都落在高置信度区间，说明热身太重了
                    assertFalse(confident, "热身组不该落在高置信度区间：" + set);
                } else {
                    // 正式组必须在外推可靠范围内，否则系统给出的力量结论不可信
                    assertTrue(confident, "正式组应落在高置信度区间：" + set);
                }
            }
        }
    }

    @Test
    @DisplayName("体重必须稳步上升，且单日有波动")
    void bodyWeightTrends() {
        Plan plan = DemoDataPlanner.plan(CONFIG);
        List<BodyRecordPoint> points = plan.bodyRecords();

        assertEquals(DemoDataPlanner.WEEKS, points.size());
        assertTrue(points.get(points.size() - 1).weightKg() > points.get(0).weightKg(),
                "增肌期体重应上升");
        // 增肌速度应当在精益增肌区间内，半年涨 3~5kg
        double gained = points.get(points.size() - 1).weightKg() - points.get(0).weightKg();
        assertTrue(gained > 2 && gained < 6, "半年增重 " + gained + "kg 不在合理区间");
    }

    @Test
    @DisplayName("剧本最后八周卡在平台期：上限抬不动，下限确实更低")
    void scriptShape() {
        Plan plan = DemoDataPlanner.plan(CONFIG);
        List<Double> bench8 = DemoDataPlanner.e1rmSeries(plan, DemoDataPlanner.BENCH, 8);

        // 卡不卡由生产算法说了算，不能靠「值都相等」来凑
        assertTrue(TrainMetrics.isPlateau(bench8), "最后八周必须判定为平台期：" + bench8);

        // 但数据必须有起伏。八周条条一样的成绩在真实记录里不存在，
        // 答辩时一眼假。真实的卡住是「上限抬不动，下限更低」。
        double ceiling = bench8.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        assertTrue(bench8.stream().anyMatch(v -> v < ceiling),
                "应有状态欠佳导致成绩下滑的日子，当前全部持平：" + bench8);
        assertTrue(bench8.stream().distinct().count() >= 2,
                "平台期数据应存在起伏，当前为常数：" + bench8);
    }

    // ==================== 辅助 ====================

    private PlannedSession sessionOfWeek(Plan plan, int weekIndex) {
        // 第 12 周整周缺练，取该周的任意一次即可，这里取第 9 周与第 7 周的推日
        for (PlannedSession s : plan.sessions()) {
            if (s.sessionType() == 1 && weekStartDate(plan, weekIndex).equals(
                    s.date().minusDays(s.date().getDayOfWeek().getValue() - 1))) {
                return s;
            }
        }
        throw new AssertionError("找不到第 " + weekIndex + " 周的训练");
    }

    private LocalDate weekStartDate(Plan plan, int weekIndex) {
        // 剧本第一周的周一
        LocalDate first = plan.sessions().get(0).date()
                .minusDays(plan.sessions().get(0).date().getDayOfWeek().getValue() - 1);
        return first.plusWeeks(weekIndex);
    }

    private double maxWorkingWeight(PlannedSession s, String key) {
        return s.sets().stream()
                .filter(x -> x.exerciseKey().equals(key) && !x.warmup())
                .mapToDouble(PlannedSet::weightKg).max().orElse(0);
    }

    private long countSets(PlannedSession s, String key) {
        return s.sets().stream()
                .filter(x -> x.exerciseKey().equals(key) && !x.warmup()).count();
    }
}
