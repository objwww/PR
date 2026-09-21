package com.objwww.pr.control.eval.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * 多 Agent 协作评测结果值对象（ME-T07/D07 步骤 7；纯数据，L0 零框架依赖）。
 *
 * <p>语义冻结：
 * <ul>
 *   <li>checks 统一五态（{@link BehaviorCheckStatus}），缺证据不猜通过；metrics 带
 *       分子/分母（分母 0 = 口径内无对象，如实不约分）；</li>
 *   <li>failureLabels = MAST 失败类别机器码（{@code MAST_*}）+ 非 MAST 机制码
 *       （DELEGATION_CHOICE_WRONG/ARM_INCOMPARABLE/MULTI_FACTOR_ABLATION）；</li>
 *   <li>归因双轨（D07 步骤 7）：无干预对照时失败只能进 suspectedAttributions
 *       （疑似归因）；仅当替换错误回执后重放改善且干预前后轨迹俱在（MA-10），
 *       该交接边的因果归因才进 supportedAttributions。</li>
 * </ul>
 */
public record CollaborationEvaluation(String graderVersion,
                                      String caseId,
                                      List<BehaviorEvaluation.Check> checks,
                                      List<BehaviorEvaluation.Metric> metrics,
                                      List<String> failureLabels,
                                      List<String> suspectedAttributions,
                                      List<String> supportedAttributions) {

    // MAST 失败类别（REPORT D07 步骤 7；确定性标签，适配本项目机制面）
    /** 信息遗漏：交接必需事实/约束在接收端丢失（MA-04） */
    public static final String MAST_INFORMATION_LOSS = "MAST_INFORMATION_LOSS";
    /** 忽略同伴：适用证据未被消费 / 冲突按投票或置信措辞压过同伴反证（MA-02/03） */
    public static final String MAST_IGNORED_PEER = "MAST_IGNORED_PEER";
    /** 重复：回执重复消费 / 预算重复结算（MA-06） */
    public static final String MAST_REPETITION = "MAST_REPETITION";
    /** 验证不足：共享证据被当成独立双重验证 / 结论缺跨模态支持（MA-02/08） */
    public static final String MAST_VERIFICATION_INADEQUATE = "MAST_VERIFICATION_INADEQUATE";
    /** 任务偏离：伪造子任务结论 / 取消围栏被破坏 / 注入错误扩散（MA-05/07/指标 5） */
    public static final String MAST_TASK_DERAIL = "MAST_TASK_DERAIL";

    public CollaborationEvaluation {
        Objects.requireNonNull(graderVersion, "graderVersion 不得为 null");
        Objects.requireNonNull(caseId, "caseId 不得为 null");
        Objects.requireNonNull(checks, "checks 不得为 null");
        checks = List.copyOf(checks);
        Objects.requireNonNull(metrics, "metrics 不得为 null");
        metrics = List.copyOf(metrics);
        Objects.requireNonNull(failureLabels, "failureLabels 不得为 null");
        failureLabels = List.copyOf(failureLabels);
        Objects.requireNonNull(suspectedAttributions, "suspectedAttributions 不得为 null");
        suspectedAttributions = List.copyOf(suspectedAttributions);
        Objects.requireNonNull(supportedAttributions, "supportedAttributions 不得为 null");
        supportedAttributions = List.copyOf(supportedAttributions);
    }
}
