package com.gym.web.controller;

import com.gym.ai.rag.HybridSearchService;
import com.gym.ai.rag.KnowledgeDoc;
import com.gym.ai.rag.KnowledgeEtlService;
import com.gym.ai.rag.KnowledgeIndexService;
import com.gym.ai.rag.RagAnswer;
import com.gym.ai.rag.RagQaService;
import com.gym.common.result.R;
import com.gym.common.result.ResultCode;
import com.gym.web.security.RequireRole;
import com.gym.web.security.Role;
import com.gym.web.service.LocalKnowledgeFallback;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@Tag(name = "知识库与 RAG 问答")
@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final RagQaService ragQaService;
    private final KnowledgeEtlService etlService;
    private final KnowledgeIndexService indexService;
    private final HybridSearchService hybridSearchService;
    private final LocalKnowledgeFallback localKnowledgeFallback;

    @Operation(summary = "RAG 问答", description = "ES 可用走混合检索；不可用或召回空则本地动作要领兜底")
    @PostMapping("/qa")
    public R<RagAnswer> qa(@RequestBody Map<String, String> body) {
        String question = body.get("question");
        if (question == null || question.isBlank()) {
            return R.fail(ResultCode.PARAM_ERROR, "问题不能为空");
        }
        try {
            RagAnswer ans = ragQaService.ask(question);
            if (ans != null && ans.isRefused()) {
                RagAnswer local = localKnowledgeFallback.answer(question);
                if (local != null && !local.isRefused()) {
                    return R.ok(local);
                }
            }
            return R.ok(ans);
        } catch (Exception e) {
            log.warn("RAG 不可用，改用本地要领兜底: {}", e.getMessage());
            return R.ok(localKnowledgeFallback.answer(question));
        }
    }

    @Operation(summary = "重建知识库", description = "从动作库重新生成 embedding 并写入 ES")
    @RequireRole(Role.ADMIN)
    @PostMapping("/ingest")
    public R<Map<String, Object>> ingest() throws IOException {
        long before = indexService.count();
        int written = etlService.ingestExercises();
        return R.ok(Map.of("before", before, "written", written, "after", indexService.count()));
    }

    @Operation(summary = "清空知识库", description = "换 Embedding 模型导致维度变化时必须先清空重建")
    @RequireRole(Role.ADMIN)
    @PostMapping("/clear")
    public R<Map<String, Object>> clear() throws IOException {
        long deleted = indexService.deleteAll();
        return R.ok(Map.of("deleted", deleted));
    }

    @Operation(summary = "混合检索调试", description = "直接看两路 RRF 融合后召回了哪些片段，用于排查召回问题")
    @GetMapping("/search")
    public R<List<KnowledgeDoc>> search(@RequestParam String q,
                                        @RequestParam(defaultValue = "6") int topK) throws IOException {
        return R.ok(hybridSearchService.search(q, topK));
    }
}
