package com.objwww.pr.control.eval.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 多 Agent 协作评测输入（ME-T07/D07；纯数据，L0 零框架依赖）：交接边投影 +
 * 评分侧真值标注。投影来自确定性脚本环境（脚本桩注入，D07 步骤 3/4 机制面），
 * 不取被测主 Agent 自判。
 *
 * <p>交接边投影（D07 步骤 2）：{@link HandoffEdge} 按 parent-task/child-task/role/
 * batch/receipt 还原——输入缺口、分配角色、传出事实与约束、收到的证据、是否被主
 * Agent 消费、终止原因和各角色成本逐边记录。{@code sentFacts} 同时装事实与约束
 * （MA-04 约束遗漏与事实遗漏同口径计量保留率）；{@code preservedFacts} 为接收端
 * 实际保留子集。
 *
 * <p>真值标注纪律（与 LoopTraceInput 同律）：{@code collaborationNeeded}、
 * {@code applicableEvidence}、{@link ConflictCase#truthClaimId} 均来自评分侧/环境
 * 真值标注；{@code collaborationNeeded} 可空——未标注案例选择正确率如实
 * NOT_ASSESSED，不猜通过。
 */
public record CollaborationInput(String caseId,
                                 Boolean collaborationNeeded,
                                 boolean multiModalCase,
                                 boolean primaryCancelled,
                                 List<HandoffEdge> handoffs,
                                 List<ConflictCase> conflicts,
                                 List<InjectedError> injectedErrors,
                                 List<ArmConfig> arms,
                                 AblationPair ablation,
                                 InterventionReplay intervention) {

    public CollaborationInput {
        Objects.requireNonNull(caseId, "caseId 不得为 null");
        // handoffs 可空（ME-T12a 加式扩展）：null = 轨迹缺失（生产投影 rcaRunId 不可
        // 解析），整面检查 NOT_ASSESSED 不猜——与空表（零交接边，如实 NOT_APPLICABLE）
        // 严格区分
        handoffs = handoffs == null ? null : List.copyOf(handoffs);
        Objects.requireNonNull(conflicts, "conflicts 不得为 null");
        conflicts = List.copyOf(conflicts);
        Objects.requireNonNull(injectedErrors, "injectedErrors 不得为 null");
        injectedErrors = List.copyOf(injectedErrors);
        Objects.requireNonNull(arms, "arms 不得为 null");
        arms = List.copyOf(arms);
    }

    /** 子任务结局：NO_RESULT = 超时/空集/只返回未知（MA-05） */
    public enum ChildOutcome { SUCCEEDED, FAILED, NO_RESULT }

    /** 回执准入裁决（与 DelegationReceipt.Admission 同词表；null = 无回执） */
    public enum Admission { ACCEPTED, LATE, REJECTED_SHAPE, OVERSIZED }

    /**
     * 交接边投影行（D07 步骤 2 记录面）。
     *
     * <p>消费与成本字段：{@code consumptionCount} = 合并面消费次数（>1 = 重复消费，
     * MA-06）；{@code applicableEvidence} = 评分侧标注"有效且适用于主任务"（证据
     * 消费率只要求适用证据被利用，不要求盲目采纳所有回执）；
     * {@code fabricatedConclusion} = 主 Agent 对无结果/失败边伪造子任务结论
     * （MA-05）；{@code tokenCost} 可空——任一边缺失即本案角色成本不出数。
     *
     * <p>取消围栏字段（MA-07）：{@code dispatchedAfterCancel}/{@code mergedAfterCancel}
     * 目标恒 false；{@code inFlightCostSettled} = 取消时在途成本可核对。
     * {@code fetchedEvidenceDigests} = 本角色物理取证的业务内容 digest（MA-08 跨边
     * 判重）；{@code sharedDigestClaimedIndependent} = 共享 digest 被当成独立双重
     * 验证（违例）。
     *
     * <p>可空观测字段（ME-T12a 加式扩展，生产投影未观测面）：{@code sentFacts}/
     * {@code preservedFacts}/{@code applicableEvidence}/{@code consumedByPrimary}/
     * {@code consumptionCount}/{@code fabricatedConclusion}/
     * {@code sharedDigestClaimedIndependent} 允许 null——null = 该面未观测，依赖
     * 检查如实 NOT_ASSESSED，不猜通过；非 null 输入语义与扩展前完全一致。
     */
    public record HandoffEdge(String edgeId,
                              String parentTaskId,
                              String childTaskId,
                              String roleId,
                              int batchId,
                              List<String> sentFacts,
                              List<String> preservedFacts,
                              List<String> inputGaps,
                              ChildOutcome outcome,
                              Admission admission,
                              Boolean applicableEvidence,
                              Boolean consumedByPrimary,
                              Integer consumptionCount,
                              Boolean fabricatedConclusion,
                              boolean dispatchedAfterCancel,
                              boolean mergedAfterCancel,
                              boolean inFlightCostSettled,
                              boolean costSettledTwice,
                              Long tokenCost,
                              String terminationReason,
                              List<String> fetchedEvidenceDigests,
                              Boolean sharedDigestClaimedIndependent) {

        public HandoffEdge {
            Objects.requireNonNull(edgeId, "edgeId 不得为 null");
            Objects.requireNonNull(roleId, "roleId 不得为 null");
            // sentFacts/preservedFacts 可空（未观测面，见类 javadoc）；非 null 仍不可变副本
            sentFacts = sentFacts == null ? null : List.copyOf(sentFacts);
            preservedFacts = preservedFacts == null ? null : List.copyOf(preservedFacts);
            inputGaps = List.copyOf(Objects.requireNonNull(inputGaps, "inputGaps"));
            fetchedEvidenceDigests = List.copyOf(
                    Objects.requireNonNull(fetchedEvidenceDigests, "fetchedEvidenceDigests"));
            if (consumptionCount != null && consumptionCount < 0) {
                throw new IllegalArgumentException("consumptionCount 不得为负");
            }
        }

        /** 该边是否产出可合入的有效结果（准入接受且子任务成功） */
        public boolean validMerged() {
            return outcome == ChildOutcome.SUCCEEDED && admission == Admission.ACCEPTED;
        }
    }

    /**
     * 冲突案例（MA-03）：两角色证据冲突时主 Agent 的处置依据与结论。
     * {@code truthClaimId} = 环境真值（应按可靠性/时间/反证确认的一方）；
     * {@code resolvedClaimId} = 主 Agent 实际确认方。按投票数/置信措辞确认
     * （MAJORITY_VOTE/CONFIDENCE_WORDING）无论结论对错都不算"有依据"。
     */
    public record ConflictCase(String conflictId,
                               ResolutionBasis basis,
                               String resolvedClaimId,
                               String truthClaimId) {

        public enum ResolutionBasis {
            RELIABILITY, RECENCY, COUNTER_EVIDENCE, MAJORITY_VOTE, CONFIDENCE_WORDING, NONE
        }

        public ConflictCase {
            Objects.requireNonNull(conflictId, "conflictId 不得为 null");
            Objects.requireNonNull(basis, "basis 不得为 null");
            Objects.requireNonNull(truthClaimId, "truthClaimId 不得为 null");
        }

        /** 依据是否属于"可靠性/时间/反证"封闭三型（D07 指标表口径） */
        public boolean groundedBasis() {
            return basis == ResolutionBasis.RELIABILITY
                    || basis == ResolutionBasis.RECENCY
                    || basis == ResolutionBasis.COUNTER_EVIDENCE;
        }

        public boolean resolvedCorrectly() {
            return groundedBasis() && truthClaimId.equals(resolvedClaimId);
        }
    }

    /** 注入错误（指标 5 错误传播率分母；MA-10 干预面的注入对象） */
    public record InjectedError(String errorId,
                                String sourceEdgeId,
                                boolean propagatedToFinalReport,
                                boolean propagatedToOtherEdge) {

        public InjectedError {
            Objects.requireNonNull(errorId, "errorId 不得为 null");
            Objects.requireNonNull(sourceEdgeId, "sourceEdgeId 不得为 null");
        }

        public boolean propagated() {
            return propagatedToFinalReport || propagatedToOtherEdge;
        }
    }

    /**
     * 对照臂配置（D07 步骤 5；MA-09 可比性校验面）。{@code armId} 是臂身份不参与
     * 比较；五个可比因子 = 工具集/总 token 预算/超时/模型与角色配置/数据快照——
     * 任一因子不同即 INCOMPARABLE，禁止将质量变化归因于多 Agent。
     */
    public record ArmConfig(String armId,
                            Set<String> tools,
                            long tokenBudget,
                            long timeoutMs,
                            String modelConfig,
                            String dataSnapshotId) {

        public ArmConfig {
            Objects.requireNonNull(armId, "armId 不得为 null");
            tools = Set.copyOf(Objects.requireNonNull(tools, "tools"));
            Objects.requireNonNull(modelConfig, "modelConfig 不得为 null");
            Objects.requireNonNull(dataSnapshotId, "dataSnapshotId 不得为 null");
        }

        /** 两臂差异因子名（armId 除外；空 = 可比） */
        public static List<String> changedFactors(ArmConfig a, ArmConfig b) {
            java.util.List<String> out = new java.util.ArrayList<>();
            if (!a.tools().equals(b.tools())) {
                out.add("tools");
            }
            if (a.tokenBudget() != b.tokenBudget()) {
                out.add("token_budget");
            }
            if (a.timeoutMs() != b.timeoutMs()) {
                out.add("timeout_ms");
            }
            if (!a.modelConfig().equals(b.modelConfig())) {
                out.add("model_config");
            }
            if (!a.dataSnapshotId().equals(b.dataSnapshotId())) {
                out.add("data_snapshot");
            }
            return List.copyOf(out);
        }
    }

    /** 局部消融对（D07 步骤 6）：base 与 variant 必须恰好只差一个因子 */
    public record AblationPair(ArmConfig base, ArmConfig variant) {
        public AblationPair {
            Objects.requireNonNull(base, "base 不得为 null");
            Objects.requireNonNull(variant, "variant 不得为 null");
        }
    }

    /**
     * 干预重放记录（D07 步骤 7/MA-10）：替换错误专家回执后的重放。
     * {@code preTraceDigest}/{@code postTraceDigest} = 干预前后轨迹内容摘要（缺一 =
     * 观测不足如实 NOT_ASSESSED）；{@code postReplayImproved} = 重放后质量改善
     * （环境真值判定）。只有改善且有双轨迹才支持因果归因；否则只能叫疑似归因。
     */
    public record InterventionReplay(String replacedEdgeId,
                                     String preTraceDigest,
                                     String postTraceDigest,
                                     boolean postReplayImproved) {

        public InterventionReplay {
            Objects.requireNonNull(replacedEdgeId, "replacedEdgeId 不得为 null");
        }
    }
}
