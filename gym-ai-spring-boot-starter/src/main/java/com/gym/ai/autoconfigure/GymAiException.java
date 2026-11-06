package com.gym.ai.autoconfigure;

public class GymAiException extends RuntimeException {

    private final AiErrorType type;

    public GymAiException(AiErrorType type, String message) {
        super(message);
        this.type = type;
    }

    public GymAiException(AiErrorType type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    public AiErrorType getType() {
        return type;
    }

    public enum AiErrorType {
        BUDGET_EXCEEDED,
        CONCURRENCY_FULL,
        MODEL_UNAVAILABLE,
        OUTPUT_INVALID
    }
}
