package com.gym.ai.task;

/**
 * 任务状态机：QUEUED → RUNNING → SUCCESS / FAILED
 *
 * <p>刻意不用「已取消」作为终态之一参与流转——本项目没有主动取消入口，
 * 加一个用不到的状态只会让分支判断变复杂。
 */
public enum AiTaskStatus {

    QUEUED(0, "排队中"),
    RUNNING(1, "生成中"),
    SUCCESS(2, "已完成"),
    FAILED(3, "失败");

    private final int code;
    private final String label;

    AiTaskStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static AiTaskStatus of(Integer code) {
        for (AiTaskStatus status : values()) {
            if (status.code == (code == null ? -1 : code)) {
                return status;
            }
        }
        return QUEUED;
    }
}
