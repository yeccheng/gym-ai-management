package com.gym.ai.autoconfigure;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import reactor.core.publisher.Flux;

/**
 * 自定义 Starter 的自动装配。
 *
 * <p>这个 Starter 存在的意义不是炫技，而是解决三个真实问题：
 * 1. 模型参数散落在各处，切换云端/本地要改一堆地方——这里集中管理
 * 2. 本地 7b 模型并发稍高就排队卡死——这里卡住并发上限
 * 3. 演示时容易被反复点击烧掉预算——这里卡住每日 Token
 */
@AutoConfiguration
@EnableConfigurationProperties(GymAiProperties.class)
@ConditionalOnClass({ChatClient.class, Flux.class})
public class GymAiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TokenBudget tokenBudget(GymAiProperties properties) {
        return new TokenBudget(properties.getDailyTokenLimit());
    }

    /**
     * 全项目唯一的 ChatClient 构造点。
     * 业务侧拿到的是 GymAiTemplate，因此模型名、系统提示、并发上限
     * 全部收敛在这里，切换模型时业务代码零改动。
     */
    @Bean
    @ConditionalOnMissingBean
    public GymAiTemplate gymAiTemplate(ChatClient.Builder builder,
                                       TokenBudget tokenBudget,
                                       GymAiProperties properties,
                                       @Value("${spring.ai.openai.api-key:}") String apiKey) {
        // 不要在这里绑死「只依据资料片段」那套 RAG 系统提示——
        // 智能体对话会自己传 FitnessAgentService 的教练 Prompt；
        // 若再叠一层「资料未覆盖就拒答」，工具查完数据后模型容易空回复。
        ChatClient client = builder.build();
        return new GymAiTemplate(client, tokenBudget, properties, apiKey);
    }
}
