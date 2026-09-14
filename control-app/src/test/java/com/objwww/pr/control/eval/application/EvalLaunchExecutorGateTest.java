package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.GoldenScenarioRegistry;
import com.objwww.pr.control.eval.domain.model.EvalRunCommand;
import com.objwww.pr.control.eval.domain.repository.EvalPhaseEventSink;
import com.objwww.pr.control.eval.domain.repository.EvalRunCommandRepository;
import com.objwww.pr.control.eval.domain.repository.EvalRunRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PAGE-03 执行器领取后复验：能力闸门在 driver 装配/注入探针/run 落库之前拒绝
 * 不支持的计划（PRT-05 零副作用面）——E 模式命令即使漏网入队，也不会走进故障驱动。
 */
class EvalLaunchExecutorGateTest {

    private static final String REGISTRY = """
            registry_version: 1
            schema_version: 1
            lexicon_binding: "lex"
            scenarios:
              - scenario_id: S1
                name: paymentFailure=50%
                driver: FlagdScenarioDriver
                chaos_family: null
                target: payment
                expected_root_cause:
                  component: payment
                  fault_type: BUSINESS_ERROR_RATE
                  reason_code: PAYMENT_CHARGE_FAILURE
                expected_symptom_codes:
                  - checkout
                timing:
                  preheat_seconds: 1
                  hold_seconds: 1
                  max_firing_wait_seconds: 1
                  max_resolved_wait_seconds: 1
                  cleanup_timeout_seconds: 1
            """;

    /** 计数驱动器：激活次数必须恒 0（闸门在驱动触达前拒绝） */
    private static final class CountingDriver implements ScenarioDriver {
        int activations;

        @Override
        public ActivationReceipt activate(com.objwww.pr.control.eval.domain.GoldenCase golden,
                                          int roundNo) {
            activations++;
            return new ActivationReceipt(golden.scenarioId(), "d", 1, "alert");
        }

        @Override
        public RecoveryReceipt deactivate(com.objwww.pr.control.eval.domain.GoldenCase golden,
                                          ActivationReceipt receipt) {
            return new RecoveryReceipt(golden.scenarioId(), receipt.actionDigest(), 1, true, true,
                    List.of());
        }
    }

    private static EvalRunCommand launchCommand(String mode) {
        String payload = "{\"displayName\":\"n\",\"mode\":\"" + mode + "\","
                + "\"datasetVersion\":\"eval-ds-1\",\"model\":null,\"promptVersion\":null,"
                + "\"budgetMaxTokens\":null,\"maxConcurrency\":null,"
                + "\"deadlineSeconds\":null,\"roundsPerScenario\":null}";
        return EvalRunCommand.pending(UUID.randomUUID(), EvalRunCommand.Type.LAUNCH,
                UUID.randomUUID(), "key-" + mode, payload,
                Digest.sha256Of(payload).value(), "operator",
                Instant.parse("2026-09-14T00:00:00Z"));
    }

