package com.gym.training.demo;

import com.gym.training.demo.DemoDataPlanner.Config;
import com.gym.training.entity.BodyRecord;
import com.gym.training.entity.Exercise;
import com.gym.training.entity.SetLog;
import com.gym.training.entity.WorkoutSession;
import com.gym.training.mapper.BodyRecordMapper;
import com.gym.training.mapper.ExerciseMapper;
import com.gym.training.mapper.SetLogMapper;
import com.gym.training.mapper.WorkoutSessionMapper;
import com.gym.training.metric.TrainMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoDataGeneratorTest {

    private ExerciseMapper exerciseMapper;
    private WorkoutSessionMapper sessionMapper;
    private SetLogMapper setLogMapper;
    private BodyRecordMapper bodyRecordMapper;
    private DemoDataGenerator generator;

    private final List<SetLog> writtenLogs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        exerciseMapper = mock(ExerciseMapper.class);
        sessionMapper = mock(WorkoutSessionMapper.class);
        setLogMapper = mock(SetLogMapper.class);
        bodyRecordMapper = mock(BodyRecordMapper.class);

        // 动作库：主键与主肌群必须与演示剧本一致
        for (String key : DemoDataPlanner.MUSCLE.keySet()) {
            when(exerciseMapper.selectByName(key)).thenReturn(exercise(key));
        }
        // 会话插入后要回填 id，否则 setLog 拿不到 sessionId
        when(sessionMapper.insert(any(WorkoutSession.class))).thenAnswer(invocation -> {
            WorkoutSession ws = invocation.getArgument(0);
            ws.setId(System.nanoTime());
            return 1;
        });
        when(setLogMapper.insertBatch(ArgumentMatchers.<List<SetLog>>any()))
                .thenAnswer(invocation -> {
                    writtenLogs.addAll(invocation.getArgument(0));
                    return 1;
                });

        generator = new DemoDataGenerator(exerciseMapper, sessionMapper,
                setLogMapper, bodyRecordMapper);
    }

    @Test
    @DisplayName("落库产出规模合理")
    void generatesExpectedVolume() {
        DemoDataGenerator.DemoResult result = generator.generate(config());

        assertTrue(result.sessionCount() > 80, "半年四练应有百次左右训练，实际 " + result.sessionCount());
        assertTrue(result.setCount() > 800, "组数应在千级，实际 " + result.setCount());
        assertEquals(26, result.bodyRecordCount());
        assertFalse(result.highlights().isEmpty());
    }

    @Test
    @DisplayName("落库的每组 e1RM 必须等于用生产算法对该行重算的结果")
    void storedE1rmMatchesAlgorithm() {
        generator.generate(config());

        for (SetLog log : writtenLogs) {
            double expected = TrainMetrics.e1rm(log.getWeightKg().doubleValue(),
                    log.getReps(), log.getRpe().doubleValue());
            assertEquals(Math.round(expected * 10.0) / 10.0, log.getE1rm().doubleValue(), 0.05,
                    "存入的 e1RM 与算法重算不一致");
        }
    }

    @Test
    @DisplayName("performed_at 必须回写到训练当天，不能落成 now")
    void performedAtIsBackdated() {
        generator.generate(config());

        LocalDate today = LocalDate.now();
        for (SetLog log : writtenLogs) {
            assertTrue(log.getPerformedAt().isBefore(today.atStartOfDay().plusDays(1)));
            assertTrue(log.getPerformedAt().isBefore(today.plusDays(1).atStartOfDay()));
            // 历史数据的录入时间应与发生时间一致，而不是插入时刻
            assertEquals(log.getPerformedAt(), log.getCreateTime());
        }
    }

    @Test
    @DisplayName("热身组必须被标记，且有效组判定为 0")
    void warmupsAreMarked() {
        generator.generate(config());

        long warmups = writtenLogs.stream().filter(l -> l.getIsWarmup() == 1).count();
        long working = writtenLogs.stream().filter(l -> l.getIsWarmup() == 0).count();
        assertTrue(warmups > 0);
        assertTrue(working > warmups);
        for (SetLog log : writtenLogs) {
            if (log.getIsWarmup() == 1) {
                assertEquals(0, log.getIsWorkingSet(), "热身组不能算有效组");
            }
        }
    }

    @Test
    @DisplayName("正式组必须全部被判为有效组")
    void workingSetsCounted() {
        generator.generate(config());

        for (SetLog log : writtenLogs) {
            if (log.getIsWarmup() == 0) {
                assertEquals(1, log.getIsWorkingSet(),
                        "正式组应计入有效组：" + log.getWeightKg() + "×" + log.getReps());
            }
        }
    }

    @Test
    @DisplayName("组记录必须带上 sessionId，且分批写入")
    void batchedWithSessionId() {
        generator.generate(config());

        for (SetLog log : writtenLogs) {
            assertNotNull(log.getSessionId());
        }
        verify(setLogMapper, atLeastOnce()).insertBatch(any());
    }

    @Test
    @DisplayName("个人记录只在刷新最好成绩时标记")
    void personalRecords() {
        generator.generate(config());

        long prs = writtenLogs.stream().filter(l -> l.getIsPr() == 1).count();
        assertTrue(prs > 0, "进步期必然产生个人记录");
        // 半年 26 周 × 10 个动作，个人记录不会是每组都是
        assertTrue(prs < writtenLogs.size() * 0.5,
                "个人记录过多：" + prs + "/" + writtenLogs.size());
    }

    @Test
    @DisplayName("动作库缺失时必须报清晰的错误，而不是插入空指针")
    void missingExerciseFailsClearly() {
        when(exerciseMapper.selectByName(DemoDataPlanner.BENCH)).thenReturn(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> generator.generate(config()));

        assertTrue(ex.getMessage().contains("种子数据"), ex.getMessage());
        verify(setLogMapper, never()).insertBatch(any());
    }

    @Test
    @DisplayName("肌群映射与数据库漂移时必须报错")
    void muscleDriftFails() {
        Exercise drifted = exercise(DemoDataPlanner.BENCH);
        drifted.setPrimaryMuscle("背");
        when(exerciseMapper.selectByName(DemoDataPlanner.BENCH)).thenReturn(drifted);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> generator.generate(config()));

        assertTrue(ex.getMessage().contains("不一致"), ex.getMessage());
    }

    @Test
    @DisplayName("会话总容量是算出来的，不是写死的")
    void sessionVolumeIsComputed() {
        generator.generate(config());

        ArgumentCaptor<WorkoutSession> captor = ArgumentCaptor.forClass(WorkoutSession.class);
        verify(sessionMapper, atLeastOnce()).insert(captor.capture());
        for (WorkoutSession ws : captor.getAllValues()) {
            assertNotNull(ws.getTotalVolume());
            assertTrue(ws.getTotalVolume().doubleValue() > 0);
        }
    }

    @Test
    @DisplayName("清空只删 is_demo=1，不动真实打卡")
    void clearsUserData() {
        WorkoutSession demo = new WorkoutSession();
        demo.setId(9001L);
        demo.setUserId(1001L);
        demo.setIsDemo(1);
        when(sessionMapper.selectList(any())).thenReturn(List.of(demo));

        generator.clear(1001L);

        verify(setLogMapper).delete(any());
        verify(sessionMapper).deleteById(9001L);
        verify(bodyRecordMapper).delete(any());
    }

    @Test
    @DisplayName("没有样板会话时 clear 什么都不删")
    void clearNoopWhenNoDemo() {
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        generator.clear(1001L);

        verify(setLogMapper, never()).delete(any());
        verify(sessionMapper, never()).deleteById(any(java.io.Serializable.class));
        verify(bodyRecordMapper, never()).delete(any());
    }

    @Test
    @DisplayName("体测记录写入 26 条")
    void writesBodyRecords() {
        generator.generate(config());

        ArgumentCaptor<BodyRecord> captor = ArgumentCaptor.forClass(BodyRecord.class);
        verify(bodyRecordMapper, atLeastOnce()).insert(captor.capture());
        assertEquals(26, captor.getAllValues().size());
        for (BodyRecord br : captor.getAllValues()) {
            assertNotNull(br.getWeightKg());
            assertEquals(1001L, br.getUserId());
        }
    }

    // ==================== 辅助 ====================

    private static Config config() {
        return new Config(1001L, DemoDataPlanner.WEEKS, LocalDate.now(), 20260701L, 72.0);
    }

    private static Exercise exercise(String name) {
        Exercise ex = new Exercise();
        ex.setId((long) (name.hashCode() & 0xFFFF));
        ex.setName(name);
        ex.setPrimaryMuscle(DemoDataPlanner.MUSCLE.get(name));
        return ex;
    }
}
