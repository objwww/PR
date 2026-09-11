package com.objwww.pr.control.alert.domain.classification;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UX-01 规则分类器单测（方案 §一"每类 ≥2 规则用例 + 未分类兜底 + PLATFORM 与 7 类不混"）：
 * 直标签与 alertname 关键词双通道、冻结优先级裁决、severity 不参与、输入有界。
 */
class IncidentClassifierTest {

    private final IncidentClassifier classifier = new IncidentClassifier();

    private static Map<String, String> labels(String alertname) {
        return Map.of("alertname", alertname);
    }

    private static Map<String, String> labels(String alertname, String k, String v) {
        return Map.of("alertname", alertname, k, v);
    }

    // ------------------------------------------------------------------ 7 个业务类（每类 ≥2 规则）

    @Test
    void businessViaLabelAndAlertname() {
        assertThat(classifier.classify(labels("Whatever", "domain", "business")).category())
                .isEqualTo(IncidentCategory.BUSINESS);
        assertThat(classifier.classify(labels("OrderFailureRateHigh")).category())
                .isEqualTo(IncidentCategory.BUSINESS);
        assertThat(classifier.classify(labels("PaymentSloBurnRate")).category())
                .isEqualTo(IncidentCategory.BUSINESS);
        // 反例：裸 "slo" 子串（dnslookup）不得误入 BUSINESS
        assertThat(classifier.classify(labels("DnsLookupFailures")).category())
                .isEqualTo(IncidentCategory.NETWORK);
    }

    @Test
    void applicationViaLabelAndAlertname() {
        assertThat(classifier.classify(labels("Whatever", "domain", "application")).category())
                .isEqualTo(IncidentCategory.APPLICATION);
        assertThat(classifier.classify(labels("Http5xxErrorRateHigh")).category())
                .isEqualTo(IncidentCategory.APPLICATION);
        assertThat(classifier.classify(labels("WorkerCrashLoopBackOff")).category())
                .isEqualTo(IncidentCategory.APPLICATION);
    }

    @Test
    void dependencyViaLabelAndAlertname() {
        assertThat(classifier.classify(labels("Whatever", "category", "dependency")).category())
                .isEqualTo(IncidentCategory.DEPENDENCY);
        assertThat(classifier.classify(labels("PostgresReplicationLagHigh")).category())
                .isEqualTo(IncidentCategory.DEPENDENCY);
        assertThat(classifier.classify(labels("KafkaConsumerLag")).category())
                .isEqualTo(IncidentCategory.DEPENDENCY);
    }

    @Test
    void infraViaLabelAndAlertname() {
        assertThat(classifier.classify(labels("Whatever", "domain", "infra")).category())
                .isEqualTo(IncidentCategory.INFRA);
        assertThat(classifier.classify(labels("HighCPUUsage")).category())
                .isEqualTo(IncidentCategory.INFRA);
        assertThat(classifier.classify(labels("NodeDiskPressure")).category())
                .isEqualTo(IncidentCategory.INFRA);
    }

    @Test
    void networkViaLabelAndAlertname() {
        assertThat(classifier.classify(labels("Whatever", "domain", "network")).category())
                .isEqualTo(IncidentCategory.NETWORK);
        assertThat(classifier.classify(labels("DnsLookupFailures")).category())
                .isEqualTo(IncidentCategory.NETWORK);
        assertThat(classifier.classify(labels("TlsHandshakeTimeout")).category())
                .isEqualTo(IncidentCategory.NETWORK);
    }

    @Test
    void dataViaLabelAndAlertname() {
        assertThat(classifier.classify(labels("Whatever", "domain", "data")).category())
                .isEqualTo(IncidentCategory.DATA);
        assertThat(classifier.classify(labels("DataQualityCheckFailed")).category())
                .isEqualTo(IncidentCategory.DATA);
        assertThat(classifier.classify(labels("EtlPipelineFailRate")).category())
                .isEqualTo(IncidentCategory.DATA);
    }

