package com.objwww.pr.control.alert.domain.tool;

/**
 * 模型可见族工具异常（AM4 M4-17）：message 必须是脱敏固定文案——实现方禁止把
 * 底层异常文本（堆栈/凭据/内部地址）拼进 message。
 */
public class ToolModelVisibleException extends RuntimeException {

    private final ToolModelVisibleReason reason;

    public ToolModelVisibleException(ToolModelVisibleReason reason, String sanitizedMessage) {
        super(sanitizedMessage);
        this.reason = reason;
    }

    public ToolModelVisibleReason reason() {
        return reason;
    }
}
