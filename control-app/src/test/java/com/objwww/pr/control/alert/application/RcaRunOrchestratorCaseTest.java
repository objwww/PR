package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.ops.application.CaseDraft;
import com.objwww.pr.control.ops.application.OperatorCaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 处置单开单接线面（报告发布赢家 → operator_case 幂等开单/合并）：
 * 赢家开单、败者不开、confirmed 分级、开单失败不回滚收尾。
 * 复用 PublicationWinnerGateTest 的同代竞争驱动链（RcaWorker 真实驱动）。
 */
class RcaRunOrchestratorCaseTest {

    private static final class MutableClock implements AlertClock {
        volatile Instant now = Instant.parse("2026-09-08T10:00:00Z");

        @Override
        public Instant now() {
            return now;
        }
    }

    /** 记录开单草稿的伪件；failOpen=true 时抛错（验不阻断收尾纪律） */
    private static final class RecordingCaseService extends OperatorCaseService {
        final List<CaseDraft> drafts = new ArrayList<>();
        boolean failOpen;

        RecordingCaseService() {
            super(org.mockito.Mockito.mock(
                            com.objwww.pr.control.ops.domain.repository.OperatorCaseRepository.class),
                    () -> Instant.parse("2026-09-08T10:00:00Z"));
        }

        @Override
        public MergeOutcome openOrMerge(CaseDraft draft) {
            if (failOpen) {
                throw new IllegalStateException("模拟开单故障");
            }
            drafts.add(draft);
            return new MergeOutcome(UUID.randomUUID(), true, 1L);
        }
    }

    /** 剧本执行器：payload 可配——带 root_cause 对象 = confirmed 形态 */
    private static final class PayloadExecutor implements RcaTaskExecutor {
        private final String payloadTemplate;

        PayloadExecutor(String payloadTemplate) {
            this.payloadTemplate = payloadTemplate;
        }

        @Override
        public ExecutionResult execute(RcaTask task, RcaRun run, Incident incident,
                                       RcaAttempt attempt, Runnable heartbeat) {
            heartbeat.run();
            return ExecutionResult.success(new RcaTaskExecutor.AttemptArtifact(1,
                    ValidationStatus.STRUCTURE_VALIDATED, List.of(),
                    payloadTemplate.replace("$R", run.id().toString().substring(0, 8)),
                    "raw-" + run.id(), null, List.of(), null, null, "deepseek-v3",
                    null, null, null, true, null));
        }
    }

    private AlertInMemoryStores stores;
    private MutableClock clock;
    private AlertInboxProcessor intake;
    private RecordingCaseService caseService;
    private RcaWorker worker;