    private EvalLaunchExecutor executor(CountingDriver driver, EvalLaunchGate gate,
                                        EvalRunRepository evalRuns) {
        AlertProbe alertProbe = new AlertProbe() {
            @Override
            public boolean awaitAllFiring(String scenarioId, int maxWaitSeconds) {
                return false;
            }

            @Override
            public boolean awaitAllResolved(String scenarioId, int maxWaitSeconds) {
                return false;
            }

            @Override
            public boolean awaitSessionClosed(String scenarioId, int cleanupTimeoutSeconds) {
                return false;
            }

            @Override
            public Digest ruleDigest(String alertname) {
                return Digest.sha256Of(alertname);
            }
        };
        EvalPhaseEventSink sink = (runId, phase, at, workerId, detail) -> { };
        EvalRunCommandRepository commands = new EvalRunCommandRepository() {
            @Override public void insert(com.objwww.pr.control.eval.domain.model.EvalRunCommand c) { }
            @Override public Optional<com.objwww.pr.control.eval.domain.model.EvalRunCommand> findByKey(
                    com.objwww.pr.control.eval.domain.model.EvalRunCommand.Type t, String k) {
                return Optional.empty();
            }
            @Override public Optional<com.objwww.pr.control.eval.domain.model.EvalRunCommand> findLatestLaunch(
                    java.util.UUID runId) {
                return Optional.empty();
            }
            @Override public boolean cancelAccepted(java.util.UUID runId) {
                return false;
            }
            @Override public Optional<Instant> cancelRequestedAt(java.util.UUID runId) {
                return Optional.empty();
            }
            @Override public Optional<com.objwww.pr.control.eval.domain.model.EvalRunCommand> claimNextLaunch(
                    String w, Instant at) {
                return Optional.empty();
            }
            @Override public boolean finish(java.util.UUID id,
                    com.objwww.pr.control.eval.domain.model.EvalRunCommand.State s, Instant at) {
                return true;
            }
            @Override public List<com.objwww.pr.control.eval.domain.model.EvalRunCommand> findOrphanedClaims(
                    Instant before) {
                return List.of();
            }
            @Override public boolean requeue(java.util.UUID id) {
                return false;
            }
        };
        EvalRunMetadata metadata = new EvalRunMetadata(1, "eval-ds-1",
                Digest.sha256Of("registry"), 1, "m", "p", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), null, null, null, null, null, "fp",
                Digest.sha256Of("rules"), "drv-1", "grader-1");
        // scorer 恒不应被触达（闸门在评分链之前拒绝）；用内存桩装配满足非空契约
        com.objwww.pr.control.alert.support.AlertInMemoryStores.Runs runs =
                new com.objwww.pr.control.alert.support.AlertInMemoryStores.Runs();
        SingleCaseScorer scorer = new SingleCaseScorer(runs,
                new com.objwww.pr.control.alert.support.AlertInMemoryStores.Reports(),
                new com.objwww.pr.control.alert.support.AlertInMemoryStores.Investigations(),
                new com.objwww.pr.control.alert.support.AlertInMemoryStores.ToolCalls(),
                new ScenarioEvaluator(
                        com.objwww.pr.control.eval.domain.SynonymLexicon.load("""
                                lexicon_version: 1
                                components:
                                  - code: payment
                                    synonyms: [payment-svc]
                                fault_types:
                                  - code: BUSINESS_ERROR_RATE
                                    synonyms: []
                                reason_codes:
                                  - code: PAYMENT_CHARGE_FAILURE
                                    fault_type: BUSINESS_ERROR_RATE
                                    synonyms: []
                                """)));
        return new EvalLaunchExecutor(GoldenScenarioRegistry.load(REGISTRY),
                Map.of("FlagdScenarioDriver", driver), alertProbe,
                (alertname, maxWaitSeconds) -> true,
                (golden, roundNo, activatedAt, timeoutSeconds) -> Optional.empty(),
                scorer, evalRuns, new BaselineReportGenerator(), sink, commands, metadata,
                2, new EvalBatchRunner.EvalClock() {
                    @Override public Instant now() {
                        return Instant.parse("2026-09-14T00:00:00Z");
                    }

                    @Override public void sleepSeconds(long s) { }
                }, "worker-gate-test", gate);
    }

    @Test
    @DisplayName("E 模式漏网命令：领取后闸门拒绝，零激活/零 run 落库（PRT-05 副作用面）")
    void gateRejectsModeBeforeAnySideEffect() {
        CountingDriver driver = new CountingDriver();
        java.util.List<Object> inserts = new java.util.ArrayList<>();
        EvalRunRepository evalRuns = new EvalRunRepository() {
            @Override public void insertRunning(com.objwww.pr.control.eval.domain.EvalRun r) {
                inserts.add(r);
            }

            @Override public boolean finalizeOnce(com.objwww.pr.control.eval.domain.EvalRun r) {
                inserts.add(r);
                return true;
            }

            @Override public boolean applyLaunchIdentity(java.util.UUID id, String d, String m,
                    String p) {
                inserts.add(id);
                return true;
            }

            @Override public boolean updateRecoveryState(java.util.UUID id, String s) {
                return false;
            }

            @Override public boolean insertCaseResult(
                    com.objwww.pr.control.eval.domain.EvalCaseResult r) {
                inserts.add(r);
                return true;
            }

            @Override public Optional<com.objwww.pr.control.eval.domain.EvalRun> findById(
                    java.util.UUID id) {
                return Optional.empty();
            }

            @Override public List<com.objwww.pr.control.eval.domain.EvalCaseResult> findCasesByRunId(
                    java.util.UUID id) {
                return List.of();
            }
        };
        EvalLaunchExecutor executor = executor(driver,
                EvalLaunchGate.closed(Set.of("L"), "eval-ds-1", 1, 10), evalRuns);

        assertThatThrownBy(() -> executor.execute(launchCommand("E")))
                .isInstanceOf(EvalLaunchGate.EvalLaunchUnsupportedException.class)
                .hasMessageContaining("E");

        assertThat(driver.activations).as("零驱动激活").isZero();
        assertThat(inserts).as("零 run/案例落库").isEmpty();
    }
}
