package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.model.ExecutionStatus;
import com.objwww.pr.control.alert.domain.model.InvestigationResult;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository;
import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.ScenarioMetrics;
import com.objwww.pr.control.eval.domain.litellm.LiteLlmAdminPort;
import com.objwww.pr.control.eval.domain.litellm.SpendRecord;
import com.objwww.pr.control.eval.domain.litellm.UsageLedgerReconciler;
import com.objwww.pr.control.eval.domain.litellm.UsageLedgerReconciler.AttemptUsage;
import com.objwww.pr.control.eval.domain.litellm.UsageLedgerReconciler.LedgerBasis;
import com.objwww.pr.control.eval.domain.litellm.UsageLedgerReconciler.LedgerState;
import com.objwww.pr.control.eval.domain.repository.EvalRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-25 出账口编排：未配置 → chain③ 降级；查询失败 → 降级不抛；
 * usage_json 解析（含残缺按 usage_missing 落账）。
 */
class UsageLedgerServiceTest {

    private static final UUID EVAL_RUN = UUID.randomUUID();
    private static final UUID RCA_RUN = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();

    @Test
    @DisplayName("未配置 litellm：诚实降级 NONE/UNMATCHED/BEST_EFFORT，不伪造")
    void degradeWhenNotConfigured() {
        UsageLedgerService service = new UsageLedgerService(
                fakeEvalRuns(), fakeInvestigations("{\"prompt_tokens\":100,\"completion_tokens\":20}"),
                null, null, 0);

        var ledger = service.reconcileEvalRun(EVAL_RUN);

        assertThat(ledger.basis()).isEqualTo(LedgerBasis.NONE);
        assertThat(ledger.state()).isEqualTo(LedgerState.UNMATCHED);
        assertThat(ledger.bestEffort()).isTrue();
        assertThat(ledger.attempts()).hasSize(1);
    }

    @Test
    @DisplayName("proxy 行与账本对上：key 级聚合 MATCHED 端到端")
    void matchedEndToEndThroughService() {
        List<SpendRecord> rows = List.of(new SpendRecord("req-1", "deepseek-v3",
                new BigDecimal("0.002"), 100, 20, "success", "eval-run-x", null, null));
        LiteLlmAdminPort port = new LiteLlmAdminPort() {
            @Override
            public String mintRunKey(String keyAlias, BigDecimal maxBudgetUsd) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<SpendRecord> spendLogs() {
                return rows;
            }
        };
        UsageLedgerService service = new UsageLedgerService(
                fakeEvalRuns(), fakeInvestigations("{\"prompt_tokens\":100,\"completion_tokens\":20}"),
                port, "eval-run-x", 0);

        var ledger = service.reconcileEvalRun(EVAL_RUN);

        assertThat(ledger.basis()).isEqualTo(LedgerBasis.VIRTUAL_KEY);
        assertThat(ledger.state()).isEqualTo(LedgerState.MATCHED);
    }

    @Test
    @DisplayName("proxy 查询失败：降级 UNMATCHED 且不抛（批件终态不受影响）")
    void degradeWhenProxyQueryFails() {
        LiteLlmAdminPort failingPort = new LiteLlmAdminPort() {
            @Override
            public String mintRunKey(String keyAlias, BigDecimal maxBudgetUsd) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<SpendRecord> spendLogs() {
                throw new IllegalStateException("LiteLLM /spend/logs 调用失败: HTTP 500");
            }
        };
        UsageLedgerService service = new UsageLedgerService(
                fakeEvalRuns(), fakeInvestigations("{\"prompt_tokens\":100,\"completion_tokens\":20}"),
                failingPort, "eval-run-x", 0);

        var ledger = service.reconcileEvalRun(EVAL_RUN);

        assertThat(ledger.state()).isEqualTo(LedgerState.UNMATCHED);
        assertThat(ledger.bestEffort()).isTrue();
    }

    @Test
    @DisplayName("usage_json 残缺按 usage_missing 落账；NULL 同样")
    void malformedAndNullUsageFallBackToMissing() {
        UsageLedgerService service = new UsageLedgerService(
                fakeEvalRuns(), fakeInvestigations("not-json{"), null, null, 0);

        var ledger = service.reconcileEvalRun(EVAL_RUN);

        assertThat(ledger.attempts().get(0).attemptId()).isEqualTo(ATTEMPT);
        assertThat(ledger.attempts().get(0).expectedPrompt()).isNull();
        assertThat(ledger.attempts().get(0).expectedCompletion()).isNull();
    }

    @Test
    @DisplayName("usage_json 字段残缺（非整型）同样按缺失")
    void partialUsageJsonFallsBackToMissing() {
        UsageLedgerService service = new UsageLedgerService(
                fakeEvalRuns(), fakeInvestigations("{\"prompt_tokens\":\"x\"}"), null, null, 0);

        var ledger = service.reconcileEvalRun(EVAL_RUN);

        assertThat(ledger.attempts().get(0).expectedPrompt()).isNull();
        assertThat(ledger.attempts().get(0).expectedCompletion()).isNull();
    }

    // ---------------- fakes ----------------

    private static EvalRunRepository fakeEvalRuns() {
        return new EvalRunRepository() {
            @Override
            public void insertRunning(com.objwww.pr.control.eval.domain.EvalRun running) {
            }

            @Override
            public boolean finalizeOnce(com.objwww.pr.control.eval.domain.EvalRun terminal) {
                return false;
            }

            @Override
            public boolean applyLaunchIdentity(UUID runId, String displayName, String mode,
                                               String launchPlanJson) {
                return false;
            }

            @Override
            public boolean updateRecoveryState(UUID runId, String recoveryState) {
                return false;
            }

            @Override
            public boolean insertCaseResult(EvalCaseResult result) {
                return false;
            }

            @Override
            public Optional<com.objwww.pr.control.eval.domain.EvalRun> findById(UUID runId) {
                return Optional.empty();
            }

            @Override
            public List<EvalCaseResult> findCasesByRunId(UUID runId) {
                return List.of(new EvalCaseResult(UUID.randomUUID(), EVAL_RUN, "S1", 1,
                        "final-validated-report-v1", RCA_RUN, ATTEMPT, UUID.randomUUID(),
                        ScenarioMetrics.ScoringVerdict.DECIDABLE, true,
                        new TypedRootCause("payment", "dependency_down", "pool_exhausted"),
                        new TypedRootCause("payment", "dependency_down", "pool_exhausted"),
                        List.of("checkout_failed"), List.of("checkout_failed"),
                        1, 0, 0, 5L, false, null));
            }
        };
    }

    private static InvestigationResultRepository fakeInvestigations(String usageJson) {
        return new InvestigationResultRepository() {
            @Override
            public InvestigationResult insertStartedIfAbsent(InvestigationResult started) {
                return started;
            }

            @Override
            public boolean finishTerminal(InvestigationResult terminal) {
                return true;
            }

            @Override
            public List<InvestigationResult> findHangingStarted(Instant olderThan) {
                return List.of();
            }

            @Override
            public Optional<InvestigationResult> findByAttemptId(UUID attemptId) {
                return Optional.empty();
            }

            @Override
            public List<InvestigationResult> findByRunId(UUID runId) {
                return List.of(new InvestigationResult(UUID.randomUUID(), ATTEMPT, RCA_RUN,
                        1, 2,
                        ExecutionStatus.SUCCEEDED,
                        ValidationStatus.STRUCTURE_VALIDATED,
                        List.of(), "{}", null, null, null, "deepseek-v3",
                        usageJson, Instant.now(), Instant.now()));
            }
        };
    }
}
