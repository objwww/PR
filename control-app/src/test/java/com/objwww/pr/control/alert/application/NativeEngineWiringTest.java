package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaAttemptStatus;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.service.AlertIdentityFactory;
import com.objwww.pr.control.alert.domain.service.DeferredPolicy;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.alert.support.TestFixtures;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.release.application.CanaryRouter;
import com.objwww.pr.control.release.domain.model.CanaryDecision;
import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.control.release.domain.repository.CanaryDecisionLogRepository;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6-01 Native 执行面接线（1% Canary）——铸造点与分派面 UT：
 * <ul>
 *   <li>task_key 按 routing.engine 选择（NATIVE run 铸 NATIVE_INVESTIGATE，
 *       HOLMES 保持 HOLMES_INVESTIGATE），铸造点 = IncidentProjector（INITIAL/FIRING
 *       重查）+ RcaRunOrchestrator（finishTask 材料变化 RERUN）；</li>
 *   <li>RcaWorker 按 Map&lt;RcaEngine, RcaTaskExecutor&gt; 分派；未知 engine fail-closed
 *       （task DEAD + run FAILED，不误入 HOLMES 执行器）。</li>
 * </ul>
 * 并发/真 PG 语义归 195 契约 IT；本类用 InMemory + withoutTransaction。
 */
class NativeEngineWiringTest {

    private static final class MutableClock implements AlertClock {
        volatile Instant now = Instant.parse("2026-09-08T10:00:00Z");

        @Override
        public Instant now() {
            return now;
        }
    }

    /** 剧本执行器：按序出结果；记录调用次数（分派面断言用） */
    private static final class ScriptedExecutor implements RcaTaskExecutor {
        final Queue<ExecutionResult> script = new ArrayDeque<>();
        int calls;

        @Override
        public ExecutionResult execute(RcaTask task, RcaRun run, Incident incident,
                                       RcaAttempt attempt, Runnable heartbeat) {
            calls++;
            ExecutionResult polled = script.poll();
            return polled != null ? polled
                    : RcaTaskExecutor.ExecutionResult.retryable("SCRIPT_EMPTY", "剧本耗尽");
        }

        void succeedNext() {
            script.add(RcaTaskExecutor.ExecutionResult.success(validatedArtifact()));
        }

        private static RcaTaskExecutor.AttemptArtifact validatedArtifact() {
            return new RcaTaskExecutor.AttemptArtifact(1, com.objwww.pr.control.alert.domain.model.ValidationStatus.STRUCTURE_VALIDATED,
                    List.of(), "{\"schema_version\":\"1\"}", "raw", null, List.of(),
                    null, null, "deepseek-v3", null, null, null, true, null);
        }
    }

    private AlertInMemoryStores stores;
    private MutableClock clock;
    private WiringBundles bundles;
    private WiringDecisions decisions;
    private CanaryRouter nativeRouter;
    private AlertInboxProcessor intake;

    @BeforeEach
    void setUp() {
        stores = new AlertInMemoryStores();
        clock = new MutableClock();
        bundles = new WiringBundles();
        decisions = new WiringDecisions();
        // percent=100：全部桶位 NATIVE（BUCKETED_NATIVE），无需白名单即确定性 NATIVE
        bundles.publish(canaryBundle(100, List.of(), 100));
        nativeRouter = new CanaryRouter(bundles, decisions, true, clock::now);
        IncidentProjector projector = new IncidentProjector(stores.events, stores.incidents,
                stores.runs, stores.tasks, new AlertIdentityFactory(),
                new DeferredPolicy(1000), SlaPolicy.defaults(), clock, nativeRouter);
        intake = new AlertInboxProcessor(stores.inbox, projector,
                TransactionOperations.withoutTransaction(), clock, "intake-owner",
                Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(1));
    }

    private RcaRunOrchestrator newOrchestrator() {
        return new RcaRunOrchestrator(stores.tasks, stores.runs, stores.attempts,
                stores.reports, stores.incidents, stores.slots, stores.investigations,
                stores.toolCalls, notifier(), stores.cas, SlaPolicy.defaults(), clock, "rca",
                AlertMetrics.NOOP, nativeRouter);
    }

    private ReportCompletedNotifier notifier() {
        return new ReportCompletedNotifier(stores.publications, stores.outboxes,
                List.of("test"), "am3-candidate-v1", 280);
    }

    /** 映射表构造（M6-01 分派面）；holmesOnly = 仅 HOLMES 绑定 */
    private RcaWorker newWorker(Map<RcaEngine, RcaTaskExecutor> executors, String owner) {
        return new RcaWorker(stores.tasks, stores.runs, stores.attempts, stores.investigations,
                stores.incidents, stores.slots, stores.invocations, executors, newOrchestrator(),
                TransactionOperations.withoutTransaction(), clock, owner, "rca",
                Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofMinutes(1), Duration.ofMinutes(10), 2);
    }

