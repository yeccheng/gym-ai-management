package com.gym.ai.rag;

import co.elastic.clients.elasticsearch._types.mapping.DenseVectorProperty;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch._types.mapping.IntegerNumberProperty;
import co.elastic.clients.elasticsearch._types.mapping.KeywordProperty;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TextProperty;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 索引初始化。
 *
 * <p>两个必须做对的地方：
 * 1. <b>中文分词用 IK</b>。standard 分析器会把「卧推」拆成「卧」+「推」，
 *    用户搜「练胸的动作」永远召回不到。IK 的 ik_max_word 切得细，召回高。
 * 2. <b>dense_vector 的 dims 必须与 Embedding 模型一致</b>。
 *    bge-m3 是 1024 维。维度不匹配时 ES 不报错，但 kNN 查询直接失败或返回空——
 *    这种静默失败排查起来极其耗时。换模型就必须重建索引。
 */
@Slf4j
@Component
public class KnowledgeIndexInitializer implements CommandLineRunner {

    // 注入 ElasticsearchClient 而不是 ElasticsearchIndicesClient。
    // Spring Boot 的自动装配只提供 ElasticsearchClient 这一个 bean，
    // indices() 是它的方法。直接注入 IndicesClient 在 ES 不在场时会因为
    // 找不到 bean 直接启动失败——那是「降级」逻辑根本没机会执行的失败。
    private final ElasticsearchClient client;

    @Value("${spring.ai.vectorstore.elasticsearch.index-name:gym_knowledge}")
    private String indexName;

    @Value("${spring.ai.vectorstore.elasticsearch.dimensions:1024}")
    private int dimensions;

    public KnowledgeIndexInitializer(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public void run(String... args) {
        // ES 不在场只降级 RAG，不能拖死整个应用——CommandLineRunner 抛异常会中断启动，
        // 而智能体对话、周报复盘、训练记录全都不依赖 ES。
        // 缺 IK 插件同理：索引建不出来，但主链路照常。
        try {
            createIndexIfAbsent();
        } catch (Exception e) {
            log.warn("知识库索引初始化失败，RAG 问答不可用（其余功能不受影响）：{}", e.getMessage());
            log.warn("启动 ES 并装好 IK 插件后重启即自动重建。docker compose up elasticsearch");
        }
    }

    private void createIndexIfAbsent() throws Exception {
        boolean exists = client.indices().exists(b -> b.index(indexName)).value();
        if (exists) {
            log.info("知识库索引 {} 已存在，跳过创建", indexName);
            return;
        }

        TypeMapping mapping = new TypeMapping.Builder()
                .properties("content", new Property.Builder()
                        .text(new TextProperty.Builder()
                                .analyzer("ik_max_word")
                                .searchAnalyzer("ik_smart")
                                .build())
                        .build())
                .properties("title", new Property.Builder()
                        .text(new TextProperty.Builder().analyzer("ik_max_word").build())
                        .build())
                .properties("alias", new Property.Builder()
                        .text(new TextProperty.Builder().analyzer("ik_max_word").build())
                        .build())
                .properties("docName", new Property.Builder()
                        .keyword(new KeywordProperty.Builder().build())
                        .build())
                .properties("docMd5", new Property.Builder()
                        .keyword(new KeywordProperty.Builder().build())
                        .build())
                .properties("sectionNo", new Property.Builder()
                        .keyword(new KeywordProperty.Builder().build())
                        .build())
                .properties("docType", new Property.Builder()
                        .integer(new IntegerNumberProperty.Builder().build())
                        .build())
                // index = true 才会建 HNSW 图，否则 kNN 查询不可用
                .properties("embedding", new Property.Builder()
                        .denseVector(new DenseVectorProperty.Builder()
                                .dims(dimensions)
                                .index(true)
                                .similarity(DenseVectorSimilarity.Cosine)
                                .build())
                        .build())
                .build();

        CreateIndexResponse response = client.indices().create(b -> b.index(indexName).mappings(mapping));
        log.info("创建知识库索引 {} 完成，acknowledged={}", indexName, response.acknowledged());
    }
}
