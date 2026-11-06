package com.gym.ai.task;

import reactor.core.publisher.Flux;

/**
 * 任务处理器。
 *
 * <p>执行器不认识任何业务——它只负责线程池、状态流转、重试和 SSE 分发。
 * 业务通过实现这个接口注册进来，返回一个 Flux，执行器负责把每一段转发给前端。
 * 这样加新功能不用碰执行器，也是「开闭原则」最实用的一次落地。
 */
public interface AiTaskHandler {

    AiTaskType type();

    /**
     * 执行业务并返回流式结果。
     * requestJson 是提交时落库的快照，重试时用同一份输入，保证结果可复现。
     */
    Flux<String> execute(Long userId, String requestJson);
}
