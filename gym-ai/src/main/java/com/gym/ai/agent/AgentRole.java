package com.gym.ai.agent;

/**
 * 多智能体角色：共用同一套业务工具 / RAG，仅人设与工具子集不同。
 */
public enum AgentRole {

    /** 训练计划、平台期、下次举多少 */
    COACH("教练", "coach"),

    /** 饮食热量、营养建议；可挂知识库召回 */
    NUTRITION("营养师", "nutrition"),

    /** 纯数据查询，少发挥 */
    DATA("数据助手", "data");

    private final String displayName;
    private final String code;

    AgentRole(String displayName, String code) {
        this.displayName = displayName;
        this.code = code;
    }

    public String displayName() {
        return displayName;
    }

    public String code() {
        return code;
    }
}
