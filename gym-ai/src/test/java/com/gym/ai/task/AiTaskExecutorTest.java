package com.gym.ai.task;

import com.gym.ai.mapper.AiTaskMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 异步执行器的行为锁死。
 *
 * <p>这一层的 bug 有个共同特征：**不报错，只是任务悄悄卡住**。
 * 所以必须用测试验证状态真的流转到了终态。
 *
 * <p>注意 mock 的两个默认陷阱：
 * 1. mapper.insert 什么都不做，任务对象不会自己进「数据库」
 * 2. mapper.markRunning 默认返回 0，CAS 永远失败，任务永远不会被拉起
 * 这两个都必须显式 stub，否则测试会假通过（任务没跑，断言也没验到）。
 */
class AiTaskExecutorTest {

    private AiTaskMapper taskMapper;
    private TaskSinkRegistry sinkRegistry;
    private AiExecutorProperties properties;
    private AiTaskExecutor executor;
    private final List<AiTask> pendingInDb = new ArrayList<>();

    @BeforeEach
    void setUp() {
        taskMapper = mock(AiTaskMapper.class);
        sinkRegistry = new TaskSinkRegistry();
        properties = new AiExecutorProperties();
        properties.setCoreSize(1);
        properties.setMaxSize(1);
        properties.setQueueCapacity(4);
        properties.setMaxRetry(2);
        properties.setRetryBackoffMs(10L);
        properties.setTaskTimeoutMinutes(1L);

        // insert 时把任务收进「库」，selectQueued 时取走——模拟真实 DB 的队列语义
        doAnswer(invocation -> {
            pendingInDb.add(invocation.getArgument(0));
            return 1;
        }).when(taskMapper).insert(any(AiTask.class));
        when(taskMapper.selectQueued(anyInt())).thenAnswer(invocation -> {
            List<AiTask> snapshot = new ArrayList<>(pendingInDb);
            pendingInDb.clear();
            return snapshot;
        });
        // CAS 抢占成功。默认返回 0 会让所有任务都跳过执行
        when(taskMapper.markRunning(anyString())).thenReturn(1);

        executor = new AiTaskExecutor(taskMapper, sinkRegistry, properties, List.of(new OkHandler()));
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("成功后应落库 SUCCESS，且结果是各片段的拼接")
    void successPath() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return 1;
        }).when(taskMapper).finishSuccess(anyString(), anyString(), anyInt(), anyInt(), anyInt());

        AiTask task = executor.submit(AiTaskType.WEEKLY_REVIEW, 1L, null);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "任务应在 5 秒内完成");
        verify(taskMapper).markRunning(task.getTaskId());
        verify(taskMapper).finishSuccess(eq(task.getTaskId()), eq("abc"), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("失败应重试到上限后落库 FAILED")
    void failureRetriesToLimit() throws Exception {
        // 先停掉 setUp 里的那个执行器。
        // 两个执行器共享同一个 mapper 时，谁先 poll 到队列是不确定的——
        // 这个任务被正常的执行器抢走就会变成"成功"，测试随机红。
        // 测试之间共享可变状态时，必须让消费方唯一。
        executor.shutdown();
        AiTaskExecutor failing = new AiTaskExecutor(taskMapper, sinkRegistry, properties,
                List.of(new ExplodingHandler()));
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return 1;
        }).when(taskMapper).finishFailed(anyString(), anyString(), anyInt());

        AiTask task = failing.submit(AiTaskType.WEEKLY_REVIEW, 1L, null);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "重试耗尽后应落库失败");
        verify(taskMapper).finishFailed(eq(task.getTaskId()), anyString(), anyInt());
        failing.shutdown();
    }

    @Test
    @DisplayName("任务完成后 SSE 通道必须移除，否则 map 无限增长")
    void sinkCleanedUpAfterCompletion() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return 1;
        }).when(taskMapper).finishSuccess(anyString(), anyString(), anyInt(), anyInt(), anyInt());

        executor.submit(AiTaskType.WEEKLY_REVIEW, 1L, null);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        // 必须轮询等待，不能拿到 latch 立刻断言。
        // 执行器里 finishSuccess 在 sinkRegistry.complete 之前，
        // latch 被 countDown 时通道还没移除，直接断言会随机失败。
        // 凡是"异步收尾动作"的断言，都要等到条件成立，而不是等到主事件完成。
        awaitUntil(() -> executor.activeSinks() == 0,
                "完成后应清理通道，当前仍有 " + executor.activeSinks() + " 个");
    }

    @Test
    @DisplayName("未注册的任务类型应直接失败，不能挂住 worker")
    void unregisteredTypeFailsFast() throws Exception {
        AiTask orphan = new AiTask();
        orphan.setTaskId("t-1");
        orphan.setTaskType(999);
        orphan.setStatus(AiTaskStatus.QUEUED.code());
        pendingInDb.add(orphan);

        // dispatch 是把任务丢进线程池，失败回调发生在 worker 线程，必须等待
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return 1;
        }).when(taskMapper).finishFailed(eq("t-1"), anyString(), anyInt());

        executor.dispatch();

        assertTrue(latch.await(5, TimeUnit.SECONDS), "未注册类型应快速失败");
        // 没有对应 handler 时不应产生成功结果
        verify(taskMapper, times(0)).finishSuccess(anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }

    /** 轮询等待条件成立。异步收尾动作的断言必须用它，一次性的断言必然有偶发失败 */
    private static void awaitUntil(BooleanSupplier condition, String message) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail(message);
    }

    /** 正常返回三段内容 */
    static class OkHandler implements AiTaskHandler {
        @Override
        public AiTaskType type() {
            return AiTaskType.WEEKLY_REVIEW;
        }

        @Override
        public Flux<String> execute(Long userId, String requestJson) {
            return Flux.just("a", "b", "c");
        }
    }

    /** 每次都抛异常 */
    static class ExplodingHandler implements AiTaskHandler {
        @Override
        public AiTaskType type() {
            return AiTaskType.WEEKLY_REVIEW;
        }

        @Override
        public Flux<String> execute(Long userId, String requestJson) {
            return Flux.error(new IllegalStateException("模型服务不可用"));
        }
    }
}
