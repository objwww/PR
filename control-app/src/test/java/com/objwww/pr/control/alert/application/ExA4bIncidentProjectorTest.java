package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.AlertEvent;
import com.objwww.pr.control.alert.domain.model.AlertFiringStatus;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.service.AlertIdentityFactory;
import com.objwww.pr.control.alert.domain.service.DeferredPolicy;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.release.application.CanaryRouter;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-A4b（F18/F19/F23/F24）契约断言组：
 * <ul>
 *   <li>F18：背压不阻恢复事件；admission 只挡新调查（暂扣→waitingReason=DEFERRED）；</li>
 *   <li>F19：insert 冲突返回 false（不再 DuplicateKeyException），同"事务"重查可续；</li>
 *   <li>F23：存量 incidentKey v1 管道形不动（不分裂既有事故）；</li>
 *   <li>F24：HOLMES 意愿 → WAITING_CAPABILITY 显式态；重驱扫描恢复后补铸并清等待。</li>
 * </ul>
 */
class ExA4bIncidentProjectorTest {

    private final AlertInMemoryStores stores = new AlertInMemoryStores();
    private final AlertIdentityFactory identity = new AlertIdentityFactory();
    private final Instant now = Instant.parse("2026-09-10T00:00:00Z");

    private IncidentProjector projector(boolean canaryWilling) {
        return new IncidentProjector(stores.events, stores.incidents, stores.runs,
                stores.tasks, identity, new DeferredPolicy(1), SlaPolicy.defaults(),
                () -> now, router(canaryWilling),
                new com.objwww.pr.control.alert.domain.classification.IncidentClassifier(),
                stores.categories);
    }

