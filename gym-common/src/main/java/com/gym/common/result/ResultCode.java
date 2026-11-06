package com.gym.common.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一响应码。
 * 6xx 段为 AI 链路专用，用于把模型侧的可预期失败与系统异常区分开——
 * 召回为空不是错误，而是「知识库未覆盖」，前端据此提示而非报错。
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    SUCCESS(200, "操作成功"),

    BIZ_ERROR(400, "业务处理失败"),
    PARAM_ERROR(400, "参数不合法"),
    UNAUTHORIZED(401, "未认证或登录已失效"),
    FORBIDDEN(403, "无访问权限"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源冲突"),
    TOO_MANY_REQUEST(429, "请求过于频繁，请稍后再试"),
    SYSTEM_ERROR(500, "系统繁忙，请稍后再试"),

    AI_TASK_TIMEOUT(601, "AI 任务处理超时，请稍后查看结果"),
    AI_RECALL_EMPTY(602, "知识库暂未覆盖该问题，已跳过模型调用"),
    AI_OUTPUT_INVALID(603, "模型输出未通过校验，已丢弃"),
    AI_MODEL_UNAVAILABLE(604, "模型服务暂不可用");

    private final int code;
    private final String message;
}
