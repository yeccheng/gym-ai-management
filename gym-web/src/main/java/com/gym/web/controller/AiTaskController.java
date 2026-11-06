package com.gym.web.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gym.ai.task.AiExecutorProperties;
import com.gym.ai.task.AiTask;
import com.gym.ai.task.AiTaskExecutor;
import com.gym.ai.mapper.AiTaskMapper;
import com.gym.ai.task.AiTaskStatus;
import com.gym.ai.task.AiTaskType;
import com.gym.ai.task.TaskSinkRegistry;
import com.gym.common.exception.BusinessException;
import com.gym.common.result.R;
import com.gym.common.result.ResultCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.gym.common.security.UserContext;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 任务三件套：提交 / 查询 / 流式订阅。
 *
 * <p>Controller 统一放在 gym-web，gym-ai 只放业务与处理器——
 * 这样 gym-ai 不依赖 Web 鉴权细节，依赖方向保持单向。
 *
 * <p>前端协作方式：
 * 1. POST /ai/task/review 拿 taskId
 * 2. 排队阶段轮询 GET /ai/task/{taskId}，status 从 0 变 1 说明开始了
 * 3. 生成阶段连 GET /ai/task/{taskId}/stream 收 SSE
 * 4. 断线重连时先查一次，若已 SUCCESS 直接拿完整结果
 */
@Slf4j
@Tag(name = "AI 任务", description = "异步提交、状态查询、SSE 流式订阅")
@RestController
@RequestMapping("/ai/task")
@RequiredArgsConstructor
public class AiTaskController {

    private final AiTaskExecutor executor;
    private final AiTaskMapper taskMapper;
    private final TaskSinkRegistry sinkRegistry;
    private final AiExecutorProperties executorProperties;
    private final ObjectMapper objectMapper;

    @Operation(summary = "提交周报复盘任务", description = "立即返回 taskId，不等待模型生成")
    @PostMapping("/review")
    public R<Map<String, Object>> submitReview(@RequestBody(required = false) Map<String, Object> body) {
        AiTask task = executor.submit(AiTaskType.WEEKLY_REVIEW, currentUserId(), toJson(body));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.getTaskId());
        payload.put("status", AiTaskStatus.of(task.getStatus()).label());
        payload.put("queueCapacity", executorProperties.getQueueCapacity());
        return R.ok(payload);
    }

    @Operation(summary = "查询任务状态与结果")
    @GetMapping("/{taskId}")
    public R<AiTask> detail(@PathVariable String taskId) {
        AiTask task = taskMapper.selectByTaskId(taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "任务不存在");
        }
        return R.ok(task);
    }

    @Operation(summary = "SSE 订阅任务输出", description = "排队阶段请轮询详情接口，开始生成后再连此端点")
    @GetMapping(value = "/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@PathVariable String taskId) {
        AiTask task = taskMapper.selectByTaskId(taskId);
        if (task == null) {
            return Flux.error(new BusinessException(ResultCode.NOT_FOUND, "任务不存在"));
        }

        // 已完成：直接推完整结果。
        // 少了这一条，晚到的订阅者会连上一条已 complete 的空流，前端永远等不到数据。
        if (AiTaskStatus.of(task.getStatus()) == AiTaskStatus.SUCCESS) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .data(task.getResultJson() == null ? "" : task.getResultJson())
                    .build());
        }

        Sinks.Many<String> sink = sinkRegistry.get(taskId);
        if (sink == null) {
            return Flux.just(ServerSentEvent.<String>builder().data("[任务已结束，请查询详情接口]").build());
        }
        return sink.asFlux()
                .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build())
                .doFinally(signal -> log.debug("SSE 关闭 taskId={} signal={}", taskId, signal));
    }

    private Long currentUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录");
        }
        return userId;
    }

    private String toJson(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.warn("请求体序列化失败，按空请求处理: {}", e.getMessage());
            return null;
        }
    }
}
