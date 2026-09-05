package com.objwww.pr.control.eval.domain.litellm;

import com.objwww.pr.control.eval.domain.litellm.UsageLedgerReconciler.AttemptUsage;
import com.objwww.pr.control.eval.domain.litellm.UsageLedgerReconciler.BudgetEnforcement;
import com.objwww.pr.control.eval.domain.litellm.UsageLedgerReconciler.LedgerBasis;
import com.objwww.pr.control.eval.domain.litellm.UsageLedgerReconciler.LedgerState;
import com.objwww.pr.control.eval.domain.litellm.UsageLedgerReconciler.RunLedgerInput;
import com.objwww.pr.control.eval.domain.litellm.UsageLedgerReconciler.UsageLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-25 验收：1:1、1:N、缺 usage 三态、缺 metadata 时不得用时间窗伪造 MATCHED
 * （评审 P0-8 降级链）。
 */
class UsageLedgerReconcilerTest {

    private final UsageLedgerReconciler reconciler = new UsageLedgerReconciler();

    private final UUID runId = UUID.randomUUID();
    private final UUID attemptA = UUID.randomUUID();
    private final UUID attemptB = UUID.randomUUID();

    private SpendRecord row(String keyAlias, String runIdMeta, String attemptIdMeta,
                            Integer prompt, Integer completion) {
        return new SpendRecord(UUID.randomUUID().toString(), "deepseek-v3",
                new BigDecimal("0.002"), prompt, completion, "success",
                keyAlias, runIdMeta, attemptIdMeta);
    }

    private RunLedgerInput input(String alias, AttemptUsage... attempts) {
        return new RunLedgerInput(runId, alias, List.of(attempts));
    }

    @Test
    @DisplayName("链① metadata 1:1：单行精确相等 → MATCHED")
    void oneToOneMetadataMatched() {
        UsageLedger ledger = reconciler.reconcile(
                input("run-x", new AttemptUsage(attemptA, 100, 20)),
                List.of(
                        // 无关流量：共用 proxy 的别 run 行，必须被筛掉
                        row("other-run", runId.toString(), attemptB.toString(), 999, 999),
                        row("run-x", runId.toString(), attemptA.toString(), 100, 20)));

        assertThat(ledger.basis()).isEqualTo(LedgerBasis.METADATA);
        assertThat(ledger.state()).isEqualTo(LedgerState.MATCHED);
        assertThat(ledger.budgetEnforcement()).isEqualTo(BudgetEnforcement.PROXY_VIRTUAL_KEY);
        assertThat(ledger.attempts()).hasSize(1);
        assertThat(ledger.attempts().get(0).state()).isEqualTo(LedgerState.MATCHED);
        assertThat(ledger.attempts().get(0).associatedRows()).isEqualTo(1);
        assertThat(ledger.observedPromptTokens()).isEqualTo(100);
        assertThat(ledger.observedCompletionTokens()).isEqualTo(20);
    }

    @Test
    @DisplayName("链① metadata 1:N：多行求和相等 → MATCHED（一对多聚合，不比行数）")
    void oneToManyMetadataSumMatched() {
        UsageLedger ledger = reconciler.reconcile(
                input("run-x", new AttemptUsage(attemptA, 300, 60)),
                List.of(row("run-x", runId.toString(), attemptA.toString(), 100, 20),
                        row("run-x", runId.toString(), attemptA.toString(), 100, 20),
                        row("run-x", runId.toString(), attemptA.toString(), 100, 20)));

        assertThat(ledger.state()).isEqualTo(LedgerState.MATCHED);
        assertThat(ledger.attempts().get(0).associatedRows()).isEqualTo(3);
    }

    @Test
    @DisplayName("缺 usage 三态：账本侧 usage_missing 而行在场 → UNMATCHED（允许缺失，不判失败）")
    void usageMissingAttemptUnmatched() {
        UsageLedger ledger = reconciler.reconcile(
                input("run-x", new AttemptUsage(attemptA, null, null)),
                List.of(row("run-x", runId.toString(), attemptA.toString(), 100, 20)));

        assertThat(ledger.state()).isEqualTo(LedgerState.UNMATCHED);
        assertThat(ledger.attempts().get(0).state()).isEqualTo(LedgerState.UNMATCHED);
        assertThat(ledger.attempts().get(0).notes()).contains("usage_missing");
        assertThat(ledger.basis()).isEqualTo(LedgerBasis.METADATA);
    }

    @Test
    @DisplayName("行 token 缺失 → PARTIAL（无法核验不猜默认值）")
    void rowTokensMissingPartial() {
        UsageLedger ledger = reconciler.reconcile(
                input("run-x", new AttemptUsage(attemptA, 100, 20)),
                List.of(row("run-x", runId.toString(), attemptA.toString(), null, null)));

        assertThat(ledger.state()).isEqualTo(LedgerState.PARTIAL);
        assertThat(ledger.attempts().get(0).notes()).contains("row_tokens_missing");
    }

    @Test
    @DisplayName("sum 不等 → PARTIAL")
    void tokenMismatchPartial() {
        UsageLedger ledger = reconciler.reconcile(
                input("run-x", new AttemptUsage(attemptA, 100, 20)),
                List.of(row("run-x", runId.toString(), attemptA.toString(), 90, 20)));

        assertThat(ledger.state()).isEqualTo(LedgerState.PARTIAL);
    }

