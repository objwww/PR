package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.GoldenScenarioRegistry;
import com.objwww.pr.control.eval.domain.SynonymLexicon;
import com.objwww.pr.control.eval.domain.repository.EvalRunRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * FUP-01 CLI 入口收口（FCT-05/06）：mode 显式 switch——未知值（误拼/空白/null）
 * 配置校验失败非零退出、绝不默认 once；mode=once 闭面期（EvalLaunchGate
 * launchEnabled=false）直接禁止，runBatch 零调用、非零退出带明确原因。
 * 假 runner 计数 + 哨兵 Error 截断在进入执行面前；ExitHook 记录型假件避免
 * 真实 System.exit 杀测试 JVM。假件全内存，不打真网。
 */
class EvalRunnerMainTest {

    /** 进入假 runner 即抛的哨兵（截断于任何副作用之前；探针 SafeClosedBoundaryAudit 同式） */
    private static final class StopBeforeSideEffect extends Error {
    }

    /** 计数假 runner：runBatch 被调即计数并抛哨兵（证明到达/未到达执行面） */
    private static final class FakeRunner extends EvalBatchRunner {
        int runBatchCalls;

        FakeRunner() {
            super(GoldenScenarioRegistry.load("""
                            registry_version: 1
                            schema_version: 1
                            lexicon_binding: "synonym-lexicon-v1.yml (lexicon_version: 1)"
                            scenarios: []
                            """),
                    Map.of(), new StubProbe(), (alertname, maxWaitSeconds) -> true,
                    (golden, roundNo, activatedAt, timeoutSeconds) -> Optional.empty(),
                    new SingleCaseScorer(new AlertInMemoryStores.Runs(),
                            new AlertInMemoryStores.Reports(),
                            new AlertInMemoryStores.Investigations(),
                            new AlertInMemoryStores.ToolCalls(),
                            new ScenarioEvaluator(SynonymLexicon.load("""
                                    lexicon_version: 1
                                    components: []
                                    fault_types: []
                                    reason_codes: []
                                    """))),
                    new NoopEvalRuns(), new BaselineReportGenerator(), metadata(), 2,
                    new EvalClock() {
                        @Override
                        public Instant now() {
                            return Instant.parse("2026-09-14T00:00:00Z");
                        }

                        @Override
                        public void sleepSeconds(long seconds) {
                        }
                    });
        }

        @Override
        public BatchResult runBatch() {
            runBatchCalls++;
            throw new StopBeforeSideEffect();
        }
    }

    private static final class StubProbe implements AlertProbe {
        @Override
        public boolean awaitAllFiring(String scenarioId, int maxWaitSeconds) {
            return true;
        }

        @Override
        public boolean awaitAllResolved(String scenarioId, int maxWaitSeconds) {
            return true;
        }

        @Override
        public boolean awaitSessionClosed(String scenarioId, int cleanupTimeoutSeconds) {
            return true;
        }

        @Override
        public Digest ruleDigest(String alertname) {
            return Digest.sha256Of("rules:" + alertname);
        }
    }

    /** 只读面空仓储（假 runner 不触库；实现面随 EvalRunRepository 演进兜底抛错） */
    private static final class NoopEvalRuns implements EvalRunRepository {
        @Override
        public void insertRunning(EvalRun run) {
            throw new UnsupportedOperationException("fake runner 不落库");
        }

        @Override
        public boolean finalizeOnce(EvalRun terminal) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean applyLaunchIdentity(UUID runId, String displayName, String mode,
                                           String launchPlanJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean updateRecoveryState(UUID runId, String recoveryState) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean insertCaseResult(EvalCaseResult result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<EvalRun> findById(UUID runId) {
            return Optional.empty();
        }

        @Override
        public List<EvalCaseResult> findCasesByRunId(UUID runId) {
            return List.of();
        }
    }

    /** 记录型进程出口（断言非零退出码；不杀 JVM） */
    private static final class RecordingExit implements EvalRunnerMain.ExitHook {
        final List<Integer> codes = new ArrayList<>();

        @Override
        public void exit(int code) {
            codes.add(code);
        }
    }

    private static EvalRunMetadata metadata() {
        return new EvalRunMetadata(1, "eval-ds-1", Digest.sha256Of("registry"), 1,
                "m", "p", Digest.sha256Of("prompt"), Digest.sha256Of("tools"),
                null, null, null, null, null, "fp",
                Digest.sha256Of("rules"), "driver-v1", "grader-v1");
    }

    private static EvalLaunchGate closed() {
        return EvalLaunchGate.launchDisabled(Set.of("L"), "eval-ds-1", 1, 10);
    }

    private static EvalRunnerMain main(FakeRunner runner, EvalLaunchGate gate,
                                       String mode, RecordingExit exit) {
        // ledger/worker/drillWorker 在 once 闭面与未知 mode 路径均不可达（传 null 作
        // 绊线：误触即 NPE 使测试变红）
        return new EvalRunnerMain(runner, null, null, null, gate, mode, exit);
    }

    @Test
    @DisplayName("FCT-05：mode=once + 评测闭面 → runBatch/activate 零调用，"
            + "非零退出（EXIT_ONCE_DISABLED）带明确原因")
    void onceBlockedWhenLaunchDisabled() {
        FakeRunner runner = new FakeRunner();
        RecordingExit exit = new RecordingExit();
        main(runner, closed(), "once", exit).run(null);
        assertThat(runner.runBatchCalls).isZero();
        assertThat(exit.codes).containsExactly(EvalRunnerMain.EXIT_ONCE_DISABLED);
    }

    @Test
    @DisplayName("FCT-06：mode=workre/空白/null 等未知值 → 配置校验失败非零退出，"
            + "绝不默认落到一次性执行（runBatch 零调用）")
    void unknownModeFailsStartupNeverDefaultsToOnce() {
        for (String mode : new String[]{"workre", "", "  ", "ONCE", null}) {
            FakeRunner runner = new FakeRunner();
            RecordingExit exit = new RecordingExit();
            // 能力位开放也不救未知 mode——mode 校验先于一切执行面
            main(runner, EvalLaunchGate.closed(Set.of("L"), "eval-ds-1", 1, 10),
                    mode, exit).run(null);
            assertThat(runner.runBatchCalls).as("mode=%s 不得触发 runBatch", mode)
                    .isZero();
            assertThat(exit.codes).as("mode=%s 必须以配置校验失败退出", mode)
                    .containsExactly(EvalRunnerMain.EXIT_UNKNOWN_MODE);
        }
    }

    @Test
    @DisplayName("对照面：mode=once + 能力开放 → 真实进入 runBatch（假 runner 哨兵"
            + "截断于副作用前），证明闸门只挡闭面不挡开放")
    void onceRunsWhenLaunchEnabled() {
        FakeRunner runner = new FakeRunner();
        RecordingExit exit = new RecordingExit();
        Throwable thrown = catchThrowable(() -> main(runner,
                EvalLaunchGate.closed(Set.of("L"), "eval-ds-1", 1, 10),
                "once", exit).run(null));
        assertThat(thrown).isInstanceOf(StopBeforeSideEffect.class);
        assertThat(runner.runBatchCalls).isEqualTo(1);
        assertThat(exit.codes).isEmpty();
    }
}
