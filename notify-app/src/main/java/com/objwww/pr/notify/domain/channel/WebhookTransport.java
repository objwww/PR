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

        private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
                new com.fasterxml.jackson.databind.ObjectMapper();

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

        /**
         * 状态码 + 回执 body → SendResult（429 读 Retry-After，缺省 60s）。
         *
         * <p>F20（EX-C2a）：HTTP 200 不等于业务送达——钉钉/企微以 200+errcode 回执业务
         * 结局，errcode≠0 是"平台已受理并明确拒绝"（签名错/关键词不符/限流），不得标
         * SENT。分类裁定：errcode=0 → Delivered；errcode≠0 → Retryable（进持久重试，
         * 预算/期限双闸封顶，last_error 记 business_code+errmsg 片段）；2xx 但 body
         * 非 errcode JSON（回执形态不可证）→ OutcomeUnknown（终态留档人工复核，不自动
         * 重发）。回执语义：机器人接收≠值班员阅读。
         */
        public static NotificationChannel.SendResult fromStatus(int status,
                                                                String retryAfterHeader,
                                                                String body) {
            if (status >= 200 && status < 300) {
                return classifyAck(body);
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

        /** 2xx 回执解析：errcode 数字字段是唯一送达事实（钉钉/企微共同契约） */
        private static NotificationChannel.SendResult classifyAck(String body) {
            if (body == null || body.isBlank()) {
                return new NotificationChannel.SendResult.OutcomeUnknown("empty_ack_body");
            }
            try {
                com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(body);
                if (node.isObject() && node.has("errcode") && node.get("errcode").isNumber()) {
                    int code = node.get("errcode").asInt();
                    if (code == 0) {
                        return new NotificationChannel.SendResult.Delivered();
                    }
                    String errmsg = node.has("errmsg") && node.get("errmsg").isTextual()
                            ? node.get("errmsg").asText() : "";
                    return new NotificationChannel.SendResult.Retryable(
                            "business_code_" + code + ": " + snippet(errmsg));
                }
                return new NotificationChannel.SendResult.OutcomeUnknown(
                        "unparseable_ack_body");
            } catch (Exception e) {
                return new NotificationChannel.SendResult.OutcomeUnknown(
                        "unparseable_ack_body");
            }
        }

        /** errmsg 片段截断（审计卫生：last_error 只留诊断必需） */
        static String snippet(String text) {
            String safe = text == null ? "" : text.strip();
            return safe.length() <= 120 ? safe : safe.substring(0, 120) + "…";
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
