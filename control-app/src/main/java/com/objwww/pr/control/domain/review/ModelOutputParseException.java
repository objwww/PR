package com.objwww.pr.control.domain.review;

/**
 * 模型输出无法解析为结构化 findings JSON 时的安全失败信号（T08/EX-06 同源语义：
 * 模型乱输出不产出半个结果，向上抛由 T2 把 Step 记 FAILED，不静默吞掉）。
 */
public class ModelOutputParseException extends RuntimeException {

    public ModelOutputParseException(String message) {
        super(message);
    }

    public ModelOutputParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
