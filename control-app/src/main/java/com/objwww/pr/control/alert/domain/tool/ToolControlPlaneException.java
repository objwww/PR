package com.objwww.pr.control.alert.domain.tool;

/**
 * 控制面终止族工具异常（AM4 M4-17）：调用方据此直接终止/升级，禁止把该异常
 * 文案透给模型进入重试循环。
 */
public class ToolControlPlaneException extends RuntimeException {

    private final ToolControlReason reason;

    public ToolControlPlaneException(ToolControlReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ToolControlReason reason() {
        return reason;
    }
}
