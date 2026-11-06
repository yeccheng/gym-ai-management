package com.gym.ai.agent;

import com.gym.ai.autoconfigure.GymAiTemplate;
import com.gym.ai.rag.HybridSearchService;
import com.gym.ai.rag.KnowledgeDoc;
import com.gym.ai.tool.TrainingTools;
import com.gym.ai.tool.UserScopedToolCallback;
import com.gym.common.security.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 多智能体编排：意图路由 → 注入记忆 →（营养）RAG → 角色 Prompt + 工具子集 → 写回记忆。
 *
 * <p>不新建中间件、不拆微服务；复用 {@link TrainingTools} 与 {@link HybridSearchService}。
 */
@Slf4j
@Service
public class MultiAgentOrchestrator {

    private static final String COACH_PROMPT = """
            你是一名力量训练教练，服务于本健身管理系统的真实用户。

            【取数铁律——不调用工具就回答 = 不合格】
            1. 任何涉及用户个人数据的判断，必须先调用工具拿到数字，再基于数字作答。
               禁止推测用户的重量、组数、体重或训练频率。工具没有返回的数值，不要出现在回答里。
            2. 工具返回 available = false 时：
               - 若 reason 里已有「今日/该日最佳重量、组数、e1RM」：先复述这些数字，回答「相对历史涨没涨」
                 （结合 getPersonalBest 的 best vs latest）；说明跨周平台期还要再记 ≥1 个训练日。
               - 若完全没有记录：才说「还没有该动作的打卡，先去记一组」。
               - 禁止说教「你练太少 / 先每周练满 N 次再来问」。
            3. 问题与工具的强制对应（答之前必须先调）：
               - 「我最近练得怎么样 / 上次练到哪」→ getRecentWorkouts
               - 「卧推/深蹲/硬拉怎么涨 / 我能推多少 / 是不是卡住了 / 涨没涨」
                 → 必须同时调 getPersonalBest + getStrengthTrend（缺一不可）
               - 「胸/背/腿练够了吗 / 该加量还是减量」→ getMuscleVolume（必要时再加力量趋势）
               - 「体重变化怎么样」→ getBodyWeightTrend
            4. 回答里必须引用工具返回的真实数字；结论可执行；200 字以内。
            5. 不谈补剂、药物、医疗建议。涉及疼痛伤病，直接让用户就医。
            6. 饮食细节交给营养师；你只给训练相关建议。若近期对话里用户提过伤痛/偏好，回答时要照顾到。
            """;

    private static final String NUTRITION_PROMPT = """
            你是一名运动营养师，服务于本健身管理系统的真实用户。

            【取数与知识】
            1. 涉及用户当天/某天吃了什么、热量蛋白：必须先调 getCurrentDietLog。
            2. 需要目标热量/宏量：调 getNutritionPlan；体重变化可调 getBodyWeightTrend。
            3. 若消息中附带【知识库片段】，饮食通识优先依据片段；片段不足就说「资料未覆盖」，不要用通用网文瞎编。
            4. 禁止开药、推荐补剂品牌或医疗断言；有疾病史引导就医。
            5. 回答简洁可执行，200 字以内；引用真实数字（热量、蛋白克数）。
            6. 训练动作细节交给教练；你专注吃与能量平衡。可参考近期对话里的训练目标（减脂/增肌）。
            """;

    private static final String DATA_PROMPT = """
            你是健身数据助手，只负责根据工具返回的数字做客观陈述。

            【铁律】
            1. 必须先调用工具再回答；禁止推测。
            2. 只陈述事实与数字，少给训练处方或饮食处方（那是教练/营养师的事）。
            3. 「练了啥 / 几次 / 最好成绩 / 体重多少」类问题：调 getRecentWorkouts、getPersonalBest、
               getStrengthTrend、getMuscleVolume、getBodyWeightTrend、getCurrentDietLog 中合适的工具。
            4. 150 字以内；列出关键数字即可；不要反问用户想问什么。
            """;

    private static final Set<String> COACH_TOOLS = Set.of(
            "getStrengthTrend", "getMuscleVolume", "getRecentWorkouts",
            "getPersonalBest", "getBodyWeightTrend");

    private static final Set<String> NUTRITION_TOOLS = Set.of(
            "getCurrentDietLog", "getNutritionPlan", "getBodyWeightTrend");

