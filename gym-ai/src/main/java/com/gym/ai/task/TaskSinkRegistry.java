package com.gym.ai.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个任务一条 SSE 通道。
 *
 * <p>为什么需要它：任务在后台线程跑，前端 SSE 连接是另一条线程，
 * 两者之间必须有一个解耦的缓冲区。用内存 Sinks 而不是 Redis Pub/Sub，
 * 是因为本项目是单体——引入 Redis 广播属于为了技术而技术。
 *
 * <p>multicast + onBackpressureBuffer：多个订阅者都能收到，
 * 下游慢的时候缓冲而不是抛错。
 */
@Slf4j
@Component
public class TaskSinkRegistry {

    private static final int BUFFER_SIZE = 1024;

    private final Map<String, Sinks.Many<String>> sinks = new ConcurrentHashMap<>();

    /** 提交任务时就建好通道，避免 SSE 连接时任务已经开始、无通道可订阅 */
    public Sinks.Many<String> create(String taskId) {
        return sinks.computeIfAbsent(taskId,
                k -> Sinks.many().multicast().onBackpressureBuffer(BUFFER_SIZE, false));
    }

    public Sinks.Many<String> get(String taskId) {
        return sinks.get(taskId);
    }

    public void emit(String taskId, String chunk) {
        Sinks.Many<String> sink = sinks.get(taskId);
        if (sink == null) {
            return;
        }
        // 方法名是 tryEmitNext 而非 tryNext，后者在 Sinks.Many 上不存在
        Sinks.EmitResult result = sink.tryEmitNext(chunk);
        // isFailure 不代表一定要处理——订阅者断开时 emit 失败是正常现象
        if (result.isFailure()) {
            log.debug("任务 {} 推送片段失败: {}", taskId, result);
        }
    }

    public void complete(String taskId) {
        Sinks.Many<String> sink = sinks.get(taskId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
        // 订阅者持有的是同一个引用，remove 不会切断已建立的连接，
        // 但能防止 map 无限增长——任务完成后必须清理，否则是内存泄漏。
        sinks.remove(taskId);
    }

    public void error(String taskId) {
        Sinks.Many<String> sink = sinks.get(taskId);
        if (sink != null) {
            // tryEmitError 必须带 Throwable，无参重载不存在
            sink.tryEmitError(new IllegalStateException("AI 任务执行失败"));
        }
        sinks.remove(taskId);
    }

    public int activeCount() {
        return sinks.size();
    }
}
