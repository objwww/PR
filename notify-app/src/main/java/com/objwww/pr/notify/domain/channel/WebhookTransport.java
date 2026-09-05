package com.objwww.pr.notify.domain.channel;

/**
 * webhook 传输窄端口：POST JSON，结局按状态码/异常形态分类——
 * 分类是纯函数（{@link Classifier}），传输实现只做 I/O（可假件替换）。
 */
public interface WebhookTransport {

    Response post(String url, String jsonBody);

    record Response(int status, String retryAfterHeader, String body) {
    }

    /** 传输失败（唯一受检形态）：分类仍走 Classifier.fromTransport——domain 持有该类型 */
    final class TransportFailure extends RuntimeException {

        public TransportFailure(Throwable cause) {
            super(cause);
        }
    }

    /** 传输异常 → SendResult 分类（纯函数，离线可测） */
    final class Classifier {

        private Classifier() {
        }

        /**
         * 连接建立失败/连接超时 = 请求未发出 → Retryable（可安全重试）；
         * 请求/响应读写超时与其它 IO 异常 = 请求可能已到达 → OutcomeUnknown（不自动重发）。
         */
        public static NotificationChannel.SendResult fromTransport(Throwable error) {
            if (error instanceof java.net.http.HttpConnectTimeoutException
                    || error instanceof java.net.ConnectException) {
                return new NotificationChannel.SendResult.Retryable(
                        "connect_failed: " + error.getMessage());
            }
            return new NotificationChannel.SendResult.OutcomeUnknown(
                    "outcome_unknown: " + error.getMessage());
        }

        /** 状态码 → SendResult（429 读 Retry-After，缺省 60s） */
        public static NotificationChannel.SendResult fromStatus(int status,
                                                                String retryAfterHeader) {
            if (status >= 200 && status < 300) {
                return new NotificationChannel.SendResult.Delivered();
            }
            if (status == 429) {
                return new NotificationChannel.SendResult.RateLimited(
                        parseRetryAfter(retryAfterHeader));
            }
            if (status >= 500) {
                return new NotificationChannel.SendResult.Retryable("http_" + status);
            }
            return new NotificationChannel.SendResult.Permanent("http_" + status);
        }

        static long parseRetryAfter(String header) {
            if (header == null || header.isBlank()) {
                return 60L;
            }
            try {
                long seconds = Long.parseLong(header.trim());
                return seconds < 0 ? 60L : Math.min(seconds, 3600L);
            } catch (NumberFormatException e) {
                return 60L; // HTTP-date 形态不解析，按缺省退避（MVP 渠道均为 delta-seconds）
            }
        }
    }
}
