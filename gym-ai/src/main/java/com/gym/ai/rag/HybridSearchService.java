package com.gym.ai.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 混合检索：BM25 + kNN 两路召回，应用层 RRF 融合。
 *
 * <p>为什么必须两路：
 * 纯向量对**口语化提问**召回差——用户搜「夜班补贴」，文档里写的是「夜班津贴」，
 * 语义相近但字面不同；纯关键词对**同义表述**又无能为力。
 * 两路互补，用 RRF 融合后取 topK，召回率明显高于任何单一路。
 *
 * <p>为什么融合放在应用层而不是用 ES 的 retriever API：
 * 应用层实现让权重可调、可观测（能分别看到两路各自召回了什么），
 * 论文里也好画图。ES 8.13 的 retriever  API 也能做，但那是个黑盒。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridSearchService {

    /** RRF 常数，取 60 是原论文的推荐值：既不让头部结果碾压，也不过度平滑 */
    private static final int RRF_K = 60;

    private final ElasticsearchClient esClient;
    private final EmbeddingModel embeddingModel;

    @Value("${spring.ai.vectorstore.elasticsearch.index-name:gym_knowledge}")
    private String indexName;

    public List<KnowledgeDoc> search(String question, int topK) throws IOException {
        // 每路多取一些再融合，否则单路的漏召会被直接带进最终结果
        int candidate = Math.max(topK * 2, 10);
        List<KnowledgeDoc> lexical = bm25Search(question, candidate);
        List<KnowledgeDoc> vector = knnSearch(question, candidate);
        log.debug("混合检索 BM25 {} 条 / kNN {} 条", lexical.size(), vector.size());
        return rrfFuse(lexical, vector, topK);
    }

    /**
     * BM25 一路。title 权重高于 content——命中标题通常就是用户想找的那个动作。
     * alias 单独加权是为了让「练胸外侧」这类口语能命中「哑铃飞鸟」。
     */
    private List<KnowledgeDoc> bm25Search(String text, int size) throws IOException {
        SearchResponse<KnowledgeDoc> response = esClient.search(s -> s
                        .index(indexName)
                        .size(size)
                        .query(q -> q.multiMatch(m -> m
                                .query(text)
                                .fields("title^3", "alias^2", "content"))),
                KnowledgeDoc.class);
        return toSources(response);
    }

    /** kNN 一路。numCandidates 必须大于 k，是 HNSW 的搜索宽度 */
    private List<KnowledgeDoc> knnSearch(String text, int size) throws IOException {
        List<Float> vector = toFloatList(embeddingModel.embed(text));
        SearchResponse<KnowledgeDoc> response = esClient.search(s -> s
                        .index(indexName)
                        .size(size)
                        .knn(k -> k.field("embedding")
                                .queryVector(vector)
                                .k(size)
                                .numCandidates(Math.max(size * 10, 50))),
                KnowledgeDoc.class);
        return toSources(response);
    }

    /**
     * Reciprocal Rank Fusion。
     * score(d) = Σ 1 / (k + rank_i(d))
     * 只用排名不用原始分——BM25 的分值和余弦相似度不在一个量纲上，直接加权会把
     * 分值大的那一路完全盖过去。RRF 只用「排第几」，天然规避了量纲问题。
     */
    /** 包级可见以便单测直接验证融合算法，不需要起 ES */
    List<KnowledgeDoc> rrfFuse(List<KnowledgeDoc> first,
                               List<KnowledgeDoc> second,
                               int topK) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, KnowledgeDoc> docs = new LinkedHashMap<>();
        accumulate(first, scores, docs);
        accumulate(second, scores, docs);

        List<String> ids = new ArrayList<>(scores.keySet());
        ids.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));

        List<KnowledgeDoc> result = new ArrayList<>();
        for (String id : ids.subList(0, Math.min(topK, ids.size()))) {
            result.add(docs.get(id));
        }
        return result;
    }

    void accumulate(List<KnowledgeDoc> ranked,
                    Map<String, Double> scores,
                    Map<String, KnowledgeDoc> docs) {
        for (int rank = 1; rank <= ranked.size(); rank++) {
            KnowledgeDoc doc = ranked.get(rank - 1);
            if (doc == null || doc.getId() == null) {
                continue;
            }
            scores.merge(doc.getId(), 1.0 / (RRF_K + rank), Double::sum);
            docs.putIfAbsent(doc.getId(), doc);
        }
    }

    private List<KnowledgeDoc> toSources(SearchResponse<KnowledgeDoc> response) {
        return response.hits().hits().stream()
                .map(Hit::source)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> boxed = new ArrayList<>(vector.length);
        for (float value : vector) {
            boxed.add(value);
        }
        return boxed;
    }
}
