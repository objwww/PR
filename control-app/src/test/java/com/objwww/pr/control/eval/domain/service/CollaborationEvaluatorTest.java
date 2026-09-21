package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.eval.domain.model.BehaviorCheckStatus;
import com.objwww.pr.control.eval.domain.model.BehaviorEvaluation;
import com.objwww.pr.control.eval.domain.model.CollaborationEvaluation;
import com.objwww.pr.control.eval.domain.model.CollaborationInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ME-T07/D07 多 Agent 协作评测纯函数 UT：MA-01～10 逐条机制面断言 + 步骤 6 消融
 * 单因子 + 五项机制指标分子/分母 + deferred 如实不出数。输入全为脚本桩确定性
 * 投影；真实模型三臂（A/B/C）归夜间专项，不在此断言。
 */
class CollaborationEvaluatorTest {

    private final CollaborationEvaluator evaluator = new CollaborationEvaluator();

    // ------------------------------------------------------------ fixture 面

    private static CollaborationInput.HandoffEdge edge(
            String id, String role,
            CollaborationInput.ChildOutcome outcome,
            CollaborationInput.Admission admission,
            List<String> sent, List<String> preserved,
            boolean applicable, boolean consumed, int consumptionCount,
            boolean fabricated, boolean afterCancelDispatch, boolean afterCancelMerge,
            boolean inFlightSettled, boolean doubleSettled,
            Long tokenCost, List<String> digests, boolean sharedIndependent) {
        return new CollaborationInput.HandoffEdge(id, "parent-task", id + "-child", role, 1,
                sent, preserved, List.of(), outcome, admission, applicable, consumed,
                consumptionCount, fabricated, afterCancelDispatch, afterCancelMerge,
                inFlightSettled, doubleSettled, tokenCost, null, digests, sharedIndependent);
    }

    /** 正常成功边：必需事实全保留、适用且被消费一次、成本 100 token */
    private static CollaborationInput.HandoffEdge ok(String id, String role) {
        return edge(id, role, CollaborationInput.ChildOutcome.SUCCEEDED,
                CollaborationInput.Admission.ACCEPTED,
                List.of("fact-A"), List.of("fact-A"),
                true, true, 1, false, false, false, true, false, 100L, List.of(), false);
    }

    private static CollaborationInput input(Boolean needed, boolean multiModal,
            boolean cancelled, List<CollaborationInput.HandoffEdge> edges,
            List<CollaborationInput.ConflictCase> conflicts,
            List<CollaborationInput.InjectedError> errors,
            List<CollaborationInput.ArmConfig> arms,
            CollaborationInput.AblationPair ablation,
            CollaborationInput.InterventionReplay intervention) {
        return new CollaborationInput("MA-CASE", needed, multiModal, cancelled, edges,
                conflicts, errors, arms, ablation, intervention);
    }

    private static CollaborationInput edgesOnly(List<CollaborationInput.HandoffEdge> edges) {
        return input(true, false, false, edges, List.of(), List.of(), List.of(), null, null);
    }

    private static CollaborationInput.ArmConfig arm(String id, Set<String> tools,
                                                    long budget) {
        return new CollaborationInput.ArmConfig(id, tools, budget, 300_000L,
                "model-X/role-cfg-1", "snapshot-20260920");
    }

