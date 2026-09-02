package com.objwww.pr.control.infrastructure.model;

import com.objwww.pr.control.domain.ai.FaultScope;
import com.objwww.pr.control.domain.ai.ModelCallFailure;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProviderErrorClassifier：HTTP status × 百炼 error code 二维分类矩阵（§4.2）+
 * 网络/协议/本地超时/未知四类非 HTTP 入口。
 */
class ProviderErrorClassifierTest {

    private final ProviderErrorClassifier classifier = new ProviderErrorClassifier();

    // ------------------------------------------------------------------ 400

    @Test
    void status400WithoutCodeIsRequestInvalid() {
        ModelCallFailure f = classifier.classify(400, null, null);
        assertThat(f).isInstanceOf(ModelCallFailure.RequestInvalid.class);
        assertThat(f.faultScope()).isEqualTo(FaultScope.MODEL);
    }

    @Test
    void status400ArrearageIsBillingOrActivation() {
        ModelCallFailure f = classifier.classify(400, "Arrearage", null);
        assertThat(f).isInstanceOf(ModelCallFailure.BillingOrActivation.class);
        assertThat(f.faultScope()).isEqualTo(FaultScope.ACCOUNT);
    }

    // ------------------------------------------------------------------ 401 / 403

    @Test
    void status401IsAuthDenied() {
        ModelCallFailure f = classifier.classify(401, null, null);
        assertThat(f).isInstanceOf(ModelCallFailure.AuthDenied.class);
        assertThat(f.faultScope()).isEqualTo(FaultScope.CREDENTIAL);
    }

    @Test
    void status403WithoutCodeOrAccessDeniedIsAuthDenied() {
        assertThat(classifier.classify(403, null, null))
                .isInstanceOf(ModelCallFailure.AuthDenied.class);
        ModelCallFailure f = classifier.classify(403, "Model.AccessDenied", null);
        assertThat(f).isInstanceOf(ModelCallFailure.AuthDenied.class);
        assertThat(f.faultScope()).isEqualTo(FaultScope.CREDENTIAL);
    }

    @Test
    void status403FreeTierOrAllocationQuotaIsQuotaExhausted() {
        ModelCallFailure freeTier = classifier.classify(403, "AllocationQuota.FreeTierOnly", null);
        assertThat(freeTier).isInstanceOf(ModelCallFailure.QuotaExhausted.class);
        assertThat(freeTier.faultScope()).isEqualTo(FaultScope.ACCOUNT);
        assertThat(classifier.classify(403, "AllocationQuota", null))
                .isInstanceOf(ModelCallFailure.QuotaExhausted.class);
    }

    // ------------------------------------------------------------------ 404 / 408

    @Test
    void status404IsRequestInvalid() {
        ModelCallFailure f = classifier.classify(404, null, null);
        assertThat(f).isInstanceOf(ModelCallFailure.RequestInvalid.class);
        assertThat(f.faultScope()).isEqualTo(FaultScope.MODEL);
    }

    @Test
    void status408IsRemoteTimeout() {
        ModelCallFailure f = classifier.classify(408, null, null);
        ModelCallFailure.Timeout t = (ModelCallFailure.Timeout) f;
        assertThat(t.remote()).isTrue();
        assertThat(t.faultScope()).isEqualTo(FaultScope.ENDPOINT);
    }

    // ------------------------------------------------------------------ 429

    @Test
    void status429RateQuotaOrBurstRateIsModelScopedRateLimit() {
        ModelCallFailure rateQuota = classifier.classify(429, "Throttling.RateQuota", Duration.ofSeconds(3));
        ModelCallFailure.RateLimitedTransient rl = (ModelCallFailure.RateLimitedTransient) rateQuota;
        assertThat(rl.faultScope()).isEqualTo(FaultScope.MODEL);
        assertThat(rl.retryAfter()).isEqualTo(Duration.ofSeconds(3));

        ModelCallFailure burst = classifier.classify(429, "Throttling.BurstRate", null);
        ModelCallFailure.RateLimitedTransient rl2 = (ModelCallFailure.RateLimitedTransient) burst;
        assertThat(rl2.faultScope()).isEqualTo(FaultScope.MODEL);
    }