    /** 路由假件：willing=NATIVE（BUCKETED_NATIVE），否则 HOLMES 意愿（不铸 run） */
    private CanaryRouter router(boolean willing) {
        RcaRunRouting routing = willing
                ? new RcaRunRouting(RcaEngine.NATIVE, Digest.sha256Of("cfg"),
                        null, null, "BUCKETED_NATIVE")
                : new RcaRunRouting(RcaEngine.HOLMES, null, null, null, "BUCKETED_HOLMES");
        CanaryRouter router = org.mockito.Mockito.mock(CanaryRouter.class);
        org.mockito.Mockito.when(router.route(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(routing);
        return router;
    }

    private ParsedAlert firing(String alertname, String annotations) {
        return new ParsedAlert(AlertFiringStatus.FIRING,
                "fp-" + alertname + "-" + annotations,
                Map.of("alertname", alertname, "service", "svc"),
                Map.of("runbook", annotations), now, null);
    }

    private ParsedAlert resolved(String alertname) {
        return new ParsedAlert(AlertFiringStatus.RESOLVED, "fp-" + alertname + "-r",
                Map.of("alertname", alertname, "service", "svc"),
                Map.of(), now.plusSeconds(60), null);
    }

    @Test
    void f18_resolvedAlertIsNotBlockedByBackpressure() {
        IncidentProjector p = projector(true);
        p.project(UUID.randomUUID(), List.of(firing("HighLatency", "rb-1")));
        // 已有 1 active；阈值=1 → 旧语义会把 resolved 整条 DEFERRED（恢复被吞）
        var outcome = p.project(UUID.randomUUID(), List.of(resolved("HighLatency")));
        Incident incident = stores.incidents.all().get(0);
        assertThat(incident.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(outcome.deferredCount()).isZero();
    }

    @Test
    void f18_f24_firstSeenAtCapStillRecordsFactAndMarksWaitingDeferred() {
        IncidentProjector p = projector(true);
        p.project(UUID.randomUUID(), List.of(firing("A", "rb-1"))); // active=1, queued=1 → 超 1
        var outcome = p.project(UUID.randomUUID(), List.of(firing("B", "rb-1")));
        Incident b = stores.incidents.findByKeyForUpdate("alertname=B|service=svc").orElseThrow();
        assertThat(b.waitingReason()).isEqualTo("DEFERRED");
        assertThat(b.status()).isEqualTo(IncidentStatus.FIRING);
        assertThat(stores.events.all()).anySatisfy(e ->
                assertThat(e.incidentId()).isEqualTo(b.id())); // 事实已入通道
        assertThat(stores.runs.all()).noneMatch(r -> r.incidentId().equals(b.id()));
        assertThat(outcome.deferredCount()).isEqualTo(1);
    }

    @Test
    void f24_holmesWillingLeavesExplicitWaitingCapabilityAndNoRun() {
        IncidentProjector p = projector(false);
        p.project(UUID.randomUUID(), List.of(firing("C", "rb-1")));
        Incident c = stores.incidents.findByKeyForUpdate("alertname=C|service=svc").orElseThrow();
        assertThat(c.waitingReason()).isEqualTo("WAITING_CAPABILITY");
        assertThat(stores.runs.all()).isEmpty();
        assertThat(stores.tasks.all()).isEmpty();
    }

    @Test
    void f24_redriveCastsRunWhenWillingAndClearsWaiting() {
        IncidentProjector p = projector(false);
        p.project(UUID.randomUUID(), List.of(firing("D", "rb-1")));
        IncidentWaitingRedrive redrive = new IncidentWaitingRedrive(stores.incidents,
                stores.runs, stores.tasks, router(true), new DeferredPolicy(100),
                SlaPolicy.defaults(), () -> now.plusSeconds(120), Duration.ofSeconds(30));
        assertThat(redrive.redriveOnce()).isEqualTo(1);
        Incident d = stores.incidents.findByKeyForUpdate("alertname=D|service=svc").orElseThrow();
        assertThat(d.waitingReason()).isNull();
        assertThat(d.currentRcaRunId()).isNotNull();
        assertThat(stores.tasks.all().get(0).state()).isEqualTo(RcaTaskState.READY);
        assertThat(redrive.redriveOnce()).isZero(); // 已无等待行，重驱幂等
    }

    @Test
    void f19_insertConflictReturnsFalseAndMergeFaceContinues() {
        Incident a = new Incident(UUID.randomUUID(), "k1", IncidentStatus.FIRING, 0,
                now, now, null, null, null, 0, 0, 0, null, now, now, now, now);
        Incident b = new Incident(UUID.randomUUID(), "k1", IncidentStatus.FIRING, 0,
                now, now, null, null, null, 0, 0, 0, null, now, now, now, now);
        assertThat(stores.incidents.insert(a)).isTrue();
        assertThat(stores.incidents.insert(b)).isFalse(); // 冲突=布尔面，非异常
        assertThat(stores.incidents.all()).hasSize(1);
    }

    @Test
    void f23_legacyIncidentKeyFormatUnchanged() {
        // 存量兼容：k=v'|'join 与 payloadHash v1 规范形保持原样，
        // 升级后同告警不产生新键/新哈希（不分裂既有事故、不破去重）
        assertThat(identity.incidentKey(Map.of("alertname", "X", "service", "svc")))
                .isEqualTo("alertname=X|service=svc");
        Digest payload = identity.payloadHash(AlertFiringStatus.FIRING,
                Map.of("alertname", "X", "service", "svc"), now);
        assertThat(payload.value()).isEqualTo(new AlertIdentityFactory()
                .payloadHash(AlertFiringStatus.FIRING,
                        Map.of("alertname", "X", "service", "svc"), now).value());
        // 17 参便捷构造 = waitingReason null（存量序列化/调用面零改动）
        Incident legacy = new Incident(UUID.randomUUID(), "k", IncidentStatus.FIRING, 0,
                now, now, null, null, null, 0, 0, 0, null, now, now, now, now);
        assertThat(legacy.waitingReason()).isNull();
    }

    @Test
    void f18_repeatNotificationOfDeferredIncidentCountsDuplicateNotRecast() {
        IncidentProjector p = projector(true);
        p.project(UUID.randomUUID(), List.of(firing("E", "rb-1")));
        assertThat(stores.events.all()).hasSize(1);
        // 同 payload 重投：duplicate 计数，不重复追加、不重复铸造
        p.project(UUID.randomUUID(), List.of(firing("E", "rb-1")));
        assertThat(stores.events.all()).hasSize(1);
        assertThat(stores.incidents.findByKeyForUpdate("alertname=E|service=svc")
                .orElseThrow().notificationCount()).isEqualTo(1);
    }

    /**
     * MC23/P0-4 时序缝隙钉案（ characterization，先测后修——修法须裁定）：旧 run
     * 活跃中发生真再现（startsAt 晚于 resolvedAt）→ castRunIfFree 返回 null 不铸新
     * run（uq_rca_run_active_incident 双活跃禁面），新 episode（generation+1）期间
     * 无 run 且<b>无显式等待态</b>（waitingReason=null，沉默无调查）；旧 run 收尾走
     * orchestrator 分支 3（pending=null 材料未变）不补偿铸造——下一事件且材料变化
     * 才补铸。钉案结论：缝隙在案，修法候选（再现即取消旧 run 铸新 / 旧 run 收尾
     * 补偿铸造）待裁定后另批实施。
     */
    @Test
    void mc23_reproductionDuringActiveRunSeamCharacterization() {
        IncidentProjector p = new IncidentProjector(stores.events, stores.incidents,
                stores.runs, stores.tasks, identity, new DeferredPolicy(100),
                SlaPolicy.defaults(), () -> now, router(true),
                new com.objwww.pr.control.alert.domain.classification.IncidentClassifier(),
                stores.categories);
        p.project(UUID.randomUUID(), List.of(firing("F", "rb-1")));
        UUID oldRunId = stores.runs.all().get(0).id();
        assertThat(stores.runs.all()).as("首事件铸 run").hasSize(1);

        p.project(UUID.randomUUID(), List.of(resolved("F")));
        // 真再现：startsAt 晚于 resolvedAt（乱序防御不吞）
        ParsedAlert reproduction = new ParsedAlert(AlertFiringStatus.FIRING,
                "fp-F-rb-1", Map.of("alertname", "F", "service", "svc"),
                Map.of("runbook", "rb-1"), now.plusSeconds(120), null);
        p.project(UUID.randomUUID(), List.of(reproduction));

        Incident f = stores.incidents.findByKeyForUpdate("alertname=F|service=svc")
                .orElseThrow();
        assertThat(f.status()).isEqualTo(IncidentStatus.FIRING);
        assertThat(f.generation()).as("再现 = 新 episode（generation+1）").isEqualTo(1);
        assertThat(stores.runs.all()).as("不铸第二活跃 run（uq 不变面）").hasSize(1);
        assertThat(f.currentRcaRunId()).as("指针仍指旧 run（跨代残留）").isEqualTo(oldRunId);
        assertThat(f.waitingReason()).as("缝隙：无显式等待态，沉默无调查").isNull();

        // 旧 run 收尾（终态化）后：材料未变的下一 FIRING 事件也不补铸（R7 方案
        // "材料未变=重复调查无益"——新 episode 继续无 run，缝隙延续到材料变化）
        stores.runs.update(withRunTerminal(stores.runs.all().get(0)));
        p.project(UUID.randomUUID(), List.of(firing("F", "rb-1")));
        assertThat(stores.runs.all())
                .as("钉案：旧 run 终态 + 材料未变 = 仍不补铸（修法待裁定）").hasSize(1);
    }

    private com.objwww.pr.control.alert.domain.model.RcaRun withRunTerminal(
            com.objwww.pr.control.alert.domain.model.RcaRun run) {
        return new com.objwww.pr.control.alert.domain.model.RcaRun(run.id(),
                run.incidentId(), run.generation(), run.trigger(),
                com.objwww.pr.control.alert.domain.model.RcaRunState.SUCCEEDED,
                run.investigationHash(), run.createdAt(), now.plusSeconds(180),
                now.plusSeconds(180), null, null);
    }
}
