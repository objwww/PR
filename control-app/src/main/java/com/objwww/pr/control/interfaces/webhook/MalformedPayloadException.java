package com.objwww.pr.control.interfaces.webhook;

/**
 * webhook payload 畸形（非 JSON / 缺必需字段）→ 400（EX-08）。
 */
public class MalformedPayloadException extends RuntimeException {

    public MalformedPayloadException(String message) {
        super(message);
    }

    public MalformedPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
