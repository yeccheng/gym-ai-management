package com.gym.ai.task;

import com.gym.ai.mapper.AiTaskMapper;

import com.gym.ai.autoconfigure.GymAiTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * AI 异步任务执行器。
 *
 * <p>解决的问题：本地 7b 模型生成一份周报要 8~15 秒，生成一份周期化计划要 1~2 分钟，
 * 同步 HTTP 必然超时。于是拆成「提交返回 taskId + 后台执行 + SSE 逐段推送」。
 *
 * <p>四个关键设计：
 * 1. <b>落库优先</b>——提交即入库，任务有独立生命周期，可查询、可重试、可恢复
 * 2. <b>CAS 抢占</b>——markRunning 带 status = 0 条件，防止重启后被两个 worker 重复执行
 * 3. <b>拒绝即留库</b>——线程池满时不抛错给前端，任务回到排队状态等下次调度
 * 4. <b>业务零耦合</b>——执行器只认 AiTaskHandler，加新功能不用改这里
 */
@Slf4j
@Component
@EnableConfigurationProperties(AiExecutorProperties.class)
public class AiTaskExecutor {

    private final AiTaskMapper taskMapper;
    private final TaskSinkRegistry sinkRegistry;
    private final AiExecutorProperties properties;
    private final Map<Integer, AiTaskHandler> handlers = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workerPool;

    public AiTaskExecutor(AiTaskMapper taskMapper,
                          TaskSinkRegistry sinkRegistry,
                          AiExecutorProperties properties,
                          List<AiTaskHandler> handlerList) {
        this.taskMapper = taskMapper;
        this.sinkRegistry = sinkRegistry;
        this.properties = properties;
        for (AiTaskHandler handler : handlerList) {
            this.handlers.put(handler.type().code(), handler);
        }
        this.workerPool = new ThreadPoolExecutor(
                Math.max(1, properties.getCoreSize()),
                Math.max(properties.getCoreSize(), properties.getMaxSize()),
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, properties.getQueueCapacity())),
                // 刻意不用 CallerRunsPolicy：那会让提交任务的 Web 线程自己去跑模型，
                // 请求线程被占住几十秒，等于没做异步。
                // 必须抛 RejectedExecutionException，dispatch 才能捕获并把任务放回队列。
                (runnable, pool) -> {
                    log.debug("执行器已满（活跃 {}/{}），任务留在数据库排队",
                            pool.getActiveCount(), pool.getMaximumPoolSize());
                    throw new RejectedExecutionException("执行器已满");
                });
    }

    /**
     * 提交任务。落库后立即返回，调用方拿到 taskId 就可以去轮询或连 SSE。
     */
    public AiTask submit(AiTaskType type, Long userId, String requestJson) {
        AiTask task = new AiTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setUserId(userId);
        task.setTaskType(type.code());
        task.setStatus(AiTaskStatus.QUEUED.code());
        task.setRequestJson(requestJson);
        taskMapper.insert(task);
        // 先建通道再调度：否则任务可能在 SSE 连上前就已经开始产出，前端拿不到开头
        sinkRegistry.create(task.getTaskId());
        dispatch();
        return task;
    }

    /** 拉取式调度。重启恢复与定时兜底都走这一个入口 */
    public void dispatch() {
        List<AiTask> queued = taskMapper.selectQueued(properties.getQueueCapacity());
        for (AiTask task : queued) {
            if (taskMapper.markRunning(task.getTaskId()) == 0) {
                // CAS 失败说明别的实例已抢占，跳过
                continue;
            }
            try {
                workerPool.execute(() -> run(task));
            } catch (RejectedExecutionException e) {
                // 队列满：状态改回排队，等下一次 dispatch。任务不丢，也不报错给用户。
                taskMapper.resetToQueued(task.getTaskId());
            }
        }
    }

    private void run(AiTask task) {
        AiTaskHandler handler = handlers.get(task.getTaskType());
        if (handler == null) {
            taskMapper.finishFailed(task.getTaskId(), "未注册的任务处理器: " + task.getTaskType(), 0);
            sinkRegistry.error(task.getTaskId());
            return;
        }

        long start = System.currentTimeMillis();
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                StringBuilder all = new StringBuilder();
                handler.execute(task.getUserId(), task.getRequestJson())
                        .doOnNext(chunk -> {
                            all.append(chunk);
                            sinkRegistry.emit(task.getTaskId(), chunk);
                        })
                        .then()
                        .block(Duration.ofMinutes(Math.max(1, properties.getTaskTimeoutMinutes())));

                int elapsed = (int) (System.currentTimeMillis() - start);
                taskMapper.finishSuccess(
                        task.getTaskId(),
                        all.toString(),
                        (int) GymAiTemplate.estimateTokens(task.getRequestJson()),
                        (int) GymAiTemplate.estimateTokens(all.toString()),
                        elapsed);
                sinkRegistry.complete(task.getTaskId());
                log.info("任务 {} 完成，耗时 {} ms，产出 {} 字", task.getTaskId(), elapsed, all.length());
                return;
            } catch (Exception e) {
                log.warn("任务 {} 第 {}/{} 次执行失败: {}", task.getTaskId(), attempt, properties.getMaxRetry() + 1, e.getMessage());
                if (attempt > properties.getMaxRetry()) {
                    taskMapper.finishFailed(task.getTaskId(), abbreviate(e),
                            (int) (System.currentTimeMillis() - start));
                    sinkRegistry.error(task.getTaskId());
                    return;
                }
                // 线性退避：第 1 次等 1s，第 2 次等 2s。给模型端一个恢复窗口。
                try {
                    Thread.sleep(properties.getRetryBackoffMs() * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    taskMapper.finishFailed(task.getTaskId(), "任务被中断", 0);
                    sinkRegistry.error(task.getTaskId());
                    return;
                }
            }
        }
    }

    private String abbreviate(Exception e) {
        String message = e.getClass().getSimpleName() + ": " + e.getMessage();
        return message.length() > 480 ? message.substring(0, 480) : message;
    }

    public int activeSinks() {
        return sinkRegistry.activeCount();
    }

    /** 测试与优雅停机用 */
    public void shutdown() {
        workerPool.shutdown();
    }
}
