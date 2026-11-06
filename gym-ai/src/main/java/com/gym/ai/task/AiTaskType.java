package com.gym.ai.task;

/**
 * 任务类型。新增一种 AI 能力 = 新增一个枚举值 + 一个 Handler，
 * 执行器、SSE、重试、重启恢复全部复用，不用改。
 */
public enum AiTaskType {

    WEEKLY_REVIEW(1, "周报复盘"),
    PLATEAU_BREAK(2, "平台期破局"),
    PLAN_GENERATE(3, "训练计划生成"),
    KNOWLEDGE_QA(4, "知识库问答");

    private final int code;
    private final String label;

    AiTaskType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static AiTaskType of(Integer code) {
        for (AiTaskType type : values()) {
            if (type.code == (code == null ? -1 : code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知任务类型: " + code);
    }
}
