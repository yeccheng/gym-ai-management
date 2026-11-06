package com.gym.ai.rag;

import lombok.Data;

import java.util.List;

/**
 * 知识库文档（ES 索引中的一条）。
 *
 * <p>字段设计与 MySQL 的 rag_doc_chunk 一一对应，但 ES 侧额外存 embedding——
 * 因为向量必须和原文档在同一个分片里，kNN 才能在一次查询内完成。
 */
@Data
public class KnowledgeDoc {

    /** chunk 主键，与 rag_doc_chunk.id 一致，便于双向追溯 */
    private String id;

    private String docName;

    /** MD5，入库判重依据 */
    private String docMd5;

    /** 1 动作库 2 训练原则 3 营养 */
    private Integer docType;

    /** 章节号，写入 metadata 供检索溯源 */
    private String sectionNo;

    private String title;

    private String content;

    /** 别名，中文检索的召回增强字段 */
    private String alias;

    /** 1024 维，必须与 bge-m3 严格一致 */
    private List<Float> embedding;
}
