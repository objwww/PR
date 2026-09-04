package com.objwww.pr.control.alert.domain.service;

import com.objwww.pr.control.alert.domain.model.AlertFiringStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UT-A05：AlertIdentityFactory——incidentKey 稳定标签纪律（INV-AM1-4）+ 双哈希分离（§6.3）。
 */
class AlertIdentityFactoryTest {

    private final AlertIdentityFactory factory = new AlertIdentityFactory();

    private static final Instant T1 = Instant.parse("2026-09-03T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-03T10:05:00Z");

    private static Map<String, String> baseLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("alertname", "HighErrorRate");
        labels.put("service", "checkout");
        labels.put("job", "kube-job");
        return labels;
    }

    @Test
    void incidentKeyIsLabelOrderIndependent() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("job", "kube-job");
        a.put("alertname", "HighErrorRate");
        a.put("service", "checkout");

        assertThat(factory.incidentKey(a))
                .isEqualTo(factory.incidentKey(baseLabels()))
                .isEqualTo("alertname=HighErrorRate|service=checkout|job=kube-job");
    }

    @Test
    void incidentKeyExcludesSeverity() {
        // INV-AM1-4：级别升级（warning→critical）不换单——severity 不参与聚合身份
        Map<String, String> warning = new LinkedHashMap<>(baseLabels());
        warning.put("severity", "warning");
        Map<String, String> critical = new LinkedHashMap<>(baseLabels());
        critical.put("severity", "critical");

        assertThat(factory.incidentKey(warning)).isEqualTo(factory.incidentKey(critical));
    }

    @Test
    void incidentKeyWithoutAlertnameIsRejected() {
        assertThatThrownBy(() -> factory.incidentKey(Map.of("service", "checkout")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alertname");
    }

    @Test
    void payloadHashDiscriminatesStatusAndStartsAtButNotLabelOrder() {
        var firing = factory.payloadHash(AlertFiringStatus.FIRING, baseLabels(), T1);

        // 标签顺序无关
        Map<String, String> shuffled = new LinkedHashMap<>();
        shuffled.put("service", "checkout");
        shuffled.put("alertname", "HighErrorRate");
        shuffled.put("job", "kube-job");
        assertThat(factory.payloadHash(AlertFiringStatus.FIRING, shuffled, T1)).isEqualTo(firing);

        // status / startsAt 变化 ⇒ 判"不是同一条"
        assertThat(factory.payloadHash(AlertFiringStatus.RESOLVED, baseLabels(), T1))
                .isNotEqualTo(firing);
        assertThat(factory.payloadHash(AlertFiringStatus.FIRING, baseLabels(), T2))
                .isNotEqualTo(firing);
    }

    @Test
    void investigationHashIgnoresDynamicAnnotationsAndSeverity() {
        Map<String, String> staticAnn1 = Map.of("runbook", "rb-17", "current_value", "0.91");
        Map<String, String> staticAnn2 = Map.of("runbook", "rb-17", "current_value", "0.13");
        Map<String, String> labels1 = new LinkedHashMap<>(baseLabels());
        labels1.put("severity", "warning");
        Map<String, String> labels2 = new LinkedHashMap<>(baseLabels());
        labels2.put("severity", "critical");

        // 动态数值抖动 + 级别变化 ⇒ 材料未变，不值得重查
        assertThat(factory.investigationHash(labels1, staticAnn1))
                .isEqualTo(factory.investigationHash(labels2, staticAnn2));

        // 静态 annotation 变化 ⇒ 材料变化
        assertThat(factory.investigationHash(labels1, Map.of("runbook", "rb-18")))
                .isNotEqualTo(factory.investigationHash(labels1, staticAnn1));

        // 关键 label 变化 ⇒ 材料变化
        Map<String, String> otherService = new LinkedHashMap<>(baseLabels());
        otherService.put("service", "payment");
        assertThat(factory.investigationHash(otherService, staticAnn1))
                .isNotEqualTo(factory.investigationHash(labels1, staticAnn1));
    }

    @Test
    void dualHashSeparation() {
        // 同一 incidentKey 下：payload 变了（新一条通知）但 investigation 没变（材料相同）
        Map<String, String> labels = baseLabels();
        Map<String, String> ann = Map.of("runbook", "rb-17");

        var h1 = factory.payloadHash(AlertFiringStatus.FIRING, labels, T1);
        var h2 = factory.payloadHash(AlertFiringStatus.FIRING, labels, T2);

        assertThat(h1).isNotEqualTo(h2);
        assertThat(factory.investigationHash(labels, ann))
                .isEqualTo(factory.investigationHash(labels, ann));
    }
}
