package com.gym.ai.rag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 问答结果。
 *
 * <p>citations 存的是片段编号（sectionNo），不是原文——
 * 后端校验时只需比对编号是否在召回集内，O(n) 即可完成，不需要再做一次相似度计算。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagAnswer {

    /** 模型给出的回答 */
    private String answer;

    /** 模型引用的片段编号，如 动作-12 */
    private List<String> citations = new ArrayList<>();

    /** 是否为拒答。拒答不是失败，是系统按预期拒绝输出 */
    private boolean refused;

    private String refuseReason;

    /** 本次实际召回的片段编号，用于后端校验与前端溯源展示 */
    private List<String> recalledSections = new ArrayList<>();

    public static RagAnswer refused(String reason) {
        RagAnswer answer = new RagAnswer();
        answer.setRefused(true);
        answer.setRefuseReason(reason);
        answer.setAnswer("");
        return answer;
    }
}