    @Test
    void securityViaLabelAndAlertname() {
        assertThat(classifier.classify(labels("Whatever", "domain", "security")).category())
                .isEqualTo(IncidentCategory.SECURITY);
        assertThat(classifier.classify(labels("AuthFailureSpike")).category())
                .isEqualTo(IncidentCategory.SECURITY);
        assertThat(classifier.classify(labels("PolicyViolationDetected")).category())
                .isEqualTo(IncidentCategory.SECURITY);
    }

    // ------------------------------------------------------------------ PLATFORM / UNCLASSIFIED 独立出口

    @Test
    void platformViaControlPlaneServiceOrAlertname() {
        Classification byService = classifier.classify(
                labels("AnythingAtAll", "service", "control-app"));
        assertThat(byService.category()).isEqualTo(IncidentCategory.PLATFORM);
        assertThat(byService.ruleId()).isEqualTo("PLATFORM-SERVICE");
        assertThat(classifier.classify(labels("ControlAppDown", "service", "other")).category())
                .isEqualTo(IncidentCategory.PLATFORM);
        assertThat(classifier.classify(labels("x", "service_name", "duty-adapter")).category())
                .isEqualTo(IncidentCategory.PLATFORM);
    }

    /** PLATFORM 置顶：控制面服务的业务味 alertname 也不落入 7 个业务类 */
    @Test
    void platformIsNotMixedIntoBusinessClasses() {
        Classification c = classifier.classify(
                labels("OrderFailureRateHigh", "service", "notify-app"));
        assertThat(c.category()).isEqualTo(IncidentCategory.PLATFORM);
        assertThat(c.ruleId()).isEqualTo("PLATFORM-SERVICE");
    }

    @Test
    void unclassifiedFallbackHasStableRuleIdAndNoFakeConfidence() {
        Classification c = classifier.classify(labels("SomeWeirdAlert"));
        assertThat(c.category()).isEqualTo(IncidentCategory.UNCLASSIFIED);
        assertThat(c.ruleId()).isEqualTo(IncidentClassifier.FALLBACK_RULE_ID);
        assertThat(c.ruleVersion()).isEqualTo(IncidentClassifier.RULE_VERSION);
        assertThat(c.basis()).contains("无规则命中");
    }

    // ------------------------------------------------------------------ 裁决律

    /** 多命中按冻结优先级：SECURITY 先于 APPLICATION（authfail + 5xx 同现 → SECURITY） */
    @Test
    void multiHitResolvesByFrozenPriorityWithStableRuleId() {
        Classification c = classifier.classify(labels("AuthFailHttp5xxSpike"));
        assertThat(c.category()).isEqualTo(IncidentCategory.SECURITY);
        assertThat(c.ruleId()).isEqualTo("SECURITY-ALERTNAME");
    }

    /** severity 不参与分类（INV：升级不换类） */
    @Test
    void severityNeverDrivesCategory() {
        Map<String, String> labels = new HashMap<>();
        labels.put("alertname", "SomeWeirdAlert");
        labels.put("severity", "critical");
        assertThat(classifier.classify(labels).category())
                .isEqualTo(IncidentCategory.UNCLASSIFIED);
        assertThat(classifier.classify(labels("HighCPUUsage", "severity", "info")).category())
                .isEqualTo(IncidentCategory.INFRA);
    }

    /** 输入有界：超长标签截断后匹配不炸；空/缺标签走 fallback */
    @Test
    void boundedInputAndMissingLabels() {
        String huge = "x".repeat(100_000);
        assertThat(classifier.classify(labels(huge)).category())
                .isEqualTo(IncidentCategory.UNCLASSIFIED);
        assertThat(classifier.classify(labels("cpu" + huge)).category())
                .isEqualTo(IncidentCategory.INFRA); // 头部命中仍在截断窗内
        assertThat(classifier.classify(Map.of("service", "svc")).category())
                .isEqualTo(IncidentCategory.UNCLASSIFIED);
        assertThat(classifier.classify(Map.of()).category())
                .isEqualTo(IncidentCategory.UNCLASSIFIED);
    }
}
