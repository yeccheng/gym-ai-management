package com.gym.ai.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * 知识库索引读写。
 *
 * <p>刻意用逐个 index 而不是 bulk：毕设数据量在千级以内，
 * 逐个写能拿到每条文档的独立异常，bulk 一旦失败只能看到第几条出错。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIndexService {

    private final ElasticsearchClient esClient;

    @Value("${spring.ai.vectorstore.elasticsearch.index-name:gym_knowledge}")
    private String indexName;

    public int index(List<KnowledgeDoc> docs) {
        int written = 0;
        for (KnowledgeDoc doc : docs) {
            try {
                esClient.index(i -> i.index(indexName).id(doc.getId()).document(doc));
                written++;
            } catch (Exception e) {
                // 单条失败不阻断整批：一条坏文档不该让整个知识库建不起来
                log.error("知识库入库失败 id={} title={}", doc.getId(), doc.getTitle(), e);
            }
        }
        return written;
    }

    public long count() throws IOException {
        return esClient.count(c -> c.index(indexName)).count();
    }

    /** 清空重建。换 Embedding 模型导致维度变化时，必须先删再建 */
    public long deleteAll() throws IOException {
        return esClient.deleteByQuery(d -> d.index(indexName)
                .query(q -> q.matchAll(m -> m))).deleted();
    }
}