    private static BehaviorEvaluation.Check checkOf(CollaborationEvaluation ev, String name) {
        return ev.checks().stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("缺检查 " + name));
    }

    private static Map<String, BehaviorEvaluation.Metric> metricsOf(
            CollaborationEvaluation ev) {
        return ev.metrics().stream().collect(Collectors.toMap(
                BehaviorEvaluation.Metric::name, Function.identity()));
    }

    // ---------------------------------------------------------------- MA 面

    @Test
    @DisplayName("MA-01：简单单源案例标注无需协作且未委派 → 选择正确；无谓委派 → FAIL + 无谓协作成本标签")
    void ma01DelegationNecessityChoice() {
        CollaborationEvaluation simple = evaluator.evaluate(
                input(false, false, false, List.of(), List.of(), List.of(), List.of(),
                        null, null));
        assertThat(checkOf(simple, CollaborationEvaluator.CHECK_DELEGATION_NECESSITY)
                .status()).isEqualTo(BehaviorCheckStatus.PASS);
        assertThat(metricsOf(simple)
                .get(CollaborationEvaluator.M_DELEGATION_CHOICE).numerator()).isEqualTo(1);

        CollaborationEvaluation wasted = evaluator.evaluate(
                input(false, false, false, List.of(ok("e1", "metrics")), List.of(),
                        List.of(), List.of(), null, null));
        BehaviorEvaluation.Check c = checkOf(wasted,
                CollaborationEvaluator.CHECK_DELEGATION_NECESSITY);
        assertThat(c.status()).isEqualTo(BehaviorCheckStatus.FAIL);
        assertThat(c.reasonCode()).isEqualTo("UNNECESSARY_DELEGATION");
        assertThat(wasted.failureLabels()).contains("DELEGATION_CHOICE_WRONG");
        // 需协作却零委派同样是选择错误
        CollaborationEvaluation omitted = evaluator.evaluate(
                input(true, false, false, List.of(), List.of(), List.of(), List.of(),
                        null, null));
        assertThat(checkOf(omitted, CollaborationEvaluator.CHECK_DELEGATION_NECESSITY)
                .reasonCode()).isEqualTo("COLLABORATION_OMITTED");
        // 未标注 → 缺证据不猜通过
        CollaborationEvaluation unlabeled = evaluator.evaluate(
                input(null, false, false, List.of(), List.of(), List.of(), List.of(),
                        null, null));
        assertThat(checkOf(unlabeled, CollaborationEvaluator.CHECK_DELEGATION_NECESSITY)
                .status()).isEqualTo(BehaviorCheckStatus.NOT_ASSESSED);
    }

    @Test
    @DisplayName("MA-02：两模态证据各由可归属角色贡献并被消费 → 跨模态支持 PASS；单角色/不可归属 → FAIL")
    void ma02CrossModalAttribution() {
        CollaborationEvaluation okCase = evaluator.evaluate(input(true, true, false,
                List.of(ok("e1", "metrics"), ok("e2", "logs")),
                List.of(), List.of(), List.of(), null, null));
        BehaviorEvaluation.Check pass = checkOf(okCase,
                CollaborationEvaluator.CHECK_CROSS_MODAL);
        assertThat(pass.status()).isEqualTo(BehaviorCheckStatus.PASS);
        assertThat(pass.evidenceRefs()).containsExactlyInAnyOrder("metrics", "logs");

        CollaborationEvaluation singleRole = evaluator.evaluate(input(true, true, false,
                List.of(ok("e1", "metrics"), ok("e2", "metrics")),
                List.of(), List.of(), List.of(), null, null));
        assertThat(checkOf(singleRole, CollaborationEvaluator.CHECK_CROSS_MODAL)
                .reasonCode()).isEqualTo("NO_CROSS_MODAL_SUPPORT");
        assertThat(singleRole.failureLabels())
                .contains(CollaborationEvaluation.MAST_VERIFICATION_INADEQUATE);

        CollaborationEvaluation unattributable = evaluator.evaluate(input(true, true, false,
                List.of(ok("e1", "metrics"), ok("e2", "")),
                List.of(), List.of(), List.of(), null, null));
        BehaviorEvaluation.Check c = checkOf(unattributable,
                CollaborationEvaluator.CHECK_CROSS_MODAL);
        assertThat(c.reasonCode()).isEqualTo("ROLE_UNATTRIBUTABLE");
        assertThat(c.evidenceRefs()).containsExactly("e2");
    }

    @Test
    @DisplayName("MA-03：冲突按反证处置且命中真值 → PASS；按置信措辞/投票确认 → FAIL + MAST_IGNORED_PEER")
    void ma03ConflictResolutionGrounded() {
        CollaborationInput.ConflictCase grounded = new CollaborationInput.ConflictCase(
                "cf-1", CollaborationInput.ConflictCase.ResolutionBasis.COUNTER_EVIDENCE,
                "claim-metrics", "claim-metrics");
        CollaborationEvaluation okCase = evaluator.evaluate(input(true, false, false,
                List.of(ok("e1", "logs"), ok("e2", "metrics")),
                List.of(grounded), List.of(), List.of(), null, null));
        assertThat(checkOf(okCase, CollaborationEvaluator.CHECK_CONFLICT).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
        assertThat(metricsOf(okCase)
                .get(CollaborationEvaluator.M_CONFLICT_RESOLUTION).numerator()).isEqualTo(1);

        // 日志专家高置信错误 vs 指标专家反证：按置信措辞确认即机制违例，结论对错都不算
        CollaborationInput.ConflictCase byConfidence = new CollaborationInput.ConflictCase(
                "cf-2", CollaborationInput.ConflictCase.ResolutionBasis.CONFIDENCE_WORDING,
                "claim-logs", "claim-metrics");
        CollaborationEvaluation bad = evaluator.evaluate(input(true, false, false,
                List.of(ok("e1", "logs"), ok("e2", "metrics")),
                List.of(byConfidence), List.of(), List.of(), null, null));
        BehaviorEvaluation.Check c = checkOf(bad, CollaborationEvaluator.CHECK_CONFLICT);
        assertThat(c.status()).isEqualTo(BehaviorCheckStatus.FAIL);
        assertThat(c.reasonCode()).isEqualTo("CONFLICT_BY_VOTE_OR_CONFIDENCE");
        assertThat(c.evidenceRefs()).containsExactly("cf-2");
        assertThat(bad.failureLabels()).contains(CollaborationEvaluation.MAST_IGNORED_PEER);
        assertThat(metricsOf(bad)
                .get(CollaborationEvaluator.M_CONFLICT_RESOLUTION).numerator()).isEqualTo(0);
    }

    @Test
    @DisplayName("MA-04：交接遗漏「不可写操作」约束 → 保留率 FAIL + MAST_INFORMATION_LOSS + 分子/分母如实")
    void ma04HandoffConstraintRetention() {
        CollaborationInput.HandoffEdge lossy = edge("e1", "metrics",
                CollaborationInput.ChildOutcome.SUCCEEDED,
                CollaborationInput.Admission.ACCEPTED,
                List.of("fact-A", "constraint:只读禁止写操作"), List.of("fact-A"),
                true, true, 1, false, false, false, true, false, 100L, List.of(), false);
        CollaborationEvaluation ev = evaluator.evaluate(edgesOnly(List.of(lossy)));
        BehaviorEvaluation.Check c = checkOf(ev,
                CollaborationEvaluator.CHECK_HANDOFF_RETENTION);
        assertThat(c.status()).isEqualTo(BehaviorCheckStatus.FAIL);
        assertThat(c.reasonCode()).isEqualTo("HANDOFF_FACT_MISSING");
        assertThat(c.evidenceRefs()).containsExactly("e1");
        assertThat(ev.failureLabels())
                .contains(CollaborationEvaluation.MAST_INFORMATION_LOSS);
        BehaviorEvaluation.Metric m = metricsOf(ev)
                .get(CollaborationEvaluator.M_HANDOFF_RETENTION);
        assertThat(m.numerator()).isEqualTo(1);
        assertThat(m.denominator()).isEqualTo(2);
    }

    @Test
    @DisplayName("MA-05：子任务超时/空集/未知 → 不伪造结论有界降级 PASS；伪造子任务结论 → FAIL + MAST_TASK_DERAIL")
    void ma05BoundedDegradationNoFabrication() {
        CollaborationInput.HandoffEdge noResult = edge("e1", "logs",
                CollaborationInput.ChildOutcome.NO_RESULT, null,
                List.of(), List.of(), true, false, 0, false, false, false, true, false,
                40L, List.of(), false);
        CollaborationEvaluation bounded = evaluator.evaluate(edgesOnly(List.of(noResult)));
        assertThat(checkOf(bounded, CollaborationEvaluator.CHECK_BOUNDED_DEGRADATION)
                .status()).isEqualTo(BehaviorCheckStatus.PASS);

        CollaborationInput.HandoffEdge fabricated = edge("e1", "logs",
                CollaborationInput.ChildOutcome.NO_RESULT, null,
                List.of(), List.of(), true, false, 0, true, false, false, true, false,
                40L, List.of(), false);
        CollaborationEvaluation bad = evaluator.evaluate(edgesOnly(List.of(fabricated)));
        BehaviorEvaluation.Check c = checkOf(bad,
                CollaborationEvaluator.CHECK_BOUNDED_DEGRADATION);
        assertThat(c.status()).isEqualTo(BehaviorCheckStatus.FAIL);
        assertThat(c.reasonCode()).isEqualTo("FABRICATED_CHILD_CONCLUSION");
        assertThat(bad.failureLabels()).contains(CollaborationEvaluation.MAST_TASK_DERAIL);
    }

    @Test
    @DisplayName("MA-06：回执重复消费/迟到合入/预算重复结算 → FAIL + MAST_REPETITION；恰一次消费 → PASS")
    void ma06ReceiptConsumptionIdempotency() {
        CollaborationEvaluation okCase = evaluator.evaluate(
                edgesOnly(List.of(ok("e1", "metrics"))));
        assertThat(checkOf(okCase, CollaborationEvaluator.CHECK_RECEIPT_IDEMPOTENCY)
                .status()).isEqualTo(BehaviorCheckStatus.PASS);

        CollaborationInput.HandoffEdge dup = edge("e1", "metrics",
                CollaborationInput.ChildOutcome.SUCCEEDED,
                CollaborationInput.Admission.ACCEPTED,
                List.of("fact-A"), List.of("fact-A"),
                true, true, 2, false, false, false, true, false, 100L, List.of(), false);
        CollaborationEvaluation doubleConsumed = evaluator.evaluate(edgesOnly(List.of(dup)));
        assertThat(checkOf(doubleConsumed, CollaborationEvaluator.CHECK_RECEIPT_IDEMPOTENCY)
                .reasonCode()).isEqualTo("RECEIPT_DOUBLE_CONSUMPTION");
        assertThat(doubleConsumed.failureLabels())
                .contains(CollaborationEvaluation.MAST_REPETITION);

        CollaborationInput.HandoffEdge late = edge("e2", "logs",
                CollaborationInput.ChildOutcome.SUCCEEDED,
                CollaborationInput.Admission.LATE,
                List.of("fact-B"), List.of("fact-B"),
                true, true, 1, false, false, false, true, false, 60L, List.of(), false);
        CollaborationEvaluation lateConsumed = evaluator.evaluate(
                edgesOnly(List.of(ok("e1", "metrics"), late)));
        assertThat(checkOf(lateConsumed, CollaborationEvaluator.CHECK_RECEIPT_IDEMPOTENCY)
                .reasonCode()).isEqualTo("LATE_RECEIPT_CONSUMED");

        CollaborationInput.HandoffEdge settledTwice = edge("e3", "change",
                CollaborationInput.ChildOutcome.SUCCEEDED,
                CollaborationInput.Admission.ACCEPTED,
                List.of("fact-C"), List.of("fact-C"),
                true, true, 1, false, false, false, true, true, 80L, List.of(), false);
        CollaborationEvaluation budget = evaluator.evaluate(
                edgesOnly(List.of(settledTwice)));
        assertThat(checkOf(budget, CollaborationEvaluator.CHECK_RECEIPT_IDEMPOTENCY)
                .reasonCode()).isEqualTo("BUDGET_DOUBLE_SETTLED");
    }

    @Test
    @DisplayName("MA-07：主任务取消后停止派发/迟到不复活/在途成本可核对 → PASS；违例三型分别 FAIL")
    void ma07CancellationFence() {
        CollaborationInput.HandoffEdge inFlight = edge("e1", "metrics",
                CollaborationInput.ChildOutcome.NO_RESULT, null,
                List.of("fact-A"), List.of("fact-A"),
                true, false, 0, false, false, false, true, false, 30L, List.of(), false);
        CollaborationEvaluation fenced = evaluator.evaluate(input(true, false, true,
                List.of(inFlight), List.of(), List.of(), List.of(), null, null));
        assertThat(checkOf(fenced, CollaborationEvaluator.CHECK_CANCELLATION).status())
                .isEqualTo(BehaviorCheckStatus.PASS);

        CollaborationInput.HandoffEdge dispatched = edge("e1", "metrics",
                CollaborationInput.ChildOutcome.NO_RESULT, null,
                List.of(), List.of(), true, false, 0, false, true, false, true, false,
                0L, List.of(), false);
        CollaborationEvaluation afterCancel = evaluator.evaluate(input(true, false, true,
                List.of(dispatched), List.of(), List.of(), List.of(), null, null));
        assertThat(checkOf(afterCancel, CollaborationEvaluator.CHECK_CANCELLATION)
                .reasonCode()).isEqualTo("DISPATCH_AFTER_CANCEL");
        assertThat(afterCancel.failureLabels())
                .contains(CollaborationEvaluation.MAST_TASK_DERAIL);

        CollaborationInput.HandoffEdge revived = edge("e1", "metrics",
                CollaborationInput.ChildOutcome.SUCCEEDED,
                CollaborationInput.Admission.LATE,
                List.of("fact-A"), List.of("fact-A"),
                true, false, 0, false, false, true, true, false, 30L, List.of(), false);
        CollaborationEvaluation late = evaluator.evaluate(input(true, false, true,
                List.of(revived), List.of(), List.of(), List.of(), null, null));
        assertThat(checkOf(late, CollaborationEvaluator.CHECK_CANCELLATION).reasonCode())
                .isEqualTo("LATE_RESULT_REVIVED");

        CollaborationInput.HandoffEdge unsettled = edge("e1", "metrics",
                CollaborationInput.ChildOutcome.NO_RESULT, null,
                List.of(), List.of(), true, false, 0, false, false, false, false, false,
                null, List.of(), false);
        CollaborationEvaluation cost = evaluator.evaluate(input(true, false, true,
                List.of(unsettled), List.of(), List.of(), List.of(), null, null));
        assertThat(checkOf(cost, CollaborationEvaluator.CHECK_CANCELLATION).reasonCode())
                .isEqualTo("IN_FLIGHT_COST_UNSETTLED");
    }

    @Test
    @DisplayName("MA-08：两专家重复取同一证据 → 计复用与重复成本 PASS；计成独立双重验证 → FAIL + MAST_VERIFICATION_INADEQUATE")
    void ma08DuplicateEvidenceAccounting() {
        CollaborationInput.HandoffEdge e1 = edge("e1", "metrics",
                CollaborationInput.ChildOutcome.SUCCEEDED,
                CollaborationInput.Admission.ACCEPTED,
                List.of("fact-A"), List.of("fact-A"),
                true, true, 1, false, false, false, true, false, 100L,
                List.of("digest-X"), false);
        CollaborationInput.HandoffEdge e2 = edge("e2", "logs",
                CollaborationInput.ChildOutcome.SUCCEEDED,
                CollaborationInput.Admission.ACCEPTED,
                List.of("fact-B"), List.of("fact-B"),
                true, true, 1, false, false, false, true, false, 90L,
                List.of("digest-X"), false);
        CollaborationEvaluation ev = evaluator.evaluate(edgesOnly(List.of(e1, e2)));
        assertThat(checkOf(ev, CollaborationEvaluator.CHECK_DUPLICATE_EVIDENCE).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
        BehaviorEvaluation.Metric m = metricsOf(ev)
                .get(CollaborationEvaluator.M_DUPLICATE_FETCH);
        assertThat(m.numerator()).isEqualTo(1);   // 重复物理取证 1 次（开销如实入账）
        assertThat(m.denominator()).isEqualTo(2);

        CollaborationInput.HandoffEdge independent = edge("e2", "logs",
                CollaborationInput.ChildOutcome.SUCCEEDED,
                CollaborationInput.Admission.ACCEPTED,
                List.of("fact-B"), List.of("fact-B"),
                true, true, 1, false, false, false, true, false, 90L,
                List.of("digest-X"), true);
        CollaborationEvaluation bad = evaluator.evaluate(edgesOnly(List.of(e1, independent)));
        assertThat(checkOf(bad, CollaborationEvaluator.CHECK_DUPLICATE_EVIDENCE)
                .reasonCode()).isEqualTo("SHARED_EVIDENCE_DOUBLE_VERIFICATION");
        assertThat(bad.failureLabels())
                .contains(CollaborationEvaluation.MAST_VERIFICATION_INADEQUATE);
    }

    @Test
    @DisplayName("MA-09：C 臂获得更大预算/更多工具 → ARM_INCOMPARABLE（禁止归因多 Agent）；五因子全同 → COMPARABLE")
    void ma09ArmComparability() {
        CollaborationEvaluation comparable = evaluator.evaluate(input(true, false, false,
                List.of(ok("e1", "metrics")), List.of(), List.of(),
                List.of(arm("A", Set.of("logs", "metrics"), 50_000L),
                        arm("C", Set.of("logs", "metrics"), 50_000L)),
                null, null));
        assertThat(checkOf(comparable, CollaborationEvaluator.CHECK_COMPARABILITY)
                .status()).isEqualTo(BehaviorCheckStatus.PASS);

        CollaborationEvaluation incomparable = evaluator.evaluate(input(true, false, false,
                List.of(ok("e1", "metrics")), List.of(), List.of(),
                List.of(arm("A", Set.of("logs", "metrics"), 50_000L),
                        arm("C", Set.of("logs", "metrics", "trace"), 100_000L)),
                null, null));
        BehaviorEvaluation.Check c = checkOf(incomparable,
                CollaborationEvaluator.CHECK_COMPARABILITY);
        assertThat(c.status()).isEqualTo(BehaviorCheckStatus.FAIL);
        assertThat(c.reasonCode()).startsWith("ARM_INCOMPARABLE")
                .contains("token_budget").contains("tools");
        assertThat(c.evidenceRefs()).containsExactly("C");
        assertThat(incomparable.failureLabels()).contains("ARM_INCOMPARABLE");
    }

    @Test
    @DisplayName("MA-10：替换错误回执后重放改善且双轨迹俱在 → 支持因果归因；无干预对照 → 只能疑似归因")
    void ma10InterventionReplayAttribution() {
        CollaborationInput.HandoffEdge lossy = edge("e1", "logs",
                CollaborationInput.ChildOutcome.SUCCEEDED,
                CollaborationInput.Admission.ACCEPTED,
                List.of("fact-A", "fact-B"), List.of("fact-A"),
                true, true, 1, false, false, false, true, false, 100L, List.of(), false);

        CollaborationEvaluation withReplay = evaluator.evaluate(input(true, false, false,
                List.of(lossy), List.of(), List.of(), List.of(), null,
                new CollaborationInput.InterventionReplay("e1", "pre-digest", "post-digest",
                        true)));
        assertThat(checkOf(withReplay, CollaborationEvaluator.CHECK_INTERVENTION).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
        assertThat(withReplay.supportedAttributions()).hasSize(1);
        assertThat(withReplay.supportedAttributions().get(0)).contains("e1");

        // 同一失败、无干预对照：失败标签只能疑似归因
        CollaborationEvaluation noControl = evaluator.evaluate(edgesOnly(List.of(lossy)));
        assertThat(checkOf(noControl, CollaborationEvaluator.CHECK_INTERVENTION).status())
                .isEqualTo(BehaviorCheckStatus.NOT_APPLICABLE);
        assertThat(noControl.supportedAttributions()).isEmpty();
        assertThat(noControl.suspectedAttributions()).anySatisfy(s -> assertThat(s)
                .contains(CollaborationEvaluation.MAST_INFORMATION_LOSS)
                .contains("疑似归因").contains("e1"));

        // 重放未改善 → 不能支持归因；轨迹缺失 → 缺证据不猜通过
        CollaborationEvaluation notImproved = evaluator.evaluate(input(true, false, false,
                List.of(lossy), List.of(), List.of(), List.of(), null,
                new CollaborationInput.InterventionReplay("e1", "pre", "post", false)));
        assertThat(checkOf(notImproved, CollaborationEvaluator.CHECK_INTERVENTION)
                .reasonCode()).isEqualTo("REPLAY_NOT_IMPROVED");
        assertThat(notImproved.supportedAttributions()).isEmpty();
        CollaborationEvaluation traceMissing = evaluator.evaluate(input(true, false, false,
                List.of(lossy), List.of(), List.of(), List.of(), null,
                new CollaborationInput.InterventionReplay("e1", null, "post", true)));
        assertThat(checkOf(traceMissing, CollaborationEvaluator.CHECK_INTERVENTION)
                .status()).isEqualTo(BehaviorCheckStatus.NOT_ASSESSED);
    }

    // ---------------------------------------------------------------- 步骤 6 / 指标面

    @Test
    @DisplayName("步骤 6 局部消融：恰好单因子差异 → PASS；多因子同变 → FAIL（不把多调用一个角色当成功）")
    void ablationSingleFactorOnly() {
        CollaborationEvaluation single = evaluator.evaluate(input(true, false, false,
                List.of(ok("e1", "metrics")), List.of(), List.of(), List.of(),
                new CollaborationInput.AblationPair(
                        arm("base", Set.of("logs", "metrics"), 50_000L),
                        arm("variant", Set.of("logs"), 50_000L)),
                null));
        BehaviorEvaluation.Check pass = checkOf(single, CollaborationEvaluator.CHECK_ABLATION);
        assertThat(pass.status()).isEqualTo(BehaviorCheckStatus.PASS);
        assertThat(pass.evidenceRefs()).containsExactly("tools");

        CollaborationEvaluation multi = evaluator.evaluate(input(true, false, false,
                List.of(ok("e1", "metrics")), List.of(), List.of(), List.of(),
                new CollaborationInput.AblationPair(
                        arm("base", Set.of("logs", "metrics"), 50_000L),
                        arm("variant", Set.of("logs"), 80_000L)),
                null));
        assertThat(checkOf(multi, CollaborationEvaluator.CHECK_ABLATION).reasonCode())
                .startsWith("MULTI_FACTOR_ABLATION");
        assertThat(multi.failureLabels()).contains("MULTI_FACTOR_ABLATION");
    }

    @Test
    @DisplayName("证据消费率（指标 3）：适用证据被忽略 → FAIL + MAST_IGNORED_PEER；非适用回执不强行要求采纳")
    void evidenceConsumptionRate() {
        CollaborationInput.HandoffEdge ignored = edge("e2", "logs",
                CollaborationInput.ChildOutcome.SUCCEEDED,
                CollaborationInput.Admission.ACCEPTED,
                List.of("fact-B"), List.of("fact-B"),
                true, false, 0, false, false, false, true, false, 90L, List.of(), false);
        CollaborationInput.HandoffEdge notApplicable = edge("e3", "change",
                CollaborationInput.ChildOutcome.SUCCEEDED,
                CollaborationInput.Admission.ACCEPTED,
                List.of("fact-C"), List.of("fact-C"),
                false, false, 0, false, false, false, true, false, 70L, List.of(), false);
        CollaborationEvaluation ev = evaluator.evaluate(
                edgesOnly(List.of(ok("e1", "metrics"), ignored, notApplicable)));
        BehaviorEvaluation.Check c = checkOf(ev,
                CollaborationEvaluator.CHECK_EVIDENCE_CONSUMPTION);
        assertThat(c.status()).isEqualTo(BehaviorCheckStatus.FAIL);
        assertThat(c.reasonCode()).isEqualTo("APPLICABLE_EVIDENCE_IGNORED");
        assertThat(c.evidenceRefs()).containsExactly("e2");
        BehaviorEvaluation.Metric m = metricsOf(ev)
                .get(CollaborationEvaluator.M_EVIDENCE_CONSUMPTION);
        assertThat(m.numerator()).isEqualTo(1);   // 非适用回执不进分母（不盲目采纳）
        assertThat(m.denominator()).isEqualTo(2);
        assertThat(ev.failureLabels()).contains(CollaborationEvaluation.MAST_IGNORED_PEER);
    }

    @Test
    @DisplayName("错误传播率（指标 5）：注入错误扩散到最终报告/其他 Agent → FAIL + MAST_TASK_DERAIL；被遏制 → PASS")
    void errorPropagationRate() {
        CollaborationInput.InjectedError contained = new CollaborationInput.InjectedError(
                "inj-1", "e1", false, false);
        CollaborationEvaluation okCase = evaluator.evaluate(input(true, false, false,
                List.of(ok("e1", "metrics")), List.of(), List.of(contained), List.of(),
                null, null));
        assertThat(checkOf(okCase, CollaborationEvaluator.CHECK_ERROR_CONTAINMENT).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
        assertThat(metricsOf(okCase)
                .get(CollaborationEvaluator.M_ERROR_PROPAGATION).numerator()).isEqualTo(0);

        CollaborationInput.InjectedError spread = new CollaborationInput.InjectedError(
                "inj-2", "e1", true, false);
        CollaborationEvaluation bad = evaluator.evaluate(input(true, false, false,
                List.of(ok("e1", "metrics")), List.of(), List.of(spread), List.of(),
                null, null));
        BehaviorEvaluation.Check c = checkOf(bad, CollaborationEvaluator.CHECK_ERROR_CONTAINMENT);
        assertThat(c.status()).isEqualTo(BehaviorCheckStatus.FAIL);
        assertThat(c.evidenceRefs()).containsExactly("e1");
        assertThat(metricsOf(bad).get(CollaborationEvaluator.M_ERROR_PROPAGATION)
                .numerator()).isEqualTo(1);
        assertThat(bad.failureLabels()).contains(CollaborationEvaluation.MAST_TASK_DERAIL);
    }

    @Test
    @DisplayName("角色成本：全部边有 token 台账 → 出数；任一边缺失 → 本案不出数不拼凑")
    void roleTokenCostHonestAccounting() {
        CollaborationEvaluation complete = evaluator.evaluate(
                edgesOnly(List.of(ok("e1", "metrics"), ok("e2", "logs"))));
        BehaviorEvaluation.Metric m = metricsOf(complete)
                .get(CollaborationEvaluator.M_ROLE_TOKEN_COST);
        assertThat(m.numerator()).isEqualTo(200);
        assertThat(m.denominator()).isEqualTo(2);

        CollaborationInput.HandoffEdge missing = edge("e2", "logs",
                CollaborationInput.ChildOutcome.SUCCEEDED,
                CollaborationInput.Admission.ACCEPTED,
                List.of("fact-B"), List.of("fact-B"),
                true, true, 1, false, false, false, true, false, null, List.of(), false);
        CollaborationEvaluation incomplete = evaluator.evaluate(
                edgesOnly(List.of(ok("e1", "metrics"), missing)));
        assertThat(metricsOf(incomplete))
                .doesNotContainKey(CollaborationEvaluator.M_ROLE_TOKEN_COST);
    }

    @Test
    @DisplayName("aggregate：跨案分子/分母求和；分母 0 如实不约分；deferred 三项涉真实模型不出数")
    void aggregateSumsNumeratorsAndDenominators() {
        CollaborationEvaluation a = evaluator.evaluate(edgesOnly(List.of(
                ok("e1", "metrics"), ok("e2", "logs"))));
        CollaborationInput.HandoffEdge lossy = edge("e1", "logs",
                CollaborationInput.ChildOutcome.SUCCEEDED,
                CollaborationInput.Admission.ACCEPTED,
                List.of("fact-A", "fact-B"), List.of("fact-A"),
                true, true, 1, false, false, false, true, false, 100L, List.of(), false);
        CollaborationEvaluation b = evaluator.evaluate(edgesOnly(List.of(lossy)));

        Map<String, BehaviorEvaluation.Metric> agg = CollaborationEvaluator
                .aggregate(List.of(a, b)).stream().collect(Collectors.toMap(
                        BehaviorEvaluation.Metric::name, Function.identity()));
        assertThat(agg.get(CollaborationEvaluator.M_DELEGATION_CHOICE).numerator())
                .isEqualTo(2);
        assertThat(agg.get(CollaborationEvaluator.M_DELEGATION_CHOICE).denominator())
                .isEqualTo(2);
        assertThat(agg.get(CollaborationEvaluator.M_HANDOFF_RETENTION).numerator())
                .isEqualTo(3);   // 2（a 案全保留）+ 1（b 案丢 1）
        assertThat(agg.get(CollaborationEvaluator.M_HANDOFF_RETENTION).denominator())
                .isEqualTo(4);

        assertThat(CollaborationEvaluator.DEFERRED).anySatisfy(s -> assertThat(s)
                        .contains("net_quality_gain"))
                .anySatisfy(s -> assertThat(s).contains("collaboration_overhead_vs_arm_a"))
                .anySatisfy(s -> assertThat(s).contains("role_capability_real_model"));
    }

    // ------------------------------------------------------------------ ME-T12a 未观测面扩展

    /** 生产投影形：真值标注/消费/适用性面全 null（未观测），仅台账可推导面有值 */
    private static CollaborationInput.HandoffEdge unobservedEdge(
            String id, String role, CollaborationInput.ChildOutcome outcome,
            CollaborationInput.Admission admission, List<String> digests) {
        return new CollaborationInput.HandoffEdge(id, "parent-task", id + "-child", role,
                1, null, null, List.of("gap-1"), outcome, admission,
                null, null, null, null, false, false, true, false, 100L, null, digests,
                null);
    }

    @Test
    @DisplayName("readError：观测读失败 ERROR 行——十三项检查全 ERROR，不出数不冒充零问题通过")
    void readErrorLandsAllErrorChecks() {
        CollaborationEvaluation ev = evaluator.readError();

        assertThat(ev.graderVersion()).isEqualTo(CollaborationEvaluator.GRADER_VERSION);
        assertThat(ev.checks()).hasSize(13).allSatisfy(c -> {
            assertThat(c.status()).isEqualTo(BehaviorCheckStatus.ERROR);
            assertThat(c.reasonCode()).isEqualTo("TRACE_READ_ERROR");
        });
        assertThat(ev.metrics()).isEmpty();
        assertThat(ev.failureLabels()).containsExactly("TRACE_READ_ERROR");
        assertThat(ev.suspectedAttributions()).isEmpty();
        assertThat(ev.supportedAttributions()).isEmpty();
    }

    @Test
    @DisplayName("handoffs=null（trace 缺失）→ 十三项检查全 NOT_ASSESSED（TRACE_MISSING），与零交接边严格区分")
    void nullHandoffsMarksTraceMissingNotAssessed() {
        CollaborationEvaluation ev = evaluator.evaluate(new CollaborationInput("C1", null,
                false, false, null, List.of(), List.of(), List.of(), null, null));

        assertThat(ev.checks()).hasSize(13).allSatisfy(c -> {
            assertThat(c.status()).isEqualTo(BehaviorCheckStatus.NOT_ASSESSED);
            assertThat(c.reasonCode()).isEqualTo("TRACE_MISSING");
        });
        assertThat(ev.metrics()).isEmpty();
        assertThat(ev.failureLabels()).containsExactly("TRACE_MISSING");
    }

    @Test
    @DisplayName("零交接边（空表）→ 机制面检查如实 NOT_APPLICABLE，不混同 trace 缺失")
    void emptyHandoffsStayNotApplicable() {
        CollaborationEvaluation ev = evaluator.evaluate(input(null, false, false,
                List.of(), List.of(), List.of(), List.of(), null, null));

        assertThat(checkOf(ev, CollaborationEvaluator.CHECK_HANDOFF_RETENTION).status())
                .isEqualTo(BehaviorCheckStatus.NOT_APPLICABLE);
        assertThat(checkOf(ev, CollaborationEvaluator.CHECK_RECEIPT_IDEMPOTENCY).status())
                .isEqualTo(BehaviorCheckStatus.NOT_APPLICABLE);
        assertThat(checkOf(ev, CollaborationEvaluator.CHECK_CANCELLATION).status())
                .isEqualTo(BehaviorCheckStatus.NOT_APPLICABLE);
        // 真值标注缺失单列 NOT_ASSESSED，不猜选择正确
        assertThat(checkOf(ev, CollaborationEvaluator.CHECK_DELEGATION_NECESSITY).status())
                .isEqualTo(BehaviorCheckStatus.NOT_ASSESSED);
    }

    @Test
    @DisplayName("交接内容/消费/适用性面 null（生产未观测）→ 保留率/回执幂等/证据消费 NOT_ASSESSED，不猜 PASS")
    void unobservedHandoffConsumptionApplicabilityNotAssessed() {
        CollaborationEvaluation ev = evaluator.evaluate(edgesOnly(List.of(
                unobservedEdge("e1", "metrics",
                        CollaborationInput.ChildOutcome.SUCCEEDED,
                        CollaborationInput.Admission.ACCEPTED, List.of()))));

        assertThat(checkOf(ev, CollaborationEvaluator.CHECK_HANDOFF_RETENTION).status())
                .isEqualTo(BehaviorCheckStatus.NOT_ASSESSED);
        assertThat(checkOf(ev, CollaborationEvaluator.CHECK_HANDOFF_RETENTION).reasonCode())
                .isEqualTo("HANDOFF_CONTENT_UNOBSERVED");
        assertThat(checkOf(ev, CollaborationEvaluator.CHECK_RECEIPT_IDEMPOTENCY).status())
                .isEqualTo(BehaviorCheckStatus.NOT_ASSESSED);
        assertThat(checkOf(ev, CollaborationEvaluator.CHECK_RECEIPT_IDEMPOTENCY).reasonCode())
                .isEqualTo("RECEIPT_CONSUMPTION_UNOBSERVED");
        assertThat(checkOf(ev, CollaborationEvaluator.CHECK_EVIDENCE_CONSUMPTION).status())
                .isEqualTo(BehaviorCheckStatus.NOT_ASSESSED);
        assertThat(checkOf(ev, CollaborationEvaluator.CHECK_EVIDENCE_CONSUMPTION).reasonCode())
                .isEqualTo("APPLICABILITY_UNOBSERVED");
        assertThat(metricsOf(ev))
                .doesNotContainKey(CollaborationEvaluator.M_HANDOFF_RETENTION)
                .doesNotContainKey(CollaborationEvaluator.M_EVIDENCE_CONSUMPTION);
        assertThat(ev.checks()).noneMatch(c -> c.status() == BehaviorCheckStatus.FAIL);
    }

    @Test
    @DisplayName("伪造判定面 null（降级边存在但无从比对）→ 有界降级 NOT_ASSESSED，不猜 PASS")
    void unobservedFabricationNotAssessed() {
        CollaborationEvaluation ev = evaluator.evaluate(edgesOnly(List.of(
                unobservedEdge("e1", "logs",
                        CollaborationInput.ChildOutcome.FAILED,
                        CollaborationInput.Admission.ACCEPTED, List.of()))));

        assertThat(checkOf(ev, CollaborationEvaluator.CHECK_BOUNDED_DEGRADATION).status())
                .isEqualTo(BehaviorCheckStatus.NOT_ASSESSED);
        assertThat(checkOf(ev, CollaborationEvaluator.CHECK_BOUNDED_DEGRADATION).reasonCode())
                .isEqualTo("FABRICATION_UNOBSERVED");
    }

    @Test
    @DisplayName("共享证据独立声称面 null → 重复取证 NOT_ASSESSED（计数指标如实不出），不猜 PASS")
    void unobservedIndependenceClaimNotAssessed() {
        CollaborationEvaluation ev = evaluator.evaluate(edgesOnly(List.of(
                unobservedEdge("e1", "metrics",
                        CollaborationInput.ChildOutcome.SUCCEEDED,
                        CollaborationInput.Admission.ACCEPTED, List.of("dg-1")),
                unobservedEdge("e2", "logs",
                        CollaborationInput.ChildOutcome.SUCCEEDED,
                        CollaborationInput.Admission.ACCEPTED, List.of("dg-1")))));

        assertThat(checkOf(ev, CollaborationEvaluator.CHECK_DUPLICATE_EVIDENCE).status())
                .isEqualTo(BehaviorCheckStatus.NOT_ASSESSED);
        assertThat(checkOf(ev, CollaborationEvaluator.CHECK_DUPLICATE_EVIDENCE).reasonCode())
                .isEqualTo("INDEPENDENCE_CLAIM_UNOBSERVED");
        assertThat(metricsOf(ev))
                .doesNotContainKey(CollaborationEvaluator.M_DUPLICATE_FETCH);
    }
}
