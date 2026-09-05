package com.objwww.pr.control.eval.domain.litellm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 账本 × proxy 一对多聚合对账器（M3-25；AM3 §6.6 冻结语义 + v3.0r1 评审 P0-8 降级链）。
 *
 * <p>对账单位与口径（§6.6 冻结）：一次 control→Holmes 调用对应 N 次 Holmes→LiteLLM
 * 模型调用——<b>一对多聚合</b>，只比较 {@code sum(prompt/completion tokens)}，
 * <b>不比较行数相等</b>；usage 缺失允许落账（诚实记 UNMATCHED，不算失败）。
 *
 * <p>关联降级链（P0-8，顺序即优先级）：
 * <ol>
 *   <li>{@link LedgerBasis#METADATA}：spend 行携带 {@code spend_logs_metadata.run_id/
 *       attempt_id}（holmes model_list extra_body 通道，spike §二.2 实测直达 SpendLogs）
 *       ——可做 attempt 级 1:N 归因；</li>
 *   <li>{@link LedgerBasis#VIRTUAL_KEY}：行不携带可用 metadata 但落在本 EvalRun 独占
 *       virtual key（{@code metadata.user_api_key_alias}）下——只做 run 级聚合对账；</li>
 *   <li>{@link LedgerBasis#NONE}：两者皆无 → UNMATCHED + bestEffort，<b>绝不</b>按时间窗
 *       强行归因（评测期正常 RCA 共用 proxy，时间窗必误归——本类签名不接收任何时间参数，
 *       结构上杜绝该路径）。</li>
 * </ol>
 *
 * <p>预算硬拦语义（M3-26 spike 结论落账）：holmes 原生无 token/成本预算参数，硬拦只能在
 * proxy 虚拟 key {@code max_budget} 层实现——runKeyAlias 在场即视为 PROXY_VIRTUAL_KEY，
 * 否则预算口径整体标 BEST_EFFORT（spike Exp5：超限 429 在 auth 层拒绝、上游零流量）。
 */
public final class UsageLedgerReconciler {

    public enum LedgerBasis { METADATA, VIRTUAL_KEY, NONE }

    public enum LedgerState { MATCHED, PARTIAL, UNMATCHED }

    /** 预算硬拦落点标注（spike 结论的账面化） */
    public enum BudgetEnforcement { PROXY_VIRTUAL_KEY, BEST_EFFORT }

    /** 一次调查（attempt）的账本侧用量；token 为 null = usage_missing（§6.2 冻结） */
    public record AttemptUsage(UUID attemptId, Integer promptTokens, Integer completionTokens) {

        public AttemptUsage {
            Objects.requireNonNull(attemptId, "attemptId");
            if (promptTokens == null != (completionTokens == null)) {
                throw new IllegalArgumentException(
                        "usage 语义必须整体缺失或整体在场: " + attemptId);
            }
        }

        public boolean usageMissing() {
            return promptTokens == null;
        }
    }

    /** 对账主体（EvalRun 级或 RCA Run 级）+ 关联材料；无任何时间窗字段 */
    public record RunLedgerInput(UUID runId, String runKeyAlias, List<AttemptUsage> attempts) {

        public RunLedgerInput {
            Objects.requireNonNull(runId, "runId");
            attempts = List.copyOf(attempts);
        }
    }

    /** attempt 级对账明细（METADATA 基准下才有行级归因；其余基准 attempts 为空表） */
    public record AttemptLedger(UUID attemptId,
                                LedgerState state,
                                Integer expectedPrompt,
                                Integer expectedCompletion,
                                Integer observedPrompt,
                                Integer observedCompletion,
                                int associatedRows,
                                List<String> notes) {

        public AttemptLedger {
            Objects.requireNonNull(attemptId);
            Objects.requireNonNull(state);
            notes = List.copyOf(notes);
        }
    }

    /** 三态出账体（canonical JSON 固定字段序 = 分量序；digest 由调用方按需计算） */
    public record UsageLedger(UUID runId,
                              LedgerBasis basis,
                              LedgerState state,
                              boolean bestEffort,
                              BudgetEnforcement budgetEnforcement,
                              String runKeyAlias,
                              List<AttemptLedger> attempts,
                              Integer expectedPromptTokens,
                              Integer expectedCompletionTokens,
                              Integer observedPromptTokens,
                              Integer observedCompletionTokens,
                              List<String> notes) {

        public UsageLedger {
            Objects.requireNonNull(runId);
            Objects.requireNonNull(basis);
            Objects.requireNonNull(state);
            Objects.requireNonNull(budgetEnforcement);
            attempts = List.copyOf(attempts);
            notes = List.copyOf(notes);
        }

        public String toJson() {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("ledger_schema_version", 1);
                body.put("run_id", runId.toString());
                body.put("basis", basis.name());
                body.put("state", state.name());
                body.put("best_effort", bestEffort);
                body.put("budget_enforcement", budgetEnforcement.name());
                body.put("run_key_alias", runKeyAlias);
                body.put("attempts", attempts.stream().map(a -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("attempt_id", a.attemptId().toString());
                    m.put("state", a.state().name());
                    m.put("expected_prompt", a.expectedPrompt());
                    m.put("expected_completion", a.expectedCompletion());
                    m.put("observed_prompt", a.observedPrompt());
                    m.put("observed_completion", a.observedCompletion());
                    m.put("associated_rows", a.associatedRows());
                    m.put("notes", a.notes());
                    return m;
                }).toList());
                body.put("expected_prompt_tokens", expectedPromptTokens);
                body.put("expected_completion_tokens", expectedCompletionTokens);
                body.put("observed_prompt_tokens", observedPromptTokens);
                body.put("observed_completion_tokens", observedCompletionTokens);
                body.put("notes", notes);
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(body);
            } catch (Exception e) {
                throw new IllegalStateException("usage ledger 序列化失败", e);
            }
        }
    }

    /**
     * @param fetchedRows 查询得到的 spend 行全集（可含无关流量；本方法按 key 别名与
     *                    metadata 自行筛拣，调用方不需要预过滤）
     */
    public UsageLedger reconcile(RunLedgerInput input, List<SpendRecord> fetchedRows) {
        Objects.requireNonNull(input);
        Objects.requireNonNull(fetchedRows);

        String runIdText = input.runId().toString();
        // 无 key 别名时无从圈定"本 run 的行"——共用 proxy 的任何行都不可归因（P0-8 禁时间窗）
        List<SpendRecord> rowsUnderKey = input.runKeyAlias() == null ? List.of()
                : fetchedRows.stream()
                        .filter(r -> input.runKeyAlias().equals(r.keyAlias()))
                        .toList();
        BudgetEnforcement budget = input.runKeyAlias() == null
                ? BudgetEnforcement.BEST_EFFORT : BudgetEnforcement.PROXY_VIRTUAL_KEY;

        List<SpendRecord> metadataRows = rowsUnderKey.stream()
                .filter(r -> runIdText.equals(r.metadataRunId()))
                .toList();

        if (!metadataRows.isEmpty()) {
            return reconcileByMetadata(input, rowsUnderKey, metadataRows, budget);
        }
        if (!rowsUnderKey.isEmpty()) {
            return reconcileByKeyAggregate(input, rowsUnderKey, budget);
        }
        String note = input.runKeyAlias() == null
                ? "no_association_channel（无 metadata 且无 per-run virtual key；按 P0-8 拒绝时间窗归因）"
                : "no_rows_under_run_key（key 下零行：未 flush/查询失败/该 run 确无模型调用）";
        List<AttemptLedger> degraded = input.attempts().stream()
                .map(a -> new AttemptLedger(a.attemptId(), LedgerState.UNMATCHED,
                        a.promptTokens(), a.completionTokens(), null, null, 0, List.of(note)))
                .toList();
        return new UsageLedger(input.runId(), LedgerBasis.NONE, LedgerState.UNMATCHED,
                true, budget, input.runKeyAlias(), degraded,
                sumExpected(input, true), sumExpected(input, false), null, null,
                List.of(note));
    }

    /** 链①：metadata 归因——attempt 级 1:N 聚合 + 残行 taint */
    private UsageLedger reconcileByMetadata(RunLedgerInput input,
                                            List<SpendRecord> rowsUnderKey,
                                            List<SpendRecord> metadataRows,
                                            BudgetEnforcement budget) {
        List<String> notes = new ArrayList<>();
        List<AttemptLedger> attemptLedgers = new ArrayList<>();
        int observedPrompt = 0;
        int observedCompletion = 0;
        LedgerState worst = LedgerState.MATCHED;

        for (AttemptUsage attempt : input.attempts()) {
            String attemptIdText = attempt.attemptId().toString();
            List<SpendRecord> attemptRows = metadataRows.stream()
                    .filter(r -> attemptIdText.equals(r.metadataAttemptId()))
                    .toList();
            if (attemptRows.isEmpty()) {
                worst = worse(worst, LedgerState.UNMATCHED);
                attemptLedgers.add(new AttemptLedger(attempt.attemptId(),
                        LedgerState.UNMATCHED, attempt.promptTokens(), attempt.completionTokens(),
                        null, null, 0, List.of("no_rows_for_attempt")));
                continue;
            }
            if (attempt.usageMissing()) {
                worst = worse(worst, LedgerState.UNMATCHED);
                attemptLedgers.add(new AttemptLedger(attempt.attemptId(),
                        LedgerState.UNMATCHED, null, null,
                        sum(attemptRows, true), sum(attemptRows, false),
                        attemptRows.size(), List.of("usage_missing")));
                continue;
            }
            List<String> rowNotes = new ArrayList<>();
            boolean rowTokensMissing = attemptRows.stream()
                    .anyMatch(r -> r.promptTokens() == null || r.completionTokens() == null);
            if (rowTokensMissing) {
                rowNotes.add("row_tokens_missing");
            }
            int op = sum(attemptRows, true);
            int oc = sum(attemptRows, false);
            LedgerState state = rowTokensMissing ? LedgerState.PARTIAL
                    : (op == attempt.promptTokens() && oc == attempt.completionTokens())
                    ? LedgerState.MATCHED : LedgerState.PARTIAL;
            worst = worse(worst, state);
            observedPrompt += op;
            observedCompletion += oc;
            attemptLedgers.add(new AttemptLedger(attempt.attemptId(), state,
                    attempt.promptTokens(), attempt.completionTokens(), op, oc,
                    attemptRows.size(), rowNotes));
        }

        // 残行 taint：key 独占面内未归到任何 attempt 的行（无 attempt_id / 指向他者 /
        // 缺 run 标签）——无法证伪即不可声明 MATCHED
        List<SpendRecord> leftovers = rowsUnderKey.stream()
                .filter(r -> !metadataRows.contains(r)
                        || r.metadataAttemptId() == null
                        || input.attempts().stream()
                        .noneMatch(a -> a.attemptId().toString().equals(r.metadataAttemptId())))
                .toList();
        if (!leftovers.isEmpty()) {
            notes.add("unattributed_rows_under_key=" + leftovers.size()
                    + "（无法证伪即不判 MATCHED）");
            worst = worse(worst, LedgerState.PARTIAL);
        }

        return new UsageLedger(input.runId(), LedgerBasis.METADATA, worst,
                false, budget, input.runKeyAlias(), attemptLedgers,
                sumExpected(input, true), sumExpected(input, false),
                observedPrompt, observedCompletion, notes);
    }

    /** 链②：key 独占聚合——只做 run 级总量对账（metadata 不可用，attempt 级无从归因） */
    private UsageLedger reconcileByKeyAggregate(RunLedgerInput input,
                                                List<SpendRecord> rowsUnderKey,
                                                BudgetEnforcement budget) {
        List<String> notes = new ArrayList<>();
        boolean rowTokensMissing = rowsUnderKey.stream()
                .anyMatch(r -> r.promptTokens() == null || r.completionTokens() == null);
        int observedPrompt = sum(rowsUnderKey, true);
        int observedCompletion = sum(rowsUnderKey, false);
        int expectedPrompt = sumExpected(input, true);
        int expectedCompletion = sumExpected(input, false);

        notes.add("attempt_level_attribution_unavailable（metadata 缺失，key 级聚合对账）");
        if (rowTokensMissing) {
            notes.add("row_tokens_missing");
        }

        LedgerState state;
        if (rowTokensMissing) {
            state = LedgerState.PARTIAL;
        } else if (expectedPrompt == 0 && expectedCompletion == 0) {
            // 全部 attempt usage_missing：有花销但无账本锚——诚实 UNMATCHED
            state = LedgerState.UNMATCHED;
            notes.add("all_attempts_usage_missing");
        } else {
            state = (observedPrompt == expectedPrompt
                    && observedCompletion == expectedCompletion)
                    ? LedgerState.MATCHED : LedgerState.PARTIAL;
        }
        return new UsageLedger(input.runId(), LedgerBasis.VIRTUAL_KEY, state,
                false, budget, input.runKeyAlias(), List.of(),
                expectedPrompt, expectedCompletion, observedPrompt, observedCompletion, notes);
    }

    private static int sum(List<SpendRecord> rows, boolean prompt) {
        return rows.stream()
                .map(r -> prompt ? r.promptTokens() : r.completionTokens())
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }

    private static int sumExpected(RunLedgerInput input, boolean prompt) {
        return input.attempts().stream()
                .map(a -> prompt ? a.promptTokens() : a.completionTokens())
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }

    private static LedgerState worse(LedgerState a, LedgerState b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
