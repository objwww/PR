package com.objwww.pr.control.alert.application.agent;

import java.util.Map;

/**
 * TypeSafe Jev 结构化决策口（JE-01）。契约对齐 jev-lab（experiments/jev-lab/lab.py
 * Provider.call，2026-09-20 核对）：
 * <ul>
 *   <li>请求 {@code {model, state, questions}}——questions 每键一题（键 = 候选
 *       id；官方说明 question map 的 key 不参与模型推理，故 id 同时写入题面）；</li>
 *   <li>响应 {@code {model, answers, usage}}——answers 必须完整覆盖全部题键、
 *       概率 ∈ [0,1]；usage 键为 input_tokens/output_tokens（非 chat 的
 *       prompt/completion 命名）；返回 model 与钉版不一致 = 契约失败。</li>
 * </ul>
 * Jev 只回答有类型判断（Noul 是/否概率），不生成摘要正文；宿主代码负责保留、
 * 预算与身份边界。实现方须短超时 + 封闭错误码，任何失败由调用方有界回退。
 */
public interface JevClient {

    /** 一次结构化决策调用；失败抛 {@link JevClientException}（封闭原因码） */
    JevAnswer score(JevRequest request) throws JevClientException;

    /** 请求：questions 键 = 候选 id，值 = 题面文本（noul 信封由实现方钉版） */
    record JevRequest(String model, Map<String, Object> state,
            Map<String, String> questions) {
    }

    /** 答案：probabilities 键与请求 questions 键严格同集 */
    record JevAnswer(Map<String, Double> probabilities, String model, JevUsage usage) {
    }

    /** usage（Jev 键名 input/output_tokens；缺失不猜零 → usageMissing） */
    record JevUsage(Long inputTokens, Long outputTokens, Long totalTokens,
            boolean usageMissing) {
    }

    /** 封闭错误码客户端异常（不携带供应商正文，可安全入日志） */
    final class JevClientException extends RuntimeException {

        public static final String TIMEOUT = "JEV_TIMEOUT";
        public static final String NETWORK = "JEV_NETWORK";
        public static final String HTTP_4XX = "JEV_HTTP_4XX";
        public static final String HTTP_429 = "JEV_HTTP_429";
        public static final String HTTP_5XX = "JEV_HTTP_5XX";
        public static final String CONTRACT = "JEV_CONTRACT";

        private final String code;
        /** true = 未发出任何网络字节（调用方可保守释放预算预留） */
        private final boolean zeroSend;

        public JevClientException(String code, String message, boolean zeroSend) {
            super(message);
            this.code = code;
            this.zeroSend = zeroSend;
        }

        public JevClientException(String code, String message, boolean zeroSend,
                Throwable cause) {
            super(message, cause);
            this.code = code;
            this.zeroSend = zeroSend;
        }

        public String code() {
            return code;
        }

        public boolean zeroSend() {
            return zeroSend;
        }
    }
}
