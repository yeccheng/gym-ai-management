package com.gym.ai.task;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 异步执行器参数。
 *
 * <p>并发度必须显式限制：本地 7b 模型在 CPU 上推理时，
 * 同时跑 4 个任务会让每个任务都慢 4 倍，还会互相抢内存导致 OOM。
 * 宁可排队，不可压垮。
 */
@Data
@ConfigurationProperties(prefix = "gym.ai.executor")
public class AiExecutorProperties {

    private int coreSize = 2;

    private int maxSize = 4;

    /** 队列容量。满了之后新提交的任务留在数据库里排队，不丢 */
    private int queueCapacity = 32;

    /** 失败重试次数。2 次是经验值：模型偶发抖动重试能救，真错误重试无益 */
    private int maxRetry = 2;

    /** 重试退避基数，第 n 次重试等待 n × base */
    private long retryBackoffMs = 1000L;

    /** 单次执行硬超时。防止模型卡死把 worker 永久占住 */
    private long taskTimeoutMinutes = 5L;
}