    @Test
    void status429AllocationQuotaIsAccountQuotaTemporaryWithNotBefore() {
        Instant before = Instant.now();
        ModelCallFailure f = classifier.classify(429, "Throttling.AllocationQuota", Duration.ofSeconds(30));
        ModelCallFailure.QuotaTemporary qt = (ModelCallFailure.QuotaTemporary) f;
        assertThat(qt.faultScope()).isEqualTo(FaultScope.ACCOUNT);
        // notBefore ≈ now + retryAfter(30s)
        assertThat(qt.notBefore()).isBetween(before.plusSeconds(29), Instant.now().plusSeconds(31));

        assertThat(classifier.classify(429, "insufficient_quota", Duration.ofSeconds(5)))
                .isInstanceOf(ModelCallFailure.QuotaTemporary.class);
    }

    @Test
    void status429WithoutCodeIsAccountScopedRateLimit() {
        ModelCallFailure f = classifier.classify(429, null, Duration.ofSeconds(2));
        ModelCallFailure.RateLimitedTransient rl = (ModelCallFailure.RateLimitedTransient) f;
        assertThat(rl.faultScope()).isEqualTo(FaultScope.ACCOUNT);
        assertThat(rl.retryAfter()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void status429UnknownCodeIsUnknownError() {
        ModelCallFailure f = classifier.classify(429, "SomethingNew", null);
        ModelCallFailure.UnknownError u = (ModelCallFailure.UnknownError) f;
        assertThat(u.reason()).isEqualTo("429:SomethingNew");
        assertThat(u.faultScope()).isNull();
    }

    // ------------------------------------------------------------------ 5xx / 非标准码

    @Test
    void status500And503AreEndpointServerError() {
        for (int status : new int[]{500, 503}) {
            ModelCallFailure f = classifier.classify(status, null, null);
            assertThat(f).isInstanceOf(ModelCallFailure.ServerError.class);
            assertThat(f.faultScope()).isEqualTo(FaultScope.ENDPOINT);
        }
    }

    @Test
    void nonStandardStatusIsUnknownError() {
        ModelCallFailure f = classifier.classify(302, null, null);
        ModelCallFailure.UnknownError u = (ModelCallFailure.UnknownError) f;
        assertThat(u.reason()).isEqualTo("HTTP 302");
        assertThat(u.faultScope()).isNull();
    }

    // ------------------------------------------------------------------ 非 HTTP 入口

    @Test
    void classifyTimeoutIsLocalNonRemote() {
        ModelCallFailure.Timeout t = (ModelCallFailure.Timeout) classifier.classifyTimeout();
        assertThat(t.remote()).isFalse();
        assertThat(t.faultScope()).isEqualTo(FaultScope.ENDPOINT);
    }

    @Test
    void classifyNetworkErrorIsEndpointScoped() {
        ModelCallFailure f = classifier.classifyNetworkError("connection reset");
        assertThat(f).isInstanceOf(ModelCallFailure.NetworkError.class);
        assertThat(f.faultScope()).isEqualTo(FaultScope.ENDPOINT);
    }

    @Test
    void classifyProtocolErrorIsEndpointScoped() {
        ModelCallFailure f = classifier.classifyProtocolError("truncated body");
        assertThat(f).isInstanceOf(ModelCallFailure.ProtocolError.class);
        assertThat(f.faultScope()).isEqualTo(FaultScope.ENDPOINT);
    }

    @Test
    void classifyUnknownCarriesReasonAndNullScope() {
        ModelCallFailure.UnknownError u = (ModelCallFailure.UnknownError) classifier.classifyUnknown("weird sdk");
        assertThat(u.reason()).isEqualTo("weird sdk");
        assertThat(u.faultScope()).isNull();
    }
}
