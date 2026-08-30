package com.objwww.pr.control.domain.ai;

/** 单次模型调用超硬超时 → Step FAILED（EX-06，安全步骤不降级） */
public final class ModelTimeoutException extends RuntimeException {

    public ModelTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
