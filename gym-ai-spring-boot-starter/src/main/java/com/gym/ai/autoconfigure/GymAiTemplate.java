package com.gym.ai.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * 全项目唯一的模型调用出口。
 *
 * <p>业务代码不允许直接注入 ChatClient，统一通过本类调用。
 * 好处有三个：模型参数集中在一处、Token 消耗有账可查、并发有上限保护。
 */
public class GymAiTemplate {

    private static final Logger log = LoggerFactory.getLogger(GymAiTemplate.class);

    private final ChatClient chatClient;
    private final TokenBudget tokenBudget;
    private final GymAiProperties properties;
    private final Semaphore concurrency;
    private final String apiKeyFingerprint;

    public GymAiTemplate(ChatClient chatClient,
                         TokenBudget tokenBudget,
                         GymAiProperties properties,
                         String apiKey) {
        this.chatClient = chatClient;
        this.tokenBudget = tokenBudget;
        this.properties = properties;
        this.concurrency = new Semaphore(Math.max(1, properties.getMaxConcurrentTask()));
        this.apiKeyFingerprint = fingerprint(apiKey);
        // 启动时只打前 8 位，方便确认读到的是哪把 key，又不把完整密钥打进日志
        log.info("GymAiTemplate 已就绪，DeepSeek/OpenAI api-key 前缀={}…（完整密钥不打印）",
                apiKeyFingerprint);
    }

