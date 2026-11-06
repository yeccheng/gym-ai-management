package com.gym.ai.agent;

/**
 * 多智能体一次应答：正文 + 实际接诊角色（方便前端展示 / 答辩演示）。
 */
public record AgentReply(AgentRole role, String answer) {
}
