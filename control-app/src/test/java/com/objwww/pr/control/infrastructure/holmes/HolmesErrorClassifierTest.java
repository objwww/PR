package com.objwww.pr.control.infrastructure.holmes;

import org.junit.jupiter.api.Test;

import static com.objwww.pr.control.infrastructure.holmes.HolmesErrorClassifier.Kind;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-A08：Holmes 错误分类（§6.5 评审修正：429=可重试；Holmes 无 Retry-After，退避我方决策）。
 */
class HolmesErrorClassifierTest {

    @Test
    void rateLimitedIsRetryable() {
        assertThat(HolmesErrorClassifier.classify(429).retryable()).isTrue();
        assertThat(HolmesErrorClassifier.classify(429).errorClass()).isEqualTo("HTTP_429_RATE_LIMITED");
    }

    @Test
    void authDeniedIsTerminal() {
        assertThat(HolmesErrorClassifier.classify(401).retryable()).isFalse();
        assertThat(HolmesErrorClassifier.classify(403).kind()).isEqualTo(Kind.TERMINAL);
        assertThat(HolmesErrorClassifier.classify(401).errorClass()).isEqualTo("HTTP_AUTH_DENIED");
    }

    @Test
    void serverErrorsAreRetryable() {
        assertThat(HolmesErrorClassifier.classify(500).retryable()).isTrue();
        assertThat(HolmesErrorClassifier.classify(502).retryable()).isTrue();
        assertThat(HolmesErrorClassifier.classify(503).retryable()).isTrue();
    }

    @Test
    void invalidRequestsAreTerminal() {
        assertThat(HolmesErrorClassifier.classify(400).kind()).isEqualTo(Kind.TERMINAL);
        assertThat(HolmesErrorClassifier.classify(404).kind()).isEqualTo(Kind.TERMINAL);
        assertThat(HolmesErrorClassifier.classify(422).kind()).isEqualTo(Kind.TERMINAL);
    }

    @Test
    void timeoutAndNetworkAreRetryable() {
        assertThat(HolmesErrorClassifier.timeout().retryable()).isTrue();
        assertThat(HolmesErrorClassifier.networkError().errorClass()).isEqualTo("NETWORK_ERROR");
    }

    @Test
    void unexpectedStatusConvergesRetryable() {
        // 未知按可重试收敛（max_attempts 封顶兜底，不会无限循环）
        assertThat(HolmesErrorClassifier.classify(302).retryable()).isTrue();
        assertThat(HolmesErrorClassifier.classify(302).errorClass()).isEqualTo("HTTP_UNEXPECTED");
    }
}
