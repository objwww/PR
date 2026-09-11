package com.objwww.pr.control.alert.domain.agent;

import java.util.Objects;

/**
 * RCA 模型调用失败（R7a-1）：封闭原因码（账本 error_code 同源，脱敏非供应商原文）。
 * LEDGER_WRITE_FAILED 语义 = 账本不可写/终态写失败——发送前拒绝（零触网）或终态
 * 不确定（保守占预算），两者调用方都不得把结果当成功。
 */
public class RcaModelCallException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;
    /** true = 确证未发出（账本 open 失败/deadline 已过未发送）→ 预算可 release 退款 */
    private final boolean zeroNetwork;

    public RcaModelCallException(String errorCode, String message, boolean retryable) {
        this(errorCode, message, retryable, false, null);
    }

    public RcaModelCallException(String errorCode, String message, boolean retryable,
            Throwable cause) {
        this(errorCode, message, retryable, false, cause);
    }

    public RcaModelCallException(String errorCode, String message, boolean retryable,
            boolean zeroNetwork, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.retryable = retryable;
        this.zeroNetwork = zeroNetwork;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean zeroNetwork() {
        return zeroNetwork;
    }
}