    private static final Set<String> DATA_TOOLS = Set.of(
            "getRecentWorkouts", "getStrengthTrend", "getMuscleVolume",
            "getPersonalBest", "getBodyWeightTrend", "getCurrentDietLog");

    private final GymAiTemplate gymAiTemplate;
    private final IntentRouter intentRouter;
    private final AgentChatMemory chatMemory;
    private final HybridSearchService hybridSearchService;
    private final List<ToolCallback> allTools;

    @Value("${gym.ai.recall-top-k:6}")
    private int recallTopK;

    public MultiAgentOrchestrator(GymAiTemplate gymAiTemplate,
                                  IntentRouter intentRouter,
                                  AgentChatMemory chatMemory,
                                  HybridSearchService hybridSearchService,
                                  TrainingTools trainingTools) {
        this.gymAiTemplate = gymAiTemplate;
        this.intentRouter = intentRouter;
        this.chatMemory = chatMemory;
        this.hybridSearchService = hybridSearchService;
        this.allTools = Arrays.asList(MethodToolCallbackProvider.builder()
                .toolObjects(trainingTools)
                .build()
                .getToolCallbacks());
    }

    public AgentReply ask(String question) {
        long userId = UserContext.requireUserId();
        AgentRole role = intentRouter.route(question);
        String system = systemPrompt(role);
        String userPayload = buildUserPayload(userId, role, question);
        List<ToolCallback> tools = scopedTools(role, userId);

        log.info("[多Agent] userId={} role={} tools={}", userId, role.code(), tools.size());

        String answer = gymAiTemplate.chat(userPayload, system, tools);
        chatMemory.append(userId, role, stripEnrichment(question), answer);
        return new AgentReply(role, answer);
    }

    public AgentRole peekRoute(String question) {
        return intentRouter.route(question);
    }

    public int toolCount() {
        return allTools.size();
    }

    private String buildUserPayload(long userId, AgentRole role, String question) {
        StringBuilder sb = new StringBuilder();
        String memory = chatMemory.formatContext(userId);
        if (!memory.isBlank()) {
            sb.append(memory).append("\n\n");
        }
        if (role == AgentRole.NUTRITION) {
            String rag = recallNutrition(question);
            if (!rag.isBlank()) {
                sb.append(rag).append("\n\n");
            }
        }
        String q = role == AgentRole.COACH ? FitnessAgentService.enrich(question) : question.trim();
        sb.append("【本轮问题】\n").append(q);
        return sb.toString();
    }

    private String recallNutrition(String question) {
        try {
            List<KnowledgeDoc> docs = hybridSearchService.search(question, recallTopK);
            if (docs == null || docs.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("【知识库片段】\n");
            int i = 1;
            for (KnowledgeDoc doc : docs) {
                String text = doc.getContent() != null ? doc.getContent() : "";
                if (text.length() > 400) {
                    text = text.substring(0, 400) + "…";
                }
                sb.append(i++).append(". ").append(text).append('\n');
            }
            sb.append("请优先依据以上片段与工具数据回答；片段不足请明说资料未覆盖。");
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("[多Agent] 营养 RAG 召回失败，仅用工具: {}", e.getMessage());
            return "";
        }
    }

    private List<ToolCallback> scopedTools(AgentRole role, long userId) {
        Set<String> allow = switch (role) {
            case COACH -> COACH_TOOLS;
            case NUTRITION -> NUTRITION_TOOLS;
            case DATA -> DATA_TOOLS;
        };
        return allTools.stream()
                .filter(cb -> {
                    ToolDefinition def = cb.getToolDefinition();
                    return def != null && allow.contains(def.name());
                })
                .map(cb -> UserScopedToolCallback.wrap(cb, userId))
                .collect(Collectors.toList());
    }

    private static String systemPrompt(AgentRole role) {
        return switch (role) {
            case COACH -> COACH_PROMPT;
            case NUTRITION -> NUTRITION_PROMPT;
            case DATA -> DATA_PROMPT;
        };
    }

    /** 写入记忆时去掉 enrich 追加的系统约束，避免污染上下文 */
    private static String stripEnrichment(String question) {
        if (question == null) {
            return "";
        }
        int idx = question.indexOf("\n\n（系统约束：");
        if (idx > 0) {
            return question.substring(0, idx).trim();
        }
        return question.trim();
    }
}