    private void setUpWith(String payloadTemplate) {
        stores = new AlertInMemoryStores();
        clock = new MutableClock();
        caseService = new RecordingCaseService();
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
                        SlaPolicy.defaults(), clock, nativeRouter,
                        new com.objwww.pr.control.alert.domain.classification.IncidentClassifier(),
                        stores.categories),
                org.springframework.transaction.support.TransactionOperations.withoutTransaction(),
                clock, "intake-owner", Duration.ofMinutes(2), Duration.ofSeconds(30),
                Duration.ofSeconds(10), Duration.ofSeconds(1));
        RcaRunOrchestrator orchestrator = new RcaRunOrchestrator(stores.tasks, stores.runs,
                stores.attempts, stores.reports, stores.incidents, stores.slots,
                stores.investigations, stores.toolCalls, notifier(), stores.cas,
                SlaPolicy.defaults(), clock, "rca", AlertMetrics.NOOP, nativeRouter,
                stores.winners, null, caseService);
        worker = new RcaWorker(stores.tasks, stores.runs, stores.attempts,
                stores.investigations, stores.incidents, stores.slots, stores.invocations,
                stores.toolLedger,
                java.util.Map.of(com.objwww.pr.control.alert.domain.model.RcaEngine.NATIVE,
                        new PayloadExecutor(payloadTemplate)),
                orchestrator,
                org.springframework.transaction.support.TransactionOperations.withoutTransaction(),
                clock, "worker-a", "rca", Duration.ofMinutes(5), Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofMinutes(1), Duration.ofMinutes(10), 2,
                org.mockito.Mockito.mock(
                        com.objwww.pr.control.alert.application.RunConfigSwitchService.class), null);
    }

    private ReportCompletedNotifier notifier() {
        return new ReportCompletedNotifier(stores.publications, stores.outboxes,
                List.of("test"), "am3-candidate-v1", 280);
    }

    private void deliver(String startsAt, String material) {
        stores.inbox.insert(com.objwww.pr.control.alert.support.TestFixtures.inboxRowOf(
                UUID.randomUUID(), com.objwww.pr.control.alert.support.TestFixtures.amGroup(
                        "g-checkout", 0, com.objwww.pr.control.alert.support.TestFixtures
                                .alertJson("HighErrorRate", "checkout", "warning", "firing",
                                        startsAt, material))));
        assertThat(intake.processOnce()).isEqualTo(AlertInboxProcessor.Outcome.ACCEPTED);
    }

    @Test
    @DisplayName("赢家开单：fingerprint=incidentKey、幂等键 case:report:{reportId}、未确认 P2")
    void winnerOpensCaseWithIncidentKeyFingerprint() {
        setUpWith("{\"schema_version\":\"1\",\"summary\":\"s$R\"}");
        deliver("2026-09-08T09:00:00Z", "材料一");
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);
        RcaRun run = stores.runs.all().get(0);
        assertThat(run.state()).isEqualTo(RcaRunState.SUCCEEDED);

        assertThat(caseService.drafts).hasSize(1);
        CaseDraft draft = caseService.drafts.get(0);
        String incidentKey = stores.incidents.findById(run.incidentId())
                .orElseThrow().incidentKey();
        assertThat(draft.fingerprint()).isEqualTo(incidentKey);
        UUID winnerReportId = stores.winners.findWinnerReportId(
                run.incidentId(), run.generation()).orElseThrow();
        assertThat(draft.idempotencyKey()).isEqualTo("case:report:" + winnerReportId);
        assertThat(draft.evidenceRefs()).containsExactly("report:" + winnerReportId);
        assertThat(draft.priority()).isEqualTo("P2");
        assertThat(draft.reasonCode()).isEqualTo("NO_CONFIRMED_ROOT_CAUSE");
        assertThat(draft.incidentType()).isEqualTo("unresolved");
        assertThat(draft.runId()).isEqualTo(run.id());
        assertThat(draft.observedGeneration()).isEqualTo(run.generation());
        assertThat(draft.ackDue()).isEqualTo(clock.now.plus(Duration.ofHours(8)));
        assertThat(draft.resolveDue()).isEqualTo(clock.now.plus(Duration.ofHours(72)));
    }

    @Test
    @DisplayName("同代双报告：恰赢家开一单，败者不触开单面")
    void loserReportNeverOpensCase() {
        setUpWith("{\"schema_version\":\"1\",\"summary\":\"s$R\"}");
        deliver("2026-09-08T09:00:00Z", "材料一");
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);
        deliver("2026-09-08T09:01:00Z", "材料二");
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);

        assertThat(stores.reports.all()).hasSize(2);
        assertThat(stores.publications.all()).hasSize(1);
        assertThat(caseService.drafts).hasSize(1);
        UUID winnerReportId = stores.winners.findWinnerReportId(
                stores.runs.all().get(0).incidentId(),
                stores.runs.all().get(0).generation()).orElseThrow();
        assertThat(caseService.drafts.get(0).idempotencyKey())
                .isEqualTo("case:report:" + winnerReportId);
    }

    @Test
    @DisplayName("confirmed 报告（root_cause 三元组齐）开 P1 单，SLA 2h/24h")
    void confirmedReportOpensP1Case() {
        setUpWith("{\"schema_version\":\"1\",\"summary\":\"s$R\","
                + "\"root_cause\":{\"component\":\"PaymentService\","
                + "\"fault_type\":\"downstream_rpc_failure\",\"reason_code\":\"GRPC_UNKNOWN\"}}");
        deliver("2026-09-08T09:00:00Z", "材料一");
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);

        assertThat(caseService.drafts).hasSize(1);
        CaseDraft draft = caseService.drafts.get(0);
        assertThat(draft.priority()).isEqualTo("P1");
        assertThat(draft.reasonCode()).isEqualTo("GRPC_UNKNOWN");
        assertThat(draft.incidentType()).isEqualTo("downstream_rpc_failure");
        assertThat(draft.ackDue()).isEqualTo(clock.now.plus(Duration.ofHours(2)));
        assertThat(draft.resolveDue()).isEqualTo(clock.now.plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("开单故障不回滚收尾：run SUCCEEDED、发布与通知照常落档")
    void caseOpenFailureDoesNotRollbackFinalize() {
        setUpWith("{\"schema_version\":\"1\",\"summary\":\"s$R\"}");
        caseService.failOpen = true;
        deliver("2026-09-08T09:00:00Z", "材料一");
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);

        RcaRun run = stores.runs.all().get(0);
        assertThat(run.state()).isEqualTo(RcaRunState.SUCCEEDED);
        assertThat(stores.publications.all()).hasSize(1);
        assertThat(stores.outboxes.all()).hasSize(1);
        assertThat(stores.winners.findWinnerReportId(run.incidentId(), run.generation()))
                .isPresent();
    }
}
