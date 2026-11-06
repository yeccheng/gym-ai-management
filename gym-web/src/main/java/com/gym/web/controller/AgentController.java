package com.gym.web.controller;

import com.gym.ai.agent.AgentChatMemory;
import com.gym.ai.agent.AgentReply;
import com.gym.ai.agent.FitnessAgentService;
import com.gym.ai.autoconfigure.GymAiException;
import com.gym.common.exception.BusinessException;
import com.gym.common.result.R;
import com.gym.common.result.ResultCode;
import com.gym.common.security.UserContext;
import com.gym.web.service.MemberProfilePrompt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多智能体对话入口：路由（教练 / 营养师 / 数据）+ 滑动窗口记忆 + 营养侧 RAG。
 *
 * <p>与 RAG 通识接口分工：
 * <ul>
 *   <li>{@code /knowledge/qa} ——「什么是减载周」</li>
 *   <li>{@code /agent/chat|ask} ——「我该不该减载 / 今天蛋白够不够」</li>
 * </ul>
 */
@Slf4j
@Tag(name = "健身智能体", description = "多 Agent 编排：意图路由 + ChatMemory + Function Calling / RAG")
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {

    private static final int MAX_QUESTION_LENGTH = 500;

    private final FitnessAgentService agentService;
    private final MemberProfilePrompt memberProfilePrompt;
    private final AgentChatMemory agentChatMemory;

    @Operation(summary = "流式对话（SSE）",
            description = "先推 status（含路由角色），再同步取答并按 SSE 分片推送")
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestParam String question) {
        long userId = requireUserId();
        String safeQuestion = memberProfilePrompt.append(userId, validateQuestion(question));

        ServerSentEvent<String> thinking = ServerSentEvent.<String>builder()
                .event("status")
                .data("{\"type\":\"status\",\"text\":\"多智能体编排中…\"}")
                .build();

        Flux<ServerSentEvent<String>> body = Flux.<ServerSentEvent<String>>defer(() -> {
                    UserContext.setUserId(userId);
                    try {
                        AgentReply reply = agentService.askWithRole(safeQuestion);
                        if (reply == null || reply.answer() == null || reply.answer().isBlank()) {
                            return Flux.just(errorEvent("模型返回空内容"));
                        }
                        ServerSentEvent<String> roleEvt = ServerSentEvent.<String>builder()
                                .event("agent")
                                .data("{\"role\":\"" + reply.role().code()
                                        + "\",\"name\":\"" + reply.role().displayName() + "\"}")
                                .build();
                        List<ServerSentEvent<String>> chunks = new ArrayList<>();
                        chunks.add(roleEvt);
                        for (String part : splitForSse(reply.answer())) {
                            chunks.add(ServerSentEvent.<String>builder().data(part).build());
                        }
                        return Flux.fromIterable(chunks);
                    } catch (Exception e) {
                        String msg = e.getMessage();
                        if (msg == null || msg.isBlank()) {
                            msg = e.getClass().getName();
                        }
                        log.error("智能体 SSE 对话失败: {}", msg, e);
                        return Flux.just(errorEvent(msg));
                    } finally {
                        UserContext.clear();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());

        return Flux.concat(Flux.just(thinking), body)
                .onErrorResume(e -> {
                    String msg = e.getMessage();
                    if (msg == null || msg.isBlank()) {
                        msg = e.getClass().getName();
                    }
                    log.error("智能体 SSE 外层异常: {}", msg, e);
                    return Flux.just(errorEvent(msg));
                });
    }

    private static ServerSentEvent<String> errorEvent(String msg) {
        return ServerSentEvent.<String>builder()
                .event("error")
                .data(msg)
                .build();
    }

    private static List<String> splitForSse(String answer) {
        List<String> parts = new ArrayList<>();
        int size = 48;
        for (int i = 0; i < answer.length(); i += size) {
            parts.add(answer.substring(i, Math.min(i + size, answer.length())));
        }
        return parts;
    }

    @Operation(summary = "同步对话", description = "返回 answer + 接诊角色 agent/agentName")
    @PostMapping("/ask")
    public R<Map<String, Object>> ask(@RequestBody AskRequest request) {
        long userId = requireUserId();
        String question = memberProfilePrompt.append(userId,
                validateQuestion(request == null ? null : request.question()));
        try {
            UserContext.setUserId(userId);
            AgentReply reply = agentService.askWithRole(question);
            if (reply == null || reply.answer() == null || reply.answer().isBlank()) {
                throw new BusinessException(ResultCode.SYSTEM_ERROR,
                        "AI 教练暂时开小差了，请检查网络或稍后再试");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("answer", reply.answer());
            body.put("agent", reply.role().code());
            body.put("agentName", reply.role().displayName());
            return R.ok(body);
        } catch (GymAiException e) {
            log.error("智能体同步对话失败: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, e.getMessage());
        } finally {
            UserContext.clear();
        }
    }

    @Operation(summary = "清空当前用户对话记忆")
    @DeleteMapping("/memory")
    public R<Void> clearMemory() {
        long userId = requireUserId();
        agentChatMemory.clear(userId);
        return R.ok();
    }

    @Operation(summary = "查看已注册的智能体工具", description = "全量工具数，正常应为 7；各角色使用子集")
    @GetMapping("/tools/count")
    public R<Map<String, Object>> toolCount() {
        return R.ok(Map.of("count", agentService.toolCount()));
    }

    private String validateQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "问题不能为空");
        }
        String trimmed = question.trim();
        if (trimmed.length() > MAX_QUESTION_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "问题过长，请控制在 " + MAX_QUESTION_LENGTH + " 字以内");
        }
        return trimmed;
    }

    private long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录");
        }
        return userId;
    }

    public record AskRequest(String question) {
    }
}
