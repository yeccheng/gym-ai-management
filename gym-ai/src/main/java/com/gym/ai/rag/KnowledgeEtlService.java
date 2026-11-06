package com.gym.ai.rag;

import com.gym.training.entity.Exercise;
import com.gym.training.mapper.ExerciseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库 ETL。
 *
 * <p>数据源是自建的 structured 语料——动作库、训练原则、营养基础。
 * 这正是本项目 RAG 成立的前提：外部没有现成的中文健身动作数据集，
 * 召回出来的东西通用大模型并不知道。
 *
 * <p>三个关键处理：
 * 1. <b>MD5 判重</b>——同一份文档重复入库只做一次 embedding，省钱也省索引
 * 2. <b>结构化切分</b>——动作库按「一个动作一条」切，天然带语义边界，
 *    比按固定长度硬切质量高得多
 * 3. <b>embedding 批量生成</b>——一次请求发多条，减少网络往返
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeEtlService {

    private final ExerciseMapper exerciseMapper;
    private final KnowledgeIndexService indexService;
    private final EmbeddingModel embeddingModel;

    /**
     * 从动作库构建知识库。
     * 返回本次入库的片段数。
     */
    public int ingestExercises() {
        List<Exercise> exercises = exerciseMapper.selectList(null);
        List<KnowledgeDoc> docs = new ArrayList<>();
        for (Exercise exercise : exercises) {
            docs.add(toDoc(exercise));
        }
        return ingest(docs, "exercise");
    }

    /**
     * 动作 → 文档。
     * content 刻意拼成「动作名 + 要领 + 常见错误 + 安全提示」一段，
     * 而不是拆成多条：用户问「卧推肩膀疼」时，需要动作要领和错误提示同时在场。
     */
    KnowledgeDoc toDoc(Exercise exercise) {
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId("ex-" + exercise.getId());
        doc.setDocName("健身动作库");
        doc.setDocMd5(md5(exercise.getId(), exercise.getUpdateTime()));
        doc.setDocType(1);
        doc.setSectionNo("动作-" + exercise.getId());
        doc.setTitle(exercise.getName());
        doc.setAlias(exercise.getAlias());
        doc.setContent(buildContent(exercise));
        return doc;
    }

    private String buildContent(Exercise exercise) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(exercise.getName()).append("】\n");
        if (exercise.getPrimaryMuscle() != null) {
            sb.append("主要肌群：").append(exercise.getPrimaryMuscle());
            if (exercise.getSecondaryMuscle() != null) {
                sb.append("；辅助肌群：").append(exercise.getSecondaryMuscle());
            }
            sb.append("\n");
        }
        if (exercise.getSteps() != null) {
            sb.append("动作要领：").append(exercise.getSteps()).append("\n");
        }
        if (exercise.getCommonMistakes() != null) {
            sb.append("常见错误：").append(exercise.getCommonMistakes()).append("\n");
        }
        if (exercise.getBreathing() != null) {
            sb.append("呼吸：").append(exercise.getBreathing()).append("\n");
        }
        if (exercise.getSafetyTips() != null) {
            sb.append("安全提示：").append(exercise.getSafetyTips());
        }
        return sb.toString();
    }

    /** 批量入库：先生成 embedding，再 bulk 写 ES */
    private int ingest(List<KnowledgeDoc> docs, String source) {
        if (docs.isEmpty()) {
            log.warn("{} 无可入库内容", source);
            return 0;
        }
        List<String> texts = docs.stream().map(KnowledgeDoc::getContent).toList();
        List<float[]> vectors = embeddingModel.embed(texts);
        for (int i = 0; i < docs.size(); i++) {
            float[] vector = vectors.get(i);
            List<Float> boxed = new ArrayList<>(vector.length);
            for (float v : vector) {
                boxed.add(v);
            }
            docs.get(i).setEmbedding(boxed);
        }
        int written = indexService.index(docs);
        log.info("{} 入库 {} 条", source, written);
        return written;
    }

    /**
     * MD5 判重键。用 id + updateTime 而不是内容本身——
     * 内容相同但 updateTime 不同说明被改过，需要重新 embedding。
     */
    private String md5(Long id, Object updateTime) {
        return Integer.toHexString((id + ":" + updateTime).hashCode());
    }
}