    private static String fingerprint(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "(空)";
        }
        String trimmed = apiKey.trim();
        return trimmed.substring(0, Math.min(8, trimmed.length()));
    }

    public String chat(String userText) {
        return chat(userText, null, null);
    }

    public String chat(String userText, String systemText) {
        return chat(userText, systemText, null);
    }

    /**
     * 同步调用，可挂载工具。
     *
     * <p>工具必须经由这里注册，不允许业务代码自己拿 ChatClient 拼一条请求。
     * 否则 Token 记账和并发控制就被绕过去了——
     * 一次带工具的请求实际会发两轮（先问要调哪个工具，再带着结果问一次），
     * 消耗的是普通问答的两三倍，不统一记账很快就超支。
     */
    public String chat(String userText, String systemText, List<ToolCallback> toolCallbacks) {
        long cost = estimateTokens(userText);
        if (!tokenBudget.available(cost)) {
            throw new GymAiException(GymAiException.AiErrorType.BUDGET_EXCEEDED,
                    "今日 Token 预算已用完，剩余 0");
        }
        if (!concurrency.tryAcquire()) {
            throw new GymAiException(GymAiException.AiErrorType.CONCURRENCY_FULL,
                    "当前并发任务已满（上限 " + properties.getMaxConcurrentTask() + "），请稍后再试");
        }
        try {
            if (chatClient == null) {
                throw new GymAiException(GymAiException.AiErrorType.MODEL_UNAVAILABLE,
                        "ChatClient 为空（自动装配失败）");
            }
            var spec = chatClient.prompt().user(userText);
            if (systemText != null && !systemText.isBlank()) {
                spec = spec.system(systemText);
            }
            if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
                spec = spec.toolCallbacks(toolCallbacks);
            }
            String result = spec.call().content();
            if (result == null || result.isBlank()) {
                // 模型偶发返回空 content（鉴权失败被框架吞掉、或工具轮次后第二轮无正文）。
                // 排查期把明确原因塞进 message，方便 Network 面板直接看。
                log.error("模型返回空内容，api-key前缀={}…，请检查 DeepSeek 余额/密钥与 spring.ai.openai 配置",
                        apiKeyFingerprint);
                throw new GymAiException(GymAiException.AiErrorType.MODEL_UNAVAILABLE,
                        "模型返回空 content（常见于工具调用后第二轮无正文；api-key前缀="
                                + apiKeyFingerprint + "…；toolCount="
                                + (toolCallbacks == null ? 0 : toolCallbacks.size()) + "）");
            }
            tokenBudget.record(cost + estimateTokens(result));
            return result;
        } catch (GymAiException e) {
            throw e;
        } catch (Exception e) {
            // 把类名 / message / 堆栈帧塞进返回，不要再盖成「开小差」
            log.error("调用大模型失败，api-key前缀={}…，错误类型={}，消息={}",
                    apiKeyFingerprint, e.getClass().getSimpleName(), e.getMessage(), e);
            throw new GymAiException(GymAiException.AiErrorType.MODEL_UNAVAILABLE,
                    formatDebugError(e), e);
        } finally {
            concurrency.release();
        }
    }

    /**
     * 排查专用：把异常类名、message、cause、com.gym 堆栈帧拼成一段可读文案。
     * 这段会进 HTTP 响应的 message 字段，浏览器 Network 里能直接看到。
     */
    static String formatDebugError(Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append("[AI调试] ");
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 4) {
            if (depth > 0) {
                sb.append(" <- caused by ");
            }
            sb.append(cur.getClass().getName());
            sb.append(": ");
            sb.append(cur.getMessage() == null ? "(null)" : cur.getMessage());
            depth++;
            cur = cur.getCause();
        }
        sb.append(" | stack: ");
        sb.append(topStackFrames(e, 6));
        return sb.toString();
    }

    /** 优先挑 com.gym 帧，不够再补通用帧，方便一眼定位业务代码 */
    private static String topStackFrames(Throwable e, int max) {
        if (e == null || e.getStackTrace() == null) {
            return "(no stack)";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StackTraceElement el : e.getStackTrace()) {
            String line = el.toString();
            if (line.contains("com.gym")) {
                if (n > 0) {
                    sb.append(" ; ");
                }
                sb.append("at ").append(line);
                if (++n >= max) {
                    break;
                }
            }
        }
        if (n == 0) {
            for (StackTraceElement el : e.getStackTrace()) {
                if (n > 0) {
                    sb.append(" ; ");
                }
                sb.append("at ").append(el);
                if (++n >= Math.min(max, 4)) {
                    break;
                }
            }
        }
        return sb.length() == 0 ? "(empty stack)" : sb.toString();
    }

    public Flux<String> stream(String userText) {
        return stream(userText, null, null);
    }

    /**
     * 流式输出，可挂载工具。
     *
     * <p>信号量必须在 doFinally 里释放——Flux 是惰性的，
     * 写在方法体里会在订阅完成前就释放，等于没有限流。
     */
    public Flux<String> stream(String userText, String systemText, List<ToolCallback> toolCallbacks) {
        if (!concurrency.tryAcquire()) {
            return Flux.error(new GymAiException(GymAiException.AiErrorType.CONCURRENCY_FULL,
                    "当前并发任务已满，请稍后再试"));
        }
        long cost = estimateTokens(userText);
        if (!tokenBudget.available(cost)) {
            concurrency.release();
            return Flux.error(new GymAiException(GymAiException.AiErrorType.BUDGET_EXCEEDED,
                    "今日 Token 预算已用完"));
        }
        try {
            if (chatClient == null) {
                concurrency.release();
                return Flux.error(new GymAiException(GymAiException.AiErrorType.MODEL_UNAVAILABLE,
                        "ChatClient 为空（自动装配失败）"));
            }
            StringBuilder buffer = new StringBuilder();
            var spec = chatClient.prompt().user(userText);
            if (systemText != null && !systemText.isBlank()) {
                spec = spec.system(systemText);
            }
            if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
                spec = spec.toolCallbacks(toolCallbacks);
            }
            // 注意：DeepSeek + 工具调用时 ChatClient.stream() 容易直接掐断连接。
            // 业务入口 /agent/chat 已改为「同步 ask 再 SSE 分片」；本方法留给需要真流式的场景。
            // onErrorResume：把异常变成一条可读文本，避免 Flux 以 error 信号关连接。
            return spec.stream()
                    .content()
                    .doOnNext(buffer::append)
                    .doOnError(e -> log.error("流式调用大模型失败，api-key前缀={}…，错误={}",
                            apiKeyFingerprint, e.getMessage(), e))
                    .onErrorResume(e -> {
                        String msg = e instanceof GymAiException
                                ? e.getMessage()
                                : formatDebugError(e);
                        return Flux.just("[流式错误] " + msg);
                    })
                    .doFinally(signal -> {
                        concurrency.release();
                        tokenBudget.record(cost + estimateTokens(buffer.toString()));
                    });
        } catch (Exception e) {
            // 构建 Flux 之前就炸了（NPE、参数非法等），同样带上调试信息
            concurrency.release();
            log.error("构建流式调用失败，api-key前缀={}…", apiKeyFingerprint, e);
            return Flux.error(new GymAiException(GymAiException.AiErrorType.MODEL_UNAVAILABLE,
                    formatDebugError(e), e));
        }
    }

    public long remainingTokens() {
        return tokenBudget.remaining();
    }

    /**
     * 粗略估算 Token 数：中文按 1 字约 1.5 token，英文按 4 字符约 1 token。
     * 精度不重要，限流只需要一个数量级正确的估计值。
     */
    public static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long cjk = text.chars().filter(c -> c > 0x2E80).count();
        long other = text.length() - cjk;
        return (long) (cjk * 1.5 + other / 4.0) + 8;
    }
}
