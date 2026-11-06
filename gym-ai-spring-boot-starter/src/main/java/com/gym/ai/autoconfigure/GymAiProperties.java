package com.gym.ai.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 自定义 Starter 的配置项。
 * 作用：把模型相关的所有参数从业务代码里抽干净，
 * 切换云端 / 本地模型时只改 yml，不碰一行 Java。
 */
@Data
@ConfigurationProperties(prefix = "gym.ai")
public class GymAiProperties {

    /** dev = 云端 DeepSeek；local = Ollama 私有化；demo = 答辩演示切本地 */
    private String provider = "dev";

    /** 并发任务上限，超过直接拒绝，防止把本地 7b 模型拖垮 */
    private int maxConcurrentTask = 8;

    /** 每日 Token 预算，超出后拒绝调用 */
    private long dailyTokenLimit = 200_000L;

    /** 召回为空时直接拒绝调用模型，避免通用知识冒充领域知识 */
    private boolean recallEmptyRefuse = true;

    /** 混合检索召回条数 */
    private int recallTopK = 6;

    /**
     * 系统提示词。
     * 这里刻意写入「不确定就说不知道」——把拒答写进系统提示，
     * 比事后在业务代码里过滤幻觉要可靠得多。
     */
    private String systemPrompt = "你是健身领域的专业助手。"
            + "只依据给定的资料片段回答，不得使用片段之外的知识。"
            + "若资料不足以支撑答案，必须明确回答「资料未覆盖」，不要猜测。"
            + "涉及具体数值时，只能引用资料中的原始数字，不得自行推算。";

    private Model chat = new Model();

    @Data
    public static class Model {

        private String name = "deepseek-chat";

        private double temperature = 0.7;

        /**
         * 上下文长度。
         * Ollama 默认为 2048，Prompt 超长时会被静默截断——不报错、不告警，
         * 只是模型再也看不到尾部内容，表现为一本正经地编造数值。
         * 这是本地私有化部署最常见的坑，必须显式覆盖。
         */
        private int contextLength = 8192;
    }
}
