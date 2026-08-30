package com.objwww.pr.publisher.domain.port;

/**
 * payload 不可读（CAS 缺文件 / JSON 损坏）。fail-closed：按 schema 校验失败处理（E5）。
 */
public class PayloadUnavailableException extends RuntimeException {

    public PayloadUnavailableException(String message) {
        super(message);
    }

    public PayloadUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
