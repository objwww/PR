package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.classification.IncidentCategory;
import com.objwww.pr.control.alert.domain.classification.IncidentClassifier;
import com.objwww.pr.control.alert.domain.model.AlertFiringStatus;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.alert.domain.service.AlertIdentityFactory;
import com.objwww.pr.control.alert.domain.service.DeferredPolicy;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.release.application.CanaryRouter;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UX-01 投影侧分类接线单测（ExA4bIncidentProjectorTest 同形态假件）：
 * 首见分类落 rule_* 面、重复/晚到不重分类、episode 内材料变化重分类可追溯、
 * EU47——override 后再投影，规则面更新但人工值与生效面不变。
 */
class IncidentProjectorClassificationTest {

    private final AlertInMemoryStores stores = new AlertInMemoryStores();
    private final AlertIdentityFactory identity = new AlertIdentityFactory();
    private final Instant now = Instant.parse("2026-09-11T00:00:00Z");

    /** HOLMES 意愿路由（不铸 run）——分类面与 run 铸造解耦，单测聚焦分类时机 */
    private IncidentProjector projector() {
        CanaryRouter router = org.mockito.Mockito.mock(CanaryRouter.class);
        org.mockito.Mockito.when(router.route(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RcaRunRouting(RcaEngine.HOLMES, null, null, null,
                        "BUCKETED_HOLMES"));
        return new IncidentProjector(stores.events, stores.incidents, stores.runs,
                stores.tasks, identity, new DeferredPolicy(1000), SlaPolicy.defaults(),
                () -> now, router, new IncidentClassifier(), stores.categories);
    }

    private ParsedAlert firing(String alertname, Instant startsAt,
                               Map<String, String> extraLabels) {
        Map<String, String> labels = new HashMap<>(extraLabels);
        labels.put("alertname", alertname);
        labels.put("service", "svc");
        return new ParsedAlert(AlertFiringStatus.FIRING,
                "fp-" + alertname + "-" + startsAt + "-" + labels.hashCode(),
                labels, Map.of(), startsAt, null);
    }

    @Test
    void firstSeenAlertIsClassifiedOntoRuleFace() {
        projector().project(UUID.randomUUID(),
                List.of(firing("HighCPUUsage", now, Map.of())));

        Incident i = stores.incidents.all().get(0);
        var state = stores.categories.state(i.id());
        assertThat(state.ruleCategory).isEqualTo("INFRA");
        assertThat(state.ruleId).isEqualTo("INFRA-ALERTNAME");
        assertThat(state.ruleVersion).isEqualTo(IncidentClassifier.RULE_VERSION);
        assertThat(state.classifiedAt).isEqualTo(now);
        assertThat(state.overrideCategory).isNull();
        assertThat(state.effective()).isEqualTo("INFRA");
        assertThat(stores.categories.applyRuleCalls).isEqualTo(1);
    }

    @Test
    void unclassifiableAlertLandsOnExplicitUnclassifiedFallback() {
        projector().project(UUID.randomUUID(),
                List.of(firing("SomeWeirdAlert", now, Map.of())));

        var state = stores.categories.state(stores.incidents.all().get(0).id());
        assertThat(state.ruleCategory).isEqualTo("UNCLASSIFIED");
        assertThat(state.ruleId).isEqualTo(IncidentClassifier.FALLBACK_RULE_ID);
    }

    @Test
    void duplicateNotificationDoesNotReclassify() {
        IncidentProjector p = projector();
        ParsedAlert alert = firing("HighCPUUsage", now, Map.of());
        // 同 fingerprint/labels/startsAt 重投（payloadHash 相同 → 重复通知）
        p.project(UUID.randomUUID(), List.of(alert));
        p.project(UUID.randomUUID(), List.of(alert));

        assertThat(stores.categories.applyRuleCalls).isEqualTo(1);
    }

    @Test
    void lateOutOfEpisodeEventDoesNotReclassify() {
        IncidentProjector p = projector();
        p.project(UUID.randomUUID(), List.of(firing("HighCPUUsage", now, Map.of())));
        // 晚到（startsAt 早于 episode 水印）：§6.7 只计数，不从旧材料刷新分类面
        p.project(UUID.randomUUID(), List.of(firing("HighCPUUsage",
                now.minusSeconds(3600), Map.of("domain", "network"))));

        var state = stores.categories.state(stores.incidents.all().get(0).id());
        assertThat(stores.categories.applyRuleCalls).isEqualTo(1);
        assertThat(state.ruleCategory).isEqualTo("INFRA");
    }

    @Test
    void inEpisodeMaterialChangeReclassifiesWithTraceableVersion() {
        IncidentProjector p = projector();
        p.project(UUID.randomUUID(), List.of(firing("HighCPUUsage", now, Map.of())));
        // episode 内新事件带 domain 直标签（incidentKey 标签不变 → 同 incident）：
        // NETWORK-LABEL 优先级高于 INFRA-ALERTNAME → 规则面改判 NETWORK
        p.project(UUID.randomUUID(), List.of(firing("HighCPUUsage",
                now.plusSeconds(60), Map.of("domain", "network"))));

        var state = stores.categories.state(stores.incidents.all().get(0).id());
        assertThat(stores.categories.applyRuleCalls).isEqualTo(2);
        assertThat(state.ruleCategory).isEqualTo("NETWORK");
        assertThat(state.ruleId).isEqualTo("NETWORK-LABEL");
        assertThat(state.ruleVersion).isEqualTo(IncidentClassifier.RULE_VERSION);
    }

    /** EU47：override 后告警再次投影——规则面可追溯更新，人工值/生效面/revision 不变 */
    @Test
    void overrideSurvivesSubsequentProjection() {
        IncidentProjector p = projector();
        p.project(UUID.randomUUID(), List.of(firing("HighCPUUsage", now, Map.of())));
        Incident i = stores.incidents.all().get(0);

        CategoryOverrideService overrides = new CategoryOverrideService(stores.categories,
                TransactionOperations.withoutTransaction(), () -> now);
        overrides.override(i.id(), "SECURITY", "安全团队人工确认", 0, "k-1", "sec-op");

        p.project(UUID.randomUUID(), List.of(firing("HighCPUUsage",
                now.plusSeconds(60), Map.of("domain", "network"))));

        var state = stores.categories.state(i.id());
        assertThat(state.ruleCategory).isEqualTo("NETWORK");        // 规则面更新
        assertThat(state.overrideCategory).isEqualTo("SECURITY");   // 人工值保留
        assertThat(state.overrideRevision).isEqualTo(1);
        assertThat(state.effective()).isEqualTo("SECURITY");
    }
}