    private void deliver(String service, String severity, String status,
                         String startsAt, String summary) {
        stores.inbox.insert(TestFixtures.inboxRowOf(UUID.randomUUID(), TestFixtures.amGroup(
                "g-" + service, 0,
                TestFixtures.alertJson("HighErrorRate", service, severity, status,
                        startsAt, summary))));
        assertThat(intake.processOnce()).isEqualTo(AlertInboxProcessor.Outcome.ACCEPTED);
    }

    private void deliverFiring(String service, String summary) {
        deliver(service, "warning", "firing", "2026-09-08T09:00:00Z", summary);
    }

    // ------------------------------------------------------------------ task_key 映射

    @Test
    @DisplayName("taskKeyFor：NATIVE→NATIVE_INVESTIGATE，HOLMES→HOLMES_INVESTIGATE")
    void taskKeyFollowsEngine() {
        assertThat(RcaTask.taskKeyFor(RcaEngine.NATIVE)).isEqualTo("NATIVE_INVESTIGATE");
        assertThat(RcaTask.taskKeyFor(RcaEngine.HOLMES)).isEqualTo(RcaTask.HOLMES_INVESTIGATE);
    }

    // ------------------------------------------------------------------ 铸造点（INITIAL / RERUN）

    @Test
    @DisplayName("NATIVE 路由铸造：task key=NATIVE_INVESTIGATE + 路由四列随行落库")
    void nativeRoutingCastsNativeTaskKey() {
        deliverFiring("checkout", "材料一");

        assertThat(stores.tasks.all()).hasSize(1);
        assertThat(stores.tasks.all().get(0).taskKey()).isEqualTo("NATIVE_INVESTIGATE");

        RcaRun run = stores.runs.all().get(0);
        RcaRunRepository.RoutingView routing = stores.runs.findRoutingById(run.id()).orElseThrow();
        assertThat(routing.engine()).isEqualTo(RcaEngine.NATIVE);
        assertThat(routing.configDigest()).isNotNull();
        assertThat(routing.stickinessKey()).isNotBlank();
        assertThat(routing.bucket()).isNotNull();
        assertThat(decisions.rows).hasSize(1);
        assertThat(decisions.rows.get(0).decision())
                .isEqualTo(CanaryDecision.BUCKETED_NATIVE.name());
    }

    @Test
    @DisplayName("HOLMES 路由铸造：task key 保持 HOLMES_INVESTIGATE（回归锚）")
    void holmesRoutingKeepsHolmesTaskKey() {
        bundles.publish(canaryBundle(0, List.of(), 100));   // percent=0：全桶 HOLMES

        deliverFiring("checkout", "材料一");

        assertThat(stores.tasks.all().get(0).taskKey()).isEqualTo(RcaTask.HOLMES_INVESTIGATE);
        assertThat(stores.runs.findRoutingById(stores.runs.all().get(0).id())
                .orElseThrow().engine()).isEqualTo(RcaEngine.HOLMES);
    }

    @Test
    @DisplayName("finishTask 材料变化 RERUN：新 run 照路由引擎铸 NATIVE_INVESTIGATE")
    void rerunCastingFollowsEngine() {
        ScriptedExecutor executor = new ScriptedExecutor();
        RcaWorker worker = newWorker(Map.of(RcaEngine.NATIVE, executor), "worker-a");

        deliverFiring("checkout", "材料一");
        Optional<RcaWorker.ClaimedWork> work = worker.claimWork();
        assertThat(work).isPresent();
        // 调查期间材料变化 → pending（startsAt 不同 = 不同 payloadHash，非重复通知）
        deliver("checkout", "warning", "firing", "2026-09-08T09:01:00Z", "材料二");

        RcaAttempt attempt = new RcaAttempt(UUID.randomUUID(), work.get().task().id(),
                work.get().task().attemptCount(), work.get().task().leaseEpoch(), "worker-a",
                RcaAttemptStatus.STARTED, null, null, null, clock.now, null, null);
        stores.attempts.insert(attempt);
        RcaRunOrchestrator.FinishOutcome outcome = newOrchestrator().finishTask(
                work.get().task(), "worker-a", work.get().slotNo(), work.get().slotEpoch(),
                RcaTaskExecutor.ExecutionResult.success(ScriptedExecutor.validatedArtifact()),
                attempt);
        assertThat(outcome).isEqualTo(RcaRunOrchestrator.FinishOutcome.RERUN_CAST);

        assertThat(stores.tasks.all()).hasSize(2);
        assertThat(stores.tasks.all().get(1).taskKey()).isEqualTo("NATIVE_INVESTIGATE");
        assertThat(stores.runs.findRoutingById(stores.tasks.all().get(1).runId())
                .orElseThrow().engine()).isEqualTo(RcaEngine.NATIVE);
    }

