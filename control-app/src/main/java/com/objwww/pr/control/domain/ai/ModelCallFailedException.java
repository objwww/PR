package com.objwww.pr.control.domain.ai;

/**
 * 模型调用终态失败（§4.2/附录 A）：终态族故障的载体。
 *
 * <p>携带稳定 errorCode（不泄露供应商原文），用于 Step FAILED 原因记录。
 */
public class ModelCallFailedException extends RuntimeException {
    private final String errorCode;
    private final boolean stepRetryable;

    public ModelCallFailedException(String errorCode, String message, boolean stepRetryable) {
        super(message);
        this.errorCode = errorCode;
        this.stepRetryable = stepRetryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean stepRetryable() {
        return stepRetryable;
    }
}
