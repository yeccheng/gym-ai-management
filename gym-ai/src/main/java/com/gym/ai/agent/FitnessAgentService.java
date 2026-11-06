package com.gym.ai.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 对外门面：兼容旧调用方，内部已升级为多智能体编排
 * （{@link IntentRouter} + 三角色 Prompt + {@link AgentChatMemory} + 营养 RAG）。
 *
 * <p>与 {@code /knowledge/qa} 仍分工：通识走知识库接口；「对我怎么样」走本服务。
 */
@Service
@RequiredArgsConstructor
public class FitnessAgentService {

    private final MultiAgentOrchestrator orchestrator;

    /**
     * 流式对话（底层仍同步取答；真正 SSE 分片在 Controller）。
     */
    public Flux<String> chat(String question) {
        AgentReply reply = orchestrator.ask(question);
        return Flux.just(reply.answer());
    }

    /** 同步版本，供 /agent/ask、SSE 与异步任务使用 */
    public String ask(String question) {
        return orchestrator.ask(question).answer();
    }

    /** 带角色信息的应答，供前端展示「本轮由谁接诊」 */
    public AgentReply askWithRole(String question) {
        return orchestrator.ask(question);
    }

    public int toolCount() {
        return orchestrator.toolCount();
    }

    /**
     * 力量类问题在用户话后面钉一句系统约束。
     * 只靠 system prompt，模型仍会偷懒去调 getRecentWorkouts 念周报——
     * 把路由钉在用户消息末尾，工具选择稳得多。
     */
    static String enrich(String question) {
        if (question == null || question.isBlank()) {
            return question;
        }
        String q = question.trim();
        if (!looksLikeStrengthQuestion(q)) {
            return q;
        }
        return q + "\n\n（系统约束：必须先调 getPersonalBest 与 getStrengthTrend；"
                + "若 firstTrainingDayOnly 或只有 1 个训练日：只报今天重量×次数，明确还不能比涨跌，"
                + "禁止破纪录、禁止编造 88kg 等历史数字；"
                + "若 justBrokePlateau=true：必须说刚破平台期并报卡住周数，禁止说没卡住；"
                + "若有多日历史才按「涨没涨 / 数字 / 下次举多少」三句答；"
                + "禁止先谈容量、推拉腿、体重周报，禁止反问用户想问什么。）";
    }

    static boolean looksLikeStrengthQuestion(String q) {
        String s = q.toLowerCase();
        boolean lift = s.contains("卧推") || s.contains("深蹲") || s.contains("硬拉")
                || s.contains("肩推") || s.contains("推举") || s.contains("bench")
                || s.contains("squat") || s.contains("deadlift");
        boolean intent = s.contains("卡住") || s.contains("瓶颈") || s.contains("涨")
                || s.contains("进步") || s.contains("退步") || s.contains("能推")
                || s.contains("能蹲") || s.contains("能拉") || s.contains("下次")
                || s.contains("举多少") || s.contains("加重量") || s.contains("破纪录")
                || s.contains("平台");
        return lift && intent;
    }
}
