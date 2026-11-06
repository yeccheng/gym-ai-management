package com.gym.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF 融合算法的验证。
 * 这段逻辑不依赖 ES，可以纯单元测试——也正因如此，
 * 它是整个检索链路里唯一能被测试完全覆盖的部分。
 */
class HybridSearchServiceTest {

    private final HybridSearchService service = new HybridSearchService(null, null);

    private KnowledgeDoc doc(String id) {
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(id);
        doc.setSectionNo("动作-" + id);
        return doc;
    }

    @Test
    @DisplayName("两路都命中的文档应排最前")
    void bothRankedFirst() {
        KnowledgeDoc a = doc("A");
        KnowledgeDoc b = doc("B");
        KnowledgeDoc c = doc("C");

        // 第一路 A、B；第二路 C、A
        List<KnowledgeDoc> fused = service.rrfFuse(List.of(a, b), List.of(c, a), 3);

        // A 两路都进榜，得分必然最高
        assertEquals("A", fused.get(0).getId());
        // 单路排名：C 在第一路排第 1，B 在第二路排第 2
        // score(C) = 1/61，score(B) = 1/62，故 C 高于 B
        assertEquals("C", fused.get(1).getId());
        assertEquals("B", fused.get(2).getId());
    }

    @Test
    @DisplayName("topK 应截断，且不重复返回同一文档")
    void topKTruncatesAndDeduplicates() {
        List<KnowledgeDoc> first = List.of(doc("A"), doc("B"), doc("C"));
        List<KnowledgeDoc> second = List.of(doc("C"), doc("A"), doc("B"));

        List<KnowledgeDoc> fused = service.rrfFuse(first, second, 2);

        assertEquals(2, fused.size());
        assertEquals(2, fused.stream().map(KnowledgeDoc::getId).distinct().count());
    }

    @Test
    @DisplayName("一路为空时不应崩，等同于另一路的结果")
    void emptySideTolerated() {
        List<KnowledgeDoc> only = List.of(doc("A"), doc("B"));

        List<KnowledgeDoc> fused = service.rrfFuse(only, List.of(), 6);

        assertEquals(2, fused.size());
        assertEquals("A", fused.get(0).getId());
    }

    @Test
    @DisplayName("两路都为空时返回空列表")
    void bothEmpty() {
        assertTrue(service.rrfFuse(List.of(), List.of(), 6).isEmpty());
    }

    @Test
    @DisplayName("单文档只在第二路排第 5 时仍应被保留")
    void deepRankStillKept() {
        List<KnowledgeDoc> first = List.of(doc("A"), doc("B"), doc("C"), doc("D"));
        List<KnowledgeDoc> second = List.of(doc("E"), doc("F"), doc("G"), doc("H"), doc("I"));

        List<KnowledgeDoc> fused = service.rrfFuse(first, second, 20);

        // 9 个不同文档全部应被保留，无一丢失
        assertEquals(9, fused.size());
    }
}
