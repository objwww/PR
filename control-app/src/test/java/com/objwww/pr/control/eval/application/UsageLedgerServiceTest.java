package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.ScenarioMetrics;
import com.objwww.pr.control.eval.domain.litellm.LiteLlmAdminPort;
import com.objwww.pr.control.eval.domain.litellm.SpendRecord;
import com.objwww.pr.control.eval.domain.litellm.UsageLedgerReconciler.LedgerBasis;
import com.objwww.pr.control.eval.domain.litellm.UsageLedgerReconciler.LedgerState;
import com.objwww.pr.control.eval.domain.repository.EvalRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-25 出账口编排（R6 重写：账本侧改读 rca_model_call 逐调用行）：
 * 未配置 → chain③ 降级；查询失败 → 降级不抛；逐调用行按 attempt 聚合（EU18
 * 重试逐次计后归并，§6.6 冻结的 attempt 级对账单位）；UNKNOWN/usage_missing
 * 行如实 taint 整 attempt（不猜零不猜全）；FAILED 不 taint 也不贡献。
 */
class UsageLedgerServiceTest {

    private static final UUID EVAL_RUN = UUID.randomUUID();
    private static final UUID RCA_RUN = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();

    @Test
    @DisplayName("未配置 litellm：诚实降级 NONE/UNMATCHED/BEST_EFFORT，不伪造")
    void degradeWhenNotConfigured() {
        UsageLedgerService service = new UsageLedgerService(
                fakeEvalRuns(), ledgerOf(successRow(ATTEMPT, 100, 20)), null, null, 0);

        var ledger = service.reconcileEvalRun(EVAL_RUN);

        assertThat(ledger.basis()).isEqualTo(LedgerBasis.NONE);
        assertThat(ledger.state()).isEqualTo(LedgerState.UNMATCHED);
        assertThat(ledger.bestEffort()).isTrue();
        assertThat(ledger.attempts()).hasSize(1);
        assertThat(ledger.attempts().get(0).expectedPrompt()).isEqualTo(100);
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
                fakeEvalRuns(), ledgerOf(successRow(ATTEMPT, 100, 20)), port, "eval-run-x", 0);

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
                fakeEvalRuns(), ledgerOf(successRow(ATTEMPT, 100, 20)),
                failingPort, "eval-run-x", 0);

        var ledger = service.reconcileEvalRun(EVAL_RUN);

        assertThat(ledger.state()).isEqualTo(LedgerState.UNMATCHED);
        assertThat(ledger.bestEffort()).isTrue();
    }

    @Test
    @DisplayName("EU18：同 attempt 两次成功调用（重试/多步）逐行入账后归并为一个期望总量")
    void perCallRowsAggregatePerAttempt() {
        UsageLedgerService service = new UsageLedgerService(fakeEvalRuns(),
                ledgerOf(successRow(ATTEMPT, 100, 20), successRow(ATTEMPT, 30, 5)),
                null, null, 0);

        var ledger = service.reconcileEvalRun(EVAL_RUN);

        assertThat(ledger.attempts()).as("attempt 粒度不变（§6.6 冻结）").hasSize(1);
        assertThat(ledger.attempts().get(0).attemptId()).isEqualTo(ATTEMPT);
        assertThat(ledger.expectedPromptTokens()).as("重试逐次累加").isEqualTo(130);
        assertThat(ledger.expectedCompletionTokens()).isEqualTo(25);
    }

    @Test
    @DisplayName("SUCCESS+usage_missing 行 taint 整 attempt：期望总量不可知，不猜部分和")
    void usageMissingRowTaintsWholeAttempt() {
        RcaModelCallLedger.CallUsage missing = new RcaModelCallLedger.CallUsage(
                ATTEMPT, "primary", 1, 1, null, null, null, null, null, null,
                true, "SUCCESS");
        UsageLedgerService service = new UsageLedgerService(fakeEvalRuns(),
                ledgerOf(successRow(ATTEMPT, 100, 20), missing), null, null, 0);

        var ledger = service.reconcileEvalRun(EVAL_RUN);

        assertThat(ledger.attempts().get(0).expectedPrompt()).as("不猜 100").isNull();
        assertThat(ledger.attempts().get(0).expectedCompletion()).isNull();
    }

    @Test
    @DisplayName("UNKNOWN 行 taint：是否已执行不确定 → 期望总量不可知")
    void unknownRowTaintsWholeAttempt() {
        RcaModelCallLedger.CallUsage unknown = new RcaModelCallLedger.CallUsage(
                ATTEMPT, "primary", 1, 1, null, null, null, null, null, null,
                false, "UNKNOWN");
        UsageLedgerService service = new UsageLedgerService(fakeEvalRuns(),
                ledgerOf(successRow(ATTEMPT, 100, 20), unknown), null, null, 0);

        var ledger = service.reconcileEvalRun(EVAL_RUN);

        assertThat(ledger.attempts().get(0).expectedPrompt()).isNull();
    }

    @Test
    @DisplayName("仅 FAILED 行：无响应无用量，usage_missing 落账（不编数）")
    void failedOnlyAttemptFallsBackToMissing() {
        RcaModelCallLedger.CallUsage failed = new RcaModelCallLedger.CallUsage(
                ATTEMPT, "primary", 0, 1, null, null, null, null, null, null,
                false, "FAILED");
        UsageLedgerService service = new UsageLedgerService(fakeEvalRuns(),
                ledgerOf(failed), null, null, 0);

        var ledger = service.reconcileEvalRun(EVAL_RUN);

        assertThat(ledger.attempts().get(0).expectedPrompt()).isNull();
        assertThat(ledger.attempts().get(0).expectedCompletion()).isNull();
    }

    @Test
    @DisplayName("EU17 面一（读域隔离）：只读关联 RCA Run 的账本行，他 run 行零污染")
    void readsOnlyLinkedRcaRuns() {
        UUID otherAttempt = UUID.randomUUID();
        RcaModelCallLedger.CallUsage alien = new RcaModelCallLedger.CallUsage(
                otherAttempt, "primary", 9, 1, 9_999, 9_999, 19_998, null, null, null,
                false, "SUCCESS");
        // 假件按 runId 圈读（生产 SQL WHERE run_id 的镜像）：RCA_RUN 查询不回他 run 行
        RcaModelCallLedger scopedLedger = new RcaModelCallLedger() {
            @Override
            public void open(OpenRow row) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean succeed(UUID id, UsageOutcome usage) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean fail(UUID id, String errorCode) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean markUnknown(UUID id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<UnsettledRow> findUnsettledByRun(UUID runId) {
                return List.of();
            }

            @Override
            public List<CallUsage> listSettledUsageByRunId(UUID runId) {
                return RCA_RUN.equals(runId)
                        ? List.of(successRow(ATTEMPT, 100, 20)) : List.of(alien);
            }
        };
        UsageLedgerService service = new UsageLedgerService(fakeEvalRuns(),
                scopedLedger, null, null, 0);

        var ledger = service.reconcileEvalRun(EVAL_RUN);

        assertThat(ledger.attempts()).hasSize(1);
        assertThat(ledger.attempts().get(0).attemptId()).isEqualTo(ATTEMPT);
        assertThat(ledger.expectedPromptTokens()).isEqualTo(100);
    }

    // ---------------- fakes ----------------

    private static RcaModelCallLedger.CallUsage successRow(UUID attemptId,
            int prompt, int completion) {
        return new RcaModelCallLedger.CallUsage(attemptId, "primary", 0, 1,
                prompt, completion, prompt + completion, null, "unpriced", null,
                false, "SUCCESS");
    }

    private static RcaModelCallLedger ledgerOf(RcaModelCallLedger.CallUsage... rows) {
        return new RcaModelCallLedger() {
            @Override
            public void open(OpenRow row) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean succeed(UUID id, UsageOutcome usage) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean fail(UUID id, String errorCode) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean markUnknown(UUID id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<UnsettledRow> findUnsettledByRun(UUID runId) {
                return List.of();
            }

            @Override
            public List<CallUsage> listSettledUsageByRunId(UUID runId) {
                return List.of(rows);
            }
        };
    }

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
}