    // ------------------------------------------------------------------ worker 分派面

    @Test
    @DisplayName("Map 分派：NATIVE run 的 task 由 NATIVE 执行器执行，HOLMES 执行器零触达")
    void dispatchByEngine() {
        ScriptedExecutor holmes = new ScriptedExecutor();
        ScriptedExecutor nativeExec = new ScriptedExecutor();
        RcaWorker worker = newWorker(Map.of(RcaEngine.HOLMES, holmes,
                RcaEngine.NATIVE, nativeExec), "worker-a");

        deliverFiring("checkout", "材料一");
        nativeExec.succeedNext();
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);

        assertThat(nativeExec.calls).isEqualTo(1);
        assertThat(holmes.calls).isZero();
        assertThat(stores.runs.all().get(0).state()).isEqualTo(RcaRunState.SUCCEEDED);
        assertThat(stores.tasks.all().get(0).state()).isEqualTo(RcaTaskState.DONE);
        assertThat(stores.reports.all()).hasSize(1);
    }

    @Test
    @DisplayName("未知 engine fail-closed：无绑定执行器 → task DEAD + run FAILED，不误入 HOLMES")
    void unknownEngineFailsClosed() {
        ScriptedExecutor holmes = new ScriptedExecutor();
        RcaWorker worker = newWorker(Map.of(RcaEngine.HOLMES, holmes), "worker-a");

        deliverFiring("checkout", "材料一");
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);

        assertThat(holmes.calls).isZero();
        assertThat(stores.tasks.all().get(0).state()).isEqualTo(RcaTaskState.DEAD);
        assertThat(stores.runs.all().get(0).state()).isEqualTo(RcaRunState.FAILED);
    }

    // ------------------------------------------------------------------ 迷你认账面（CanaryRouterTest 同构）

    private static Map<String, Object> canaryBundle(Integer percent, List<String> whitelist,
                                                    Integer maxNativeRuns) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policy_version", "policy-2026-09");
        Map<String, Object> canary = new LinkedHashMap<>();
        if (percent != null) {
            canary.put("percent", percent);
        }
        if (whitelist != null) {
            canary.put("whitelist", whitelist);
        }
        if (maxNativeRuns != null) {
            canary.put("max_native_runs", maxNativeRuns);
        }
        content.put("canary", canary);
        return content;
    }

    static final class WiringBundles implements ConfigBundleRepository {
        final List<ConfigBundle> rows = new ArrayList<>();
        Digest active;

        void publish(Map<String, Object> content) {
            ConfigBundle bundle = ConfigBundle.of(content, "op", Instant.parse("2026-09-08T00:00:00Z"));
            rows.add(bundle);
            active = bundle.bundleDigest();
        }

        @Override
        public long nextRevision() {
            return rows.size() + 1;
        }

        @Override
        public boolean insert(ConfigBundle bundle) {
            return rows.add(bundle);
        }

        @Override
        public java.util.Optional<ConfigBundle> findByDigest(Digest digest) {
            return rows.stream().filter(b -> b.bundleDigest().equals(digest)).findFirst();
        }

        @Override
        public java.util.Optional<Digest> activeDigest() {
            return java.util.Optional.ofNullable(active);
        }

        @Override
        public java.util.Optional<ConfigBundleRepository.ActivePointer> findActivePointer() {
            return active == null ? java.util.Optional.empty()
                    : java.util.Optional.of(new ConfigBundleRepository.ActivePointer(active, 1L,
                    Instant.parse("2026-09-08T00:00:00Z")));
        }

        @Override
        public boolean activate(Digest toDigest, Digest expectedCurrent, String by, Instant at) {
            active = toDigest;
            return true;
        }
    }

    static final class WiringDecisions implements CanaryDecisionLogRepository {
        final List<CanaryDecisionLogRepository.DecisionRow> rows = new ArrayList<>();

        @Override
        public void append(CanaryDecisionLogRepository.DecisionRow row) {
            rows.add(row);
        }

        @Override
        public long countNativeDecisions() {
            return rows.stream().map(CanaryDecisionLogRepository.DecisionRow::decision)
                    .filter(d -> d.equals(CanaryDecision.WHITELISTED.name())
                            || d.equals(CanaryDecision.BUCKETED_NATIVE.name()))
                    .count();
        }
    }
}
