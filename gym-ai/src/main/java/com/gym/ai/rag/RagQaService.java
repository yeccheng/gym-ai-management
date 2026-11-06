package com.gym.ai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gym.ai.autoconfigure.GymAiTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RAG 问答，四层防幻觉。
 *
 * <p>核心立场：<b>不信任模型输出，在后端做校验兜底。</b>
 *
 * <p>四层从外到内：
 * L1 <b>Prompt 约束</b>——只依据给定片段作答，资料不足必须明说
 * L2 <b>format=json</b>——强制结构化输出，让「引用编号」可被程序解析
 * L3 <b>后端校验</b>——模型给的引用编号必须在召回集内，否则整体拒答
 * L4 <b>召回为空不调模型</b>——知识库没覆盖就直接拒答，不让通用知识冒充领域知识
 *
 * <p>L3 和 L4 是最容易被忽略、也最能体现工程意识的两层：
 * 前两层只是「请求模型配合」，后两层是「不配合也有兜底」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagQaService {

    private static final String JSON_SYSTEM =
            "你只能输出 JSON，格式为 {\"answer\":\"...\",\"citations\":[\"片段编号\"]}。"
                    + "answer 中不得出现 JSON 以外的任何内容。";

    private final HybridSearchService hybridSearchService;
    private final GymAiTemplate gymAiTemplate;
    private final ObjectMapper objectMapper;

    @Value("${gym.ai.recall-empty-refuse:true}")
    private boolean recallEmptyRefuse;

    @Value("${gym.ai.recall-top-k:6}")
    private int recallTopK;

    public RagAnswer ask(String question) throws IOException {
        // ---------- L4：召回为空直接不调模型 ----------
        List<KnowledgeDoc> hits = hybridSearchService.search(question, recallTopK);
        List<String> recalled = hits.stream()
                .map(KnowledgeDoc::getSectionNo)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();

        if (hits.isEmpty()) {
            log.info("召回为空，跳过模型调用 question={}", question);
            if (recallEmptyRefuse) {
                return RagAnswer.refused("知识库暂未覆盖该问题，已跳过模型调用");
            }
            return RagAnswer.refused("未召回任何资料");
        }

        // ---------- L1 + L2：Prompt 约束 + 结构化输出 ----------
        String raw = gymAiTemplate.chat(buildPrompt(question, hits), JSON_SYSTEM);

        // ---------- L3：后端校验引用编号 ----------
        RagAnswer parsed;
        try {
            parsed = parse(raw);
        } catch (Exception e) {
            log.warn("模型输出无法解析为 JSON，已丢弃。raw={}", raw);
            return RagAnswer.refused("模型输出格式不符，已丢弃");
        }
        if (parsed == null || parsed.getAnswer() == null || parsed.getAnswer().isBlank()) {
            return RagAnswer.refused("模型输出为空");
        }
        if (!citationsValid(parsed.getCitations(), recalled)) {
            log.warn("模型引用了召回集之外的片段，已拒答。citations={} recalled={}",
                    parsed.getCitations(), recalled);
            return RagAnswer.refused("模型引用的片段不在召回集内，已拒绝输出");
        }

        parsed.setRecalledSections(recalled);
        parsed.setRefused(false);
        return parsed;
    }

    /**
     * 引用合法性校验。
     * 允许「不引用」（ citations 为空）——保守回答比错误引用好；
     * 但一旦引用，必须每一个编号都能在召回集里找到。
     */
    boolean citationsValid(List<String> citations, List<String> recalled) {
        if (citations == null || citations.isEmpty()) {
            return true;
        }
        Set<String> recalledSet = new LinkedHashSet<>(recalled);
        return recalledSet.containsAll(citations);
    }

    private RagAnswer parse(String raw) throws IOException {
        String json = extractJson(raw);
        JsonNode node = objectMapper.readTree(json);
        RagAnswer answer = new RagAnswer();
        answer.setAnswer(node.path("answer").asText(""));
        List<String> citations = new ArrayList<>();
        node.path("citations").forEach(c -> citations.add(c.asText()));
        answer.setCitations(citations);
        return answer;
    }

    /** 模型偶尔会在 JSON 外面包一句解释，这里把第一个 { 到最后一个 } 抠出来 */
    private String extractJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "{}";
        }
        return raw.substring(start, end + 1);
    }

    private String buildPrompt(String question, List<KnowledgeDoc> hits) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("下面是知识库中与问题相关的资料片段。回答必须且只能依据这些片段。\n\n");
        for (KnowledgeDoc doc : hits) {
            sb.append("=== 片段编号 ").append(doc.getSectionNo())
                    .append("（来源：").append(doc.getDocName() == null ? "知识库" : doc.getDocName())
                    .append(" / ").append(doc.getTitle() == null ? "" : doc.getTitle())
                    .append("）===\n")
                    .append(doc.getContent()).append("\n\n");
        }
        sb.append("问题：").append(question).append("\n\n");
        sb.append("要求：\n");
        sb.append("1. 只依据上述片段作答，不得使用片段之外的任何知识\n");
        sb.append("2. 涉及具体数值（重量、次数、组数、温度、时间）时，只能原样引用片段中的数字，不得自行推算\n");
        sb.append("3. 片段不足以回答时，answer 写「资料未覆盖」，citations 留空\n");
        sb.append("4. citations 只能填上面出现过的片段编号，一个或多个\n");
        return sb.toString();
    }
}