    @Test
    @DisplayName("链② virtual key 聚合：无 metadata 但 key 独占 → run 级总量对账 MATCHED")
    void virtualKeyRunLevelMatched() {
        UsageLedger ledger = reconciler.reconcile(
                input("run-x",
                        new AttemptUsage(attemptA, 100, 20),
                        new AttemptUsage(attemptB, 50, 10)),
                List.of(row("run-x", null, null, 100, 20),
                        row("run-x", null, null, 50, 10)));

        assertThat(ledger.basis()).isEqualTo(LedgerBasis.VIRTUAL_KEY);
        assertThat(ledger.state()).isEqualTo(LedgerState.MATCHED);
        assertThat(ledger.attempts()).isEmpty();
        assertThat(ledger.notes()).anySatisfy(n -> assertThat(n)
                .contains("attempt_level_attribution_unavailable"));
    }

    @Test
    @DisplayName("链② 总量不等 → PARTIAL")
    void virtualKeyRunLevelPartial() {
        UsageLedger ledger = reconciler.reconcile(
                input("run-x", new AttemptUsage(attemptA, 100, 20)),
                List.of(row("run-x", null, null, 70, 20)));

        assertThat(ledger.basis()).isEqualTo(LedgerBasis.VIRTUAL_KEY);
        assertThat(ledger.state()).isEqualTo(LedgerState.PARTIAL);
    }

    @Test
    @DisplayName("P0-8 核心：无 metadata 无 per-run key → UNMATCHED/BEST_EFFORT，即使行时间落在 run 窗口内也不得 MATCHED")
    void noChannelNeverFakesMatched() {
        // 行只是"恰好"存在（时间上不可区分的正常 RCA 共用流量）——对账器根本不接收时间
        UsageLedger ledger = reconciler.reconcile(
                input(null, new AttemptUsage(attemptA, 100, 20)),
                List.of(row(null, runId.toString(), attemptA.toString(), 100, 20)));

        assertThat(ledger.basis()).isEqualTo(LedgerBasis.NONE);
        assertThat(ledger.state()).isEqualTo(LedgerState.UNMATCHED);
        assertThat(ledger.bestEffort()).isTrue();
        assertThat(ledger.budgetEnforcement()).isEqualTo(BudgetEnforcement.BEST_EFFORT);
    }

    @Test
    @DisplayName("结构防时间窗：对账器源码不出现任何时间参数/过滤面")
    void reconcilerHasNoTimeWindowSurface() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/objwww/pr/control/eval/domain/litellm/UsageLedgerReconciler.java"));
        assertThat(source)
                .doesNotContain("java.time")
                .doesNotContain("Instant")
                .doesNotContain("start_date")
                .doesNotContain("end_date")
                .doesNotContain("createdAt");
    }

    @Test
    @DisplayName("链① 残行 taint：key 独占面内存在无法归因的行 → 不判 MATCHED")
    void unattributedRowsTaintPartial() {
        UsageLedger ledger = reconciler.reconcile(
                input("run-x", new AttemptUsage(attemptA, 100, 20)),
                List.of(row("run-x", runId.toString(), attemptA.toString(), 100, 20),
                        // 同 key 但缺 attempt 标签的行：无法证伪是否属于本 run
                        row("run-x", runId.toString(), null, 33, 3)));

        assertThat(ledger.attempts().get(0).state()).isEqualTo(LedgerState.MATCHED);
        assertThat(ledger.state()).isEqualTo(LedgerState.PARTIAL);
        assertThat(ledger.notes()).anySatisfy(n -> assertThat(n).contains("unattributed_rows_under_key"));
    }

    @Test
    @DisplayName("链① attempt 指向他者：本 attempt 无行 → UNMATCHED（行不被错配）")
    void attemptIdMismatchUnmatched() {
        UUID stranger = UUID.randomUUID();
        UsageLedger ledger = reconciler.reconcile(
                input("run-x",
                        new AttemptUsage(attemptA, 100, 20),
                        new AttemptUsage(attemptB, 10, 2)),
                List.of(row("run-x", runId.toString(), attemptA.toString(), 100, 20),
                        row("run-x", runId.toString(), stranger.toString(), 7, 1)));

        assertThat(ledger.attempts().stream()
                .filter(a -> a.attemptId().equals(attemptB)).findFirst().orElseThrow()
                .state()).isEqualTo(LedgerState.UNMATCHED);
        assertThat(ledger.state()).isEqualTo(LedgerState.UNMATCHED);
    }

    @Test
    @DisplayName("出账体 JSON 可解析且固定字段序（ledger_schema_version 打头）")
    void toJsonParsesWithStableFieldOrder() throws Exception {
        UsageLedger ledger = reconciler.reconcile(
                input("run-x", new AttemptUsage(attemptA, 100, 20)),
                List.of(row("run-x", runId.toString(), attemptA.toString(), 100, 20)));
        com.fasterxml.jackson.databind.JsonNode body =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(ledger.toJson());

        assertThat(body.get("ledger_schema_version").asInt()).isEqualTo(1);
        assertThat(body.get("run_id").asText()).isEqualTo(runId.toString());
        assertThat(body.get("basis").asText()).isEqualTo("METADATA");
        assertThat(body.get("state").asText()).isEqualTo("MATCHED");
        assertThat(body.get("attempts")).hasSize(1);
    }
}
