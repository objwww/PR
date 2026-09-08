package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaAttemptStatus;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6-04 generation 发布赢家栅栏（C-68）：同 (incident, generation) 双报告竞争——
 * 先收尾者取赢家并发布（publication + 每渠道一条 outbox），败者报告仍落档但不发布
 * 不通知。走 RcaWorker 真实驱动链（材料变化 RERUN 同代竞争 = 生产竞争形态）。
 * 并发 CAS 面由 195 真 PG IT 覆盖。
 */
class PublicationWinnerGateTest {

    private static final class MutableClock implements AlertClock {
        volatile Instant now = Instant.parse("2026-09-08T10:00:00Z");

        @Override
        public Instant now() {
            return now;
        }
    }

    /** 剧本执行器：恒出"六段式结构验证通过"产物 */
    private static final class SuccessExecutor implements RcaTaskExecutor {
        int heartbeatCalls;

        @Override
        public ExecutionResult execute(RcaTask task, RcaRun run, Incident incident,
                                       RcaAttempt attempt, Runnable heartbeat) {
            heartbeat.run();
            heartbeatCalls++;
            return ExecutionResult.success(new RcaTaskExecutor.AttemptArtifact(1,
                    ValidationStatus.STRUCTURE_VALIDATED, List.of(),
                    "{\"schema_version\":\"1\",\"summary\":\"s" + run.id().toString()
                            .substring(0, 8) + "\"}",
                    "raw-" + run.id(), null, List.of(), null, null, "deepseek-v3",
                    null, null, null, true, null));
        }
    }

    private AlertInMemoryStores stores;
    private MutableClock clock;
    private SuccessExecutor executor;
    private AlertInboxProcessor intake;
    private RcaWorker worker;
    private RcaRunOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        stores = new AlertInMemoryStores();
        clock = new MutableClock();
        executor = new SuccessExecutor();
        // M6-07：fixture 迁 Native 唯一引擎面（percent=100 全桶 NATIVE——原 holmesOnly
        // 路由下的 HOLMES 投影已不铸 run，C-77）
        NativeEngineWiringTest.WiringBundles bundles = new NativeEngineWiringTest.WiringBundles();
        java.util.Map<String, Object> canary = new java.util.LinkedHashMap<>();
        canary.put("percent", 100);
        canary.put("whitelist", List.of());
        canary.put("max_native_runs", 100);
        java.util.Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("policy_version", "policy-2026-09");
        content.put("canary", canary);
        bundles.publish(content);
        com.objwww.pr.control.release.application.CanaryRouter nativeRouter =
                new com.objwww.pr.control.release.application.CanaryRouter(bundles,
                        new NativeEngineWiringTest.WiringDecisions(), true, clock::now);
        intake = new AlertInboxProcessor(stores.inbox,
                new IncidentProjector(stores.events, stores.incidents, stores.runs,
                        stores.tasks, new com.objwww.pr.control.alert.domain.service.AlertIdentityFactory(),
                        new com.objwww.pr.control.alert.domain.service.DeferredPolicy(1000),
                        SlaPolicy.defaults(), clock, nativeRouter),
                org.springframework.transaction.support.TransactionOperations.withoutTransaction(),
                clock, "intake-owner", Duration.ofMinutes(2), Duration.ofSeconds(30),
                Duration.ofSeconds(10), Duration.ofSeconds(1));
        orchestrator = new RcaRunOrchestrator(stores.tasks, stores.runs, stores.attempts,
                stores.reports, stores.incidents, stores.slots, stores.investigations,
                stores.toolCalls, notifier(), stores.cas, SlaPolicy.defaults(), clock,
                "rca", AlertMetrics.NOOP, nativeRouter, stores.winners);
        worker = new RcaWorker(stores.tasks, stores.runs, stores.attempts,
                stores.investigations, stores.incidents, stores.slots, stores.invocations,
                java.util.Map.of(com.objwww.pr.control.alert.domain.model.RcaEngine.NATIVE,
                        executor),
                orchestrator,
                org.springframework.transaction.support.TransactionOperations.withoutTransaction(),
                clock, "worker-a", "rca", Duration.ofMinutes(5), Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofMinutes(1), Duration.ofMinutes(10), 2);
    }

    private ReportCompletedNotifier notifier() {
        return new ReportCompletedNotifier(stores.publications, stores.outboxes,
                List.of("test"), "am3-candidate-v1", 280);
    }

    /** startsAt 随材料递增（同 startsAt 同 labels = 同 payloadHash，会被去重判重吞掉投影） */
    private void deliver(String startsAt, String material) {
        stores.inbox.insert(com.objwww.pr.control.alert.support.TestFixtures.inboxRowOf(
                UUID.randomUUID(), com.objwww.pr.control.alert.support.TestFixtures.amGroup(
                        "g-checkout", 0, com.objwww.pr.control.alert.support.TestFixtures
                                .alertJson("HighErrorRate", "checkout", "warning", "firing",
                                        startsAt, material))));
        assertThat(intake.processOnce()).isEqualTo(AlertInboxProcessor.Outcome.ACCEPTED);
    }

    @Test
    @DisplayName("同 (incident,generation) 双报告：恰一份 publication/outbox，败者报告仍落档")
    void sameGenerationDualReportPublishesExactlyOnce() {
        // run1：材料一，成功收尾 → 赢家发布
        deliver("2026-09-08T09:00:00Z", "材料一");
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);
        RcaRun run1 = stores.runs.all().get(0);
        assertThat(run1.state()).isEqualTo(RcaRunState.SUCCEEDED);

        // 材料二到达（无活跃 run 且材料变化 → 投影器铸 RERUN，同 episode 代）
        deliver("2026-09-08T09:01:00Z", "材料二");
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);
        assertThat(stores.runs.all()).hasSize(2);
        RcaRun run2 = stores.runs.all().get(1);
        assertThat(run2.generation()).isEqualTo(run1.generation());
        // run2 同周期内执行收尾 → 竞争发布：赢家栅栏裁定后恰一份 publication/outbox
        assertThat(stores.runs.findById(run2.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.SUCCEEDED);

        // 报告两份都诚实落档（INV-AM3-7）；发布面只有赢家一份
        assertThat(stores.reports.all()).hasSize(2);
        assertThat(stores.publications.all()).hasSize(1);
        assertThat(stores.outboxes.all()).hasSize(1);   // 单渠道 test：每渠道恰一条
        // 赢家行恰一条，且指向 run1（先收尾者）
        UUID winnerReportId = stores.winners.findWinnerReportId(
                run1.incidentId(), run1.generation()).orElseThrow();
        assertThat(stores.reports.all().stream()
                .filter(r -> r.id().equals(winnerReportId)).findFirst().orElseThrow().runId())
                .isEqualTo(run1.id());
        assertThat(stores.publications.all().get(0).reportId()).isEqualTo(winnerReportId);
    }
}
