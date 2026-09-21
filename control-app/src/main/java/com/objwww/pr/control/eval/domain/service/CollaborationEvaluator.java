package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.eval.domain.model.BehaviorCheckStatus;
import com.objwww.pr.control.eval.domain.model.BehaviorEvaluation;
import com.objwww.pr.control.eval.domain.model.CollaborationEvaluation;
import com.objwww.pr.control.eval.domain.model.CollaborationInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 多 Agent 协作评测纯函数（ME-T07/D07；L0：不调 LLM、不碰 DB/HTTP）。对照
 * {@link BehaviorEvaluator}/{@link LoopTraceEvaluator}/{@link ContextDriftEvaluator}
 * 同族新检查面：角色质量、交接质量、系统收益分别测——输入为交接边投影 + 评分侧
 * 真值标注（{@link CollaborationInput}），机制面用脚本桩确定性注入；真实模型
 * 三臂对照（A 单主零委派 / B 固定角色 / C 动态委派）归夜间专项，不在本族出数。
 *
 * <p>检查面（MA-01～10 + 步骤 6 消融 + 证据消费率；缺证据不猜通过）：
 * <ul>
 *   <li>{@value #CHECK_DELEGATION_NECESSITY}（MA-01）：对评分侧标注的需要/无需协作
 *       案例，委派选择是否合适——无需协作案例发生委派 = 无谓协作成本；</li>
 *   <li>{@value #CHECK_CROSS_MODAL}（MA-02）：多模态案例最终支持须跨 ≥2 个可归属
 *       角色；</li>
 *   <li>{@value #CHECK_CONFLICT}（MA-03）：冲突按可靠性/时间/反证处置，不按投票
 *       数/置信措辞确认；</li>
 *   <li>{@value #CHECK_HANDOFF_RETENTION}（MA-04）：交接必需事实与约束在接收端的
 *       保留率（运行时硬权限阻断越权为既有 DelegationReceiptService/权限面，
 *       不在此重复）；</li>
 *   <li>{@value #CHECK_BOUNDED_DEGRADATION}（MA-05）：子任务超时/空集/未知时不得
 *       伪造子任务结论；</li>
 *   <li>{@value #CHECK_RECEIPT_IDEMPOTENCY}（MA-06）：回执不重复消费、迟到回执不
 *       合入、预算不重复结算；</li>
 *   <li>{@value #CHECK_CANCELLATION}（MA-07）：主任务取消后停止新派发、迟到结果不
 *       复活、在途成本可核对；</li>
 *   <li>{@value #CHECK_DUPLICATE_EVIDENCE}（MA-08）：共享证据计复用与重复成本，不
 *       得计成独立双重验证；</li>
 *   <li>{@value #CHECK_EVIDENCE_CONSUMPTION}（指标 3）：适用有效子任务证据被主
 *       Agent 消费的比例（不要求盲目采纳所有回执）；</li>
 *   <li>{@value #CHECK_COMPARABILITY}（MA-09）：对照臂五因子（工具/预算/超时/模型
 *       配置/数据快照）任一不同即 INCOMPARABLE，禁止归因多 Agent；</li>
 *   <li>{@value #CHECK_ABLATION}（步骤 6）：局部消融每次只变一个因子；</li>
 *   <li>{@value #CHECK_INTERVENTION}（MA-10）：替换错误回执后重放改善且双轨迹俱在
 *       才支持因果归因，否则只能疑似归因。</li>
 * </ul>
 * 七项指标中五项机制面出数（分子/分母），净质量收益与相对 A 臂协作开销涉真实
 * 模型如实列入 {@link #DEFERRED}。
 */
public final class CollaborationEvaluator {

    public static final String GRADER_VERSION = "collaboration-v1";

    public static final String CHECK_DELEGATION_NECESSITY = "delegation_necessity_choice";
    public static final String CHECK_CROSS_MODAL = "cross_modal_support";
    public static final String CHECK_CONFLICT = "conflict_resolution";
    public static final String CHECK_HANDOFF_RETENTION = "handoff_fact_constraint_retention";
    public static final String CHECK_BOUNDED_DEGRADATION = "bounded_degradation";
    public static final String CHECK_RECEIPT_IDEMPOTENCY = "receipt_consumption_idempotency";
    public static final String CHECK_CANCELLATION = "cancellation_fence";
    public static final String CHECK_DUPLICATE_EVIDENCE = "duplicate_evidence_accounting";
    public static final String CHECK_EVIDENCE_CONSUMPTION = "evidence_consumption";
    public static final String CHECK_ERROR_CONTAINMENT = "error_propagation_containment";
    public static final String CHECK_COMPARABILITY = "arm_comparability";
    public static final String CHECK_ABLATION = "ablation_single_factor";
    public static final String CHECK_INTERVENTION = "intervention_replay_attribution";

    // 七项指标名（REPORT D07 表；机制面出数项，分子/分母形态）
    public static final String M_DELEGATION_CHOICE = "delegation_choice_correct";
    public static final String M_HANDOFF_RETENTION = "handoff_fact_retention";
    public static final String M_EVIDENCE_CONSUMPTION = "evidence_consumption_rate";
    public static final String M_CONFLICT_RESOLUTION = "conflict_resolution_correct";
    public static final String M_ERROR_PROPAGATION = "error_propagation";
    public static final String M_DUPLICATE_FETCH = "duplicate_physical_fetches";
    public static final String M_ROLE_TOKEN_COST = "role_token_cost";

    /** 涉真实模型的指标（本版不出数，如实标注归夜间三臂专项） */
    public static final List<String> DEFERRED = List.of(
            "net_quality_gain:同案同预算 C−A 成功率/错误确认率及簇级置信区间——"
                    + "真实模型三臂（A 单主零委派/B 固定角色/C 动态委派）夜间专项，本版不出数",
            "collaboration_overhead_vs_arm_a:相对 A 臂的总成本/总 token/P95 延迟变化——"
                    + "需真实三臂跑批；本版只出机制面重复物理取证与角色 token 成本计数",
            "role_capability_real_model:metrics/logs/change 角色在相同证据下正确/可归属/"
                    + "可引用的真实模型能力（D07 步骤 3 模型面）——机制面只验归属结构，能力归专项");

    /** 十三项检查名全表（轨迹缺失/观测读失败整面落态用，定序=evaluate 出检序） */
    private static final List<String> ALL_CHECKS = List.of(
            CHECK_DELEGATION_NECESSITY, CHECK_CROSS_MODAL, CHECK_CONFLICT,
            CHECK_HANDOFF_RETENTION, CHECK_BOUNDED_DEGRADATION, CHECK_RECEIPT_IDEMPOTENCY,
            CHECK_CANCELLATION, CHECK_DUPLICATE_EVIDENCE, CHECK_EVIDENCE_CONSUMPTION,
            CHECK_ERROR_CONTAINMENT, CHECK_COMPARABILITY, CHECK_ABLATION,
            CHECK_INTERVENTION);

    private final String graderVersion;

    public CollaborationEvaluator() {
        this(GRADER_VERSION);
    }

    /** 显式 grader 版本（重评并存：新版本新行，旧记录不覆盖——D04 同律） */
    public CollaborationEvaluator(String graderVersion) {
        this.graderVersion = Objects.requireNonNull(graderVersion, "graderVersion 不得为 null");
    }

    public CollaborationEvaluation evaluate(CollaborationInput in) {
        Objects.requireNonNull(in, "in 不得为 null");
        // 轨迹缺失（handoffs=null，ME-T12a 加式扩展）：整面 NOT_ASSESSED 不猜——
        // 与零交接边（空表，如实 NOT_APPLICABLE）严格区分
        if (in.handoffs() == null) {
            List<BehaviorEvaluation.Check> missing = ALL_CHECKS.stream()
                    .map(n -> check(n, BehaviorCheckStatus.NOT_ASSESSED, "TRACE_MISSING",
                            List.of()))
                    .toList();
            return new CollaborationEvaluation(graderVersion, in.caseId(), missing,
                    List.of(), List.of("TRACE_MISSING"), List.of(), List.of());
        }
        List<BehaviorEvaluation.Check> checks = new ArrayList<>();
        List<BehaviorEvaluation.Metric> metrics = new ArrayList<>();
        List<String> failureLabels = new ArrayList<>();
        List<String> suspected = new ArrayList<>();
        List<String> supported = new ArrayList<>();
        List<CollaborationInput.HandoffEdge> edges = in.handoffs();
        boolean delegationUsed = !edges.isEmpty();

        // ---------------- 1. 委派必要性/选择正确率（MA-01） ----------------
        if (in.collaborationNeeded() == null) {
            checks.add(check(CHECK_DELEGATION_NECESSITY, BehaviorCheckStatus.NOT_ASSESSED,
                    "NEED_LABEL_MISSING", List.of()));
        } else if (in.collaborationNeeded() == delegationUsed) {
            checks.add(check(CHECK_DELEGATION_NECESSITY, BehaviorCheckStatus.PASS,
                    "CHOICE_APPROPRIATE", List.of()));
            metrics.add(new BehaviorEvaluation.Metric(M_DELEGATION_CHOICE, 1, 1));
        } else {
            String reason = in.collaborationNeeded()
                    ? "COLLABORATION_OMITTED" : "UNNECESSARY_DELEGATION";
            checks.add(check(CHECK_DELEGATION_NECESSITY, BehaviorCheckStatus.FAIL, reason,
                    delegationUsed ? List.of(edges.get(0).edgeId()) : List.of()));
            metrics.add(new BehaviorEvaluation.Metric(M_DELEGATION_CHOICE, 0, 1));
            fail(failureLabels, suspected, "DELEGATION_CHOICE_WRONG",
                    delegationUsed ? edges.get(0).edgeId() : null);
        }

        // ---------------- 2. 跨模态支持与角色归属（MA-02） ----------------
        if (!in.multiModalCase()) {
            checks.add(check(CHECK_CROSS_MODAL, BehaviorCheckStatus.NOT_APPLICABLE,
                    "SINGLE_MODAL", List.of()));
        } else {
            List<CollaborationInput.HandoffEdge> supporting = edges.stream()
                    .filter(e -> e.validMerged() && Boolean.TRUE.equals(e.consumedByPrimary()))
                    .toList();
            List<String> roles = supporting.stream().map(CollaborationInput.HandoffEdge::roleId)
                    .filter(r -> !r.isBlank()).distinct().toList();
            boolean unattributable = supporting.stream().anyMatch(e -> e.roleId().isBlank());
            if (unattributable) {
                checks.add(check(CHECK_CROSS_MODAL, BehaviorCheckStatus.FAIL,
                        "ROLE_UNATTRIBUTABLE", List.of(supporting.stream()
                                .filter(e -> e.roleId().isBlank()).findFirst().orElseThrow()
                                .edgeId())));
                fail(failureLabels, suspected, CollaborationEvaluation.MAST_VERIFICATION_INADEQUATE,
                        supporting.stream().filter(e -> e.roleId().isBlank())
                                .findFirst().orElseThrow().edgeId());
            } else if (roles.size() >= 2) {
                checks.add(check(CHECK_CROSS_MODAL, BehaviorCheckStatus.PASS,
                        "CROSS_MODAL_SUPPORT", List.copyOf(roles)));
            } else {
                checks.add(check(CHECK_CROSS_MODAL, BehaviorCheckStatus.FAIL,
                        "NO_CROSS_MODAL_SUPPORT", List.copyOf(roles)));
                fail(failureLabels, suspected, CollaborationEvaluation.MAST_VERIFICATION_INADEQUATE,
                        null);
            }
        }

        // ---------------- 3. 冲突处置（MA-03） ----------------
        if (in.conflicts().isEmpty()) {
            checks.add(check(CHECK_CONFLICT, BehaviorCheckStatus.NOT_APPLICABLE,
                    "NO_CONFLICT", List.of()));
        } else {
            long correct = in.conflicts().stream().filter(
                    CollaborationInput.ConflictCase::resolvedCorrectly).count();
            metrics.add(new BehaviorEvaluation.Metric(M_CONFLICT_RESOLUTION, correct,
                    in.conflicts().size()));
            CollaborationInput.ConflictCase firstBad = in.conflicts().stream()
                    .filter(c -> !c.resolvedCorrectly()).findFirst().orElse(null);
            if (firstBad == null) {
                checks.add(check(CHECK_CONFLICT, BehaviorCheckStatus.PASS,
                        "CONFLICT_GROUNDED", List.of()));
            } else {
                String reason = switch (firstBad.basis()) {
                    case MAJORITY_VOTE, CONFIDENCE_WORDING -> "CONFLICT_BY_VOTE_OR_CONFIDENCE";
                    case NONE -> "CONFLICT_NO_BASIS";
                    default -> "CONFLICT_WRONG_RESOLUTION";
                };
                checks.add(check(CHECK_CONFLICT, BehaviorCheckStatus.FAIL, reason,
                        List.of(firstBad.conflictId())));
                fail(failureLabels, suspected, CollaborationEvaluation.MAST_IGNORED_PEER,
                        firstBad.conflictId());
            }
        }

        // ---------------- 4. 交接事实/约束保留率（MA-04；指标 2） ----------------
        if (edges.isEmpty()) {
            checks.add(check(CHECK_HANDOFF_RETENTION, BehaviorCheckStatus.NOT_APPLICABLE,
                    "NO_HANDOFF", List.of()));
        } else if (edges.stream().anyMatch(e -> e.sentFacts() == null
                || e.preservedFacts() == null)) {
            // 交接内容未观测（生产投影无传出/保留事实面）——不猜通过（ME-T12a）
            checks.add(check(CHECK_HANDOFF_RETENTION, BehaviorCheckStatus.NOT_ASSESSED,
                    "HANDOFF_CONTENT_UNOBSERVED", List.of()));
        } else {
            long required = 0;
            long preserved = 0;
            CollaborationInput.HandoffEdge firstLoss = null;
            for (CollaborationInput.HandoffEdge e : edges) {
                required += e.sentFacts().size();
                long kept = e.sentFacts().stream().filter(e.preservedFacts()::contains).count();
                preserved += kept;
                if (firstLoss == null && kept < e.sentFacts().size()) {
                    firstLoss = e;
                }
            }
            if (required > 0) {
                metrics.add(new BehaviorEvaluation.Metric(M_HANDOFF_RETENTION, preserved,
                        required));
            }
            if (firstLoss == null) {
                checks.add(check(CHECK_HANDOFF_RETENTION, BehaviorCheckStatus.PASS,
                        "HANDOFF_FACTS_PRESERVED", List.of()));
            } else {
                checks.add(check(CHECK_HANDOFF_RETENTION, BehaviorCheckStatus.FAIL,
                        "HANDOFF_FACT_MISSING", List.of(firstLoss.edgeId())));
                fail(failureLabels, suspected, CollaborationEvaluation.MAST_INFORMATION_LOSS,
                        firstLoss.edgeId());
            }
        }

        // ---------------- 5. 有界降级（MA-05） ----------------
        List<CollaborationInput.HandoffEdge> degraded = edges.stream()
                .filter(e -> !e.validMerged()).toList();
        if (degraded.isEmpty()) {
            checks.add(check(CHECK_BOUNDED_DEGRADATION, BehaviorCheckStatus.NOT_APPLICABLE,
                    "NO_DEGRADED_EDGE", List.of()));
        } else if (degraded.stream().anyMatch(e -> e.fabricatedConclusion() == null)) {
            // 伪造判定面未观测（生产投影无结论比对面）——不猜通过（ME-T12a）
            checks.add(check(CHECK_BOUNDED_DEGRADATION, BehaviorCheckStatus.NOT_ASSESSED,
                    "FABRICATION_UNOBSERVED", List.of()));
        } else {
            CollaborationInput.HandoffEdge fabricated = degraded.stream()
                    .filter(e -> Boolean.TRUE.equals(e.fabricatedConclusion()))
                    .findFirst().orElse(null);
            if (fabricated == null) {
                checks.add(check(CHECK_BOUNDED_DEGRADATION, BehaviorCheckStatus.PASS,
                        "BOUNDED_DEGRADATION", List.of()));
            } else {
                checks.add(check(CHECK_BOUNDED_DEGRADATION, BehaviorCheckStatus.FAIL,
                        "FABRICATED_CHILD_CONCLUSION", List.of(fabricated.edgeId())));
                fail(failureLabels, suspected, CollaborationEvaluation.MAST_TASK_DERAIL,
                        fabricated.edgeId());
            }
        }

        // ---------------- 6. 回执消费幂等（MA-06） ----------------
        if (edges.isEmpty()) {
            checks.add(check(CHECK_RECEIPT_IDEMPOTENCY, BehaviorCheckStatus.NOT_APPLICABLE,
                    "NO_RECEIPT", List.of()));
        } else if (edges.stream().anyMatch(e -> e.consumptionCount() == null
                || e.consumedByPrimary() == null)) {
            // 消费面未观测（生产投影无合并面消费计数）——不猜通过（ME-T12a）
            checks.add(check(CHECK_RECEIPT_IDEMPOTENCY, BehaviorCheckStatus.NOT_ASSESSED,
                    "RECEIPT_CONSUMPTION_UNOBSERVED", List.of()));
        } else {
            CollaborationInput.HandoffEdge doubleConsumed = edges.stream()
                    .filter(e -> e.consumptionCount() > 1).findFirst().orElse(null);
            CollaborationInput.HandoffEdge lateConsumed = edges.stream()
                    .filter(e -> e.admission() == CollaborationInput.Admission.LATE
                            && Boolean.TRUE.equals(e.consumedByPrimary()))
                    .findFirst().orElse(null);
            CollaborationInput.HandoffEdge doubleSettled = edges.stream()
                    .filter(CollaborationInput.HandoffEdge::costSettledTwice)
                    .findFirst().orElse(null);
            if (doubleConsumed == null && lateConsumed == null && doubleSettled == null) {
                checks.add(check(CHECK_RECEIPT_IDEMPOTENCY, BehaviorCheckStatus.PASS,
                        "RECEIPT_CONSUMED_ONCE", List.of()));
            } else {
                String reason = doubleConsumed != null ? "RECEIPT_DOUBLE_CONSUMPTION"
                        : lateConsumed != null ? "LATE_RECEIPT_CONSUMED"
                        : "BUDGET_DOUBLE_SETTLED";
                CollaborationInput.HandoffEdge first = doubleConsumed != null ? doubleConsumed
                        : lateConsumed != null ? lateConsumed : doubleSettled;
                checks.add(check(CHECK_RECEIPT_IDEMPOTENCY, BehaviorCheckStatus.FAIL, reason,
                        List.of(first.edgeId())));
                fail(failureLabels, suspected, CollaborationEvaluation.MAST_REPETITION,
                        first.edgeId());
            }
        }

        // ---------------- 7. 取消围栏（MA-07） ----------------
        if (!in.primaryCancelled()) {
            checks.add(check(CHECK_CANCELLATION, BehaviorCheckStatus.NOT_APPLICABLE,
                    "NOT_CANCELLED", List.of()));
        } else {
            CollaborationInput.HandoffEdge dispatched = edges.stream()
                    .filter(CollaborationInput.HandoffEdge::dispatchedAfterCancel)
                    .findFirst().orElse(null);
            CollaborationInput.HandoffEdge revived = edges.stream()
                    .filter(CollaborationInput.HandoffEdge::mergedAfterCancel)
                    .findFirst().orElse(null);
            CollaborationInput.HandoffEdge unsettled = edges.stream()
                    .filter(e -> !e.inFlightCostSettled()).findFirst().orElse(null);
            if (dispatched == null && revived == null && unsettled == null) {
                checks.add(check(CHECK_CANCELLATION, BehaviorCheckStatus.PASS,
                        "CANCEL_FENCED", List.of()));
            } else {
                String reason = dispatched != null ? "DISPATCH_AFTER_CANCEL"
                        : revived != null ? "LATE_RESULT_REVIVED" : "IN_FLIGHT_COST_UNSETTLED";
                CollaborationInput.HandoffEdge first = dispatched != null ? dispatched
                        : revived != null ? revived : unsettled;
                checks.add(check(CHECK_CANCELLATION, BehaviorCheckStatus.FAIL, reason,
                        List.of(first.edgeId())));
                fail(failureLabels, suspected, CollaborationEvaluation.MAST_TASK_DERAIL,
                        first.edgeId());
            }
        }

        // ---------------- 8. 重复取证计量（MA-08；协作开销机制面） ----------------
        Map<String, long[]> digestFetches = new LinkedHashMap<>();
        for (CollaborationInput.HandoffEdge e : edges) {
            for (String digest : e.fetchedEvidenceDigests()) {
                digestFetches.computeIfAbsent(digest, k -> new long[1])[0]++;
            }
        }
        long totalFetches = digestFetches.values().stream().mapToLong(s -> s[0]).sum();
        long extraFetches = digestFetches.values().stream()
                .mapToLong(s -> Math.max(0, s[0] - 1)).sum();
        boolean shared = digestFetches.values().stream().anyMatch(s -> s[0] > 1);
        if (!shared) {
            checks.add(check(CHECK_DUPLICATE_EVIDENCE, BehaviorCheckStatus.NOT_APPLICABLE,
                    "NO_SHARED_EVIDENCE", List.of()));
        } else if (edges.stream().anyMatch(e -> e.sharedDigestClaimedIndependent() == null)) {
            // 独立验证声称面未观测——不猜通过（ME-T12a）
            checks.add(check(CHECK_DUPLICATE_EVIDENCE, BehaviorCheckStatus.NOT_ASSESSED,
                    "INDEPENDENCE_CLAIM_UNOBSERVED", List.of()));
        } else {
            metrics.add(new BehaviorEvaluation.Metric(M_DUPLICATE_FETCH, extraFetches,
                    totalFetches));
            CollaborationInput.HandoffEdge independent = edges.stream()
                    .filter(e -> Boolean.TRUE.equals(e.sharedDigestClaimedIndependent()))
                    .findFirst().orElse(null);
            if (independent == null) {
                checks.add(check(CHECK_DUPLICATE_EVIDENCE, BehaviorCheckStatus.PASS,
                        "DUPLICATE_COUNTED_AS_REUSE", List.of()));
            } else {
                checks.add(check(CHECK_DUPLICATE_EVIDENCE, BehaviorCheckStatus.FAIL,
                        "SHARED_EVIDENCE_DOUBLE_VERIFICATION", List.of(independent.edgeId())));
                fail(failureLabels, suspected,
                        CollaborationEvaluation.MAST_VERIFICATION_INADEQUATE,
                        independent.edgeId());
            }
        }

        // ---------------- 9. 证据消费率（指标 3） ----------------
        if (edges.stream().anyMatch(e -> e.applicableEvidence() == null)) {
            // 适用性标注面未观测（生产投影无评分侧真值标注）——不猜通过（ME-T12a）
            checks.add(check(CHECK_EVIDENCE_CONSUMPTION, BehaviorCheckStatus.NOT_ASSESSED,
                    "APPLICABILITY_UNOBSERVED", List.of()));
        } else {
            List<CollaborationInput.HandoffEdge> applicable = edges.stream()
                    .filter(e -> Boolean.TRUE.equals(e.applicableEvidence())
                            && e.validMerged())
                    .toList();
            if (applicable.isEmpty()) {
                checks.add(check(CHECK_EVIDENCE_CONSUMPTION,
                        BehaviorCheckStatus.NOT_APPLICABLE,
                        "NO_APPLICABLE_EVIDENCE", List.of()));
            } else {
                long consumed = applicable.stream()
                        .filter(e -> Boolean.TRUE.equals(e.consumedByPrimary())).count();
                metrics.add(new BehaviorEvaluation.Metric(M_EVIDENCE_CONSUMPTION, consumed,
                        applicable.size()));
                CollaborationInput.HandoffEdge ignored = applicable.stream()
                        .filter(e -> !Boolean.TRUE.equals(e.consumedByPrimary()))
                        .findFirst().orElse(null);
                if (ignored == null) {
                    checks.add(check(CHECK_EVIDENCE_CONSUMPTION, BehaviorCheckStatus.PASS,
                            "APPLICABLE_EVIDENCE_CONSUMED", List.of()));
                } else {
                    checks.add(check(CHECK_EVIDENCE_CONSUMPTION, BehaviorCheckStatus.FAIL,
                            "APPLICABLE_EVIDENCE_IGNORED", List.of(ignored.edgeId())));
                    fail(failureLabels, suspected,
                            CollaborationEvaluation.MAST_IGNORED_PEER, ignored.edgeId());
                }
            }
        }

        // ---------------- 10. 错误传播率（指标 5） ----------------
        if (in.injectedErrors().isEmpty()) {
            checks.add(check(CHECK_ERROR_CONTAINMENT, BehaviorCheckStatus.NOT_APPLICABLE,
                    "NO_INJECTED_ERROR", List.of()));
        } else {
            long propagated = in.injectedErrors().stream()
                    .filter(CollaborationInput.InjectedError::propagated).count();
            metrics.add(new BehaviorEvaluation.Metric(M_ERROR_PROPAGATION, propagated,
                    in.injectedErrors().size()));
            CollaborationInput.InjectedError first = in.injectedErrors().stream()
                    .filter(CollaborationInput.InjectedError::propagated)
                    .findFirst().orElse(null);
            if (first == null) {
                checks.add(check(CHECK_ERROR_CONTAINMENT, BehaviorCheckStatus.PASS,
                        "ERROR_CONTAINED", List.of()));
            } else {
                checks.add(check(CHECK_ERROR_CONTAINMENT, BehaviorCheckStatus.FAIL,
                        "ERROR_PROPAGATED", List.of(first.sourceEdgeId())));
                fail(failureLabels, suspected, CollaborationEvaluation.MAST_TASK_DERAIL,
                        first.sourceEdgeId());
            }
        }

        // ---------------- 11. 对照臂可比性（MA-09；步骤 5 机制面） ----------------
        if (in.arms().size() < 2) {
            checks.add(check(CHECK_COMPARABILITY, BehaviorCheckStatus.NOT_APPLICABLE,
                    "SINGLE_ARM", List.of()));
        } else {
            CollaborationInput.ArmConfig reference = in.arms().get(0);
            List<String> offenders = new ArrayList<>();
            List<String> factorSummary = new ArrayList<>();
            for (int i = 1; i < in.arms().size(); i++) {
                List<String> factors = CollaborationInput.ArmConfig.changedFactors(
                        reference, in.arms().get(i));
                if (!factors.isEmpty()) {
                    offenders.add(in.arms().get(i).armId());
                    factorSummary.addAll(factors);
                }
            }
            if (offenders.isEmpty()) {
                checks.add(check(CHECK_COMPARABILITY, BehaviorCheckStatus.PASS,
                        "ARMS_COMPARABLE", List.of()));
            } else {
                checks.add(check(CHECK_COMPARABILITY, BehaviorCheckStatus.FAIL,
                        "ARM_INCOMPARABLE:" + String.join(",",
                                factorSummary.stream().distinct().sorted().toList()),
                        List.copyOf(offenders)));
                fail(failureLabels, suspected, "ARM_INCOMPARABLE", offenders.get(0));
            }
        }

        // ---------------- 12. 局部消融单因子（步骤 6） ----------------
        if (in.ablation() == null) {
            checks.add(check(CHECK_ABLATION, BehaviorCheckStatus.NOT_APPLICABLE,
                    "NO_ABLATION", List.of()));
        } else {
            List<String> factors = CollaborationInput.ArmConfig.changedFactors(
                    in.ablation().base(), in.ablation().variant());
            if (factors.size() == 1) {
                checks.add(check(CHECK_ABLATION, BehaviorCheckStatus.PASS,
                        "SINGLE_FACTOR_ABLATION", List.of(factors.get(0))));
            } else {
                checks.add(check(CHECK_ABLATION, BehaviorCheckStatus.FAIL,
                        "MULTI_FACTOR_ABLATION:" + String.join(",", factors),
                        List.of(in.ablation().variant().armId())));
                fail(failureLabels, suspected, "MULTI_FACTOR_ABLATION",
                        in.ablation().variant().armId());
            }
        }

        // ---------------- 13. 干预重放归因（MA-10；步骤 7） ----------------
        if (in.intervention() == null) {
            checks.add(check(CHECK_INTERVENTION, BehaviorCheckStatus.NOT_APPLICABLE,
                    "NO_INTERVENTION_CONTROL", List.of()));
        } else if (in.intervention().preTraceDigest() == null
                || in.intervention().postTraceDigest() == null) {
            checks.add(check(CHECK_INTERVENTION, BehaviorCheckStatus.NOT_ASSESSED,
                    "TRACE_MISSING", List.of(in.intervention().replacedEdgeId())));
        } else if (in.intervention().postReplayImproved()) {
            checks.add(check(CHECK_INTERVENTION, BehaviorCheckStatus.PASS,
                    "REPLAY_IMPROVED_AFTER_RECEIPT_FIX",
                    List.of(in.intervention().replacedEdgeId())));
            supported.add("edge:" + in.intervention().replacedEdgeId()
                    + " 替换错误回执后重放改善（干预前后轨迹俱在），支持该交接边因果归因");
        } else {
            checks.add(check(CHECK_INTERVENTION, BehaviorCheckStatus.FAIL,
                    "REPLAY_NOT_IMPROVED", List.of(in.intervention().replacedEdgeId())));
        }

        // 角色 token 成本（协作开销机制面）：任一边缺失即本案不出数，不拼凑
        if (!edges.isEmpty() && edges.stream().allMatch(e -> e.tokenCost() != null)) {
            long sum = edges.stream().mapToLong(CollaborationInput.HandoffEdge::tokenCost).sum();
            metrics.add(new BehaviorEvaluation.Metric(M_ROLE_TOKEN_COST, sum, edges.size()));
        }

        return new CollaborationEvaluation(graderVersion, in.caseId(), checks, metrics,
                failureLabels, suspected, supported);
    }

    /** 观测读失败 ERROR 行（SAFE-07 同律：读失败如实落 ERROR，不冒充零问题通过；
     *  十三项检查全 ERROR，metrics/归因不出数） */
    public CollaborationEvaluation readError() {
        List<BehaviorEvaluation.Check> checks = ALL_CHECKS.stream()
                .map(n -> check(n, BehaviorCheckStatus.ERROR, "TRACE_READ_ERROR",
                        List.of()))
                .toList();
        return new CollaborationEvaluation(graderVersion, "~read-error~", checks,
                List.of(), List.of("TRACE_READ_ERROR"), List.of(), List.of());
    }

    /**
     * 机制面指标聚合（REPORT D07 表五项出数项 + 开销机制面两项；分子/分母形态，
     * 分母 0 = 口径内无案例如实不约分）。net_quality_gain 与相对 A 臂开销涉真实
     * 模型三臂，不在此聚合（见 {@link #DEFERRED}）。
     */
    public static List<BehaviorEvaluation.Metric> aggregate(List<CollaborationEvaluation> cases) {
        Objects.requireNonNull(cases, "cases 不得为 null");
        Map<String, long[]> sums = new LinkedHashMap<>();
        for (CollaborationEvaluation ev : cases) {
            for (BehaviorEvaluation.Metric m : ev.metrics()) {
                long[] slot = sums.computeIfAbsent(m.name(), k -> new long[2]);
                slot[0] += m.numerator();
                slot[1] += m.denominator();
            }
        }
        List<BehaviorEvaluation.Metric> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : sums.entrySet()) {
            out.add(new BehaviorEvaluation.Metric(e.getKey(), e.getValue()[0],
                    e.getValue()[1]));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ 内部

    private static BehaviorEvaluation.Check check(String name, BehaviorCheckStatus status,
                                                  String reason, List<String> refs) {
        return new BehaviorEvaluation.Check(name, status, reason, refs);
    }

    /**
     * 失败登记：MAST/机制标签 + 疑似归因（无干预对照只叫疑似——D07 步骤 7；
     * 首个可观察出错事件随 edgeRef 留痕）。
     */
    private static void fail(List<String> failureLabels, List<String> suspected,
                             String label, String edgeRef) {
        if (!failureLabels.contains(label)) {
            failureLabels.add(label);
        }
        suspected.add(label + (edgeRef != null ? " @ " + edgeRef : "")
                + "（疑似归因，无干预对照）");
    }
}
