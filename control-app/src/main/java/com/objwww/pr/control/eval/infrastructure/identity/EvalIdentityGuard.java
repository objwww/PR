package com.objwww.pr.control.eval.infrastructure.identity;

import com.objwww.pr.control.eval.domain.model.PartitionClass;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * EvalIdentityGuard（M5-02）：评测查询侧身份校验——权限矩阵的应用层单点，
 * 与 V21 的 DB 面（case_version RLS + 四分区角色）互为纵深。
 *
 * <p>矩阵（冻结；落码方案 §M5-02④ IT 面的 UT 对应物）：
 * <ul>
 *   <li>AGENT / RAG / TUNING_UI：TUNING+VALIDATION 可见，HOLDOUT/REDTEAM 恒 0，
 *       GT 不可读（Agent/RAG 对 HOLDOUT/GT 查询恒 0——方案 §12.1 L2 原文）；</li>
 *   <li>SCORING（eval_app 评分身份）：TUNING/VALIDATION/REDTEAM 可见，
 *       HOLDOUT 封存门前不可见（V21 RLS eval_app 策略同谓词），GT 可读
 *       （V3 延迟授权沿用：报告封存后评分读取）；</li>
 *   <li>HOLDOUT_GATE：仅 HOLDOUT（封存门身份，评分身份专属）；GT 可读；</li>
 *   <li>REDTEAM_GATE：仅 REDTEAM；GT 不可读。</li>
 * </ul>
 *
 * <p>DB 角色映射（M5-06 起接线）：AGENT/RAG/TUNING_UI ↔ control_app 族身份
 * （当前对 case_version 零 grant + RLS 零策略双保险，即使未来误 grant 也 0 行）；
 * SCORING ↔ eval_app；HOLDOUT_GATE/REDTEAM_GATE ↔ eval_holdout_gate/eval_redteam_gate
 * （NOLOGIN 授权目标，会话经 SET ROLE / 专属连接）。
 */
public final class EvalIdentityGuard {

    /** 评测查询身份（封闭枚举；新增身份须同步矩阵与 V21 角色面） */
    public enum EvalIdentity {AGENT, RAG, TUNING_UI, SCORING, HOLDOUT_GATE, REDTEAM_GATE}

    private static final Map<EvalIdentity, Set<PartitionClass>> VISIBLE = Map.of(
            EvalIdentity.AGENT, Set.of(PartitionClass.TUNING, PartitionClass.VALIDATION),
            EvalIdentity.RAG, Set.of(PartitionClass.TUNING, PartitionClass.VALIDATION),
            EvalIdentity.TUNING_UI, Set.of(PartitionClass.TUNING, PartitionClass.VALIDATION),
            EvalIdentity.SCORING, Set.of(PartitionClass.TUNING, PartitionClass.VALIDATION,
                    PartitionClass.REDTEAM),
            EvalIdentity.HOLDOUT_GATE, Set.of(PartitionClass.HOLDOUT),
            EvalIdentity.REDTEAM_GATE, Set.of(PartitionClass.REDTEAM));

    /** 身份可见分区（只读视图） */
    public Set<PartitionClass> visiblePartitions(EvalIdentity identity) {
        Objects.requireNonNull(identity, "identity 不得为 null");
        return Set.copyOf(VISIBLE.get(identity));
    }

    /** 分区查询放行断言：不可见分区直接拒绝（fail-closed，不降级为空集查询） */
    public void assertQueryAllowed(EvalIdentity identity, PartitionClass partition) {
        Objects.requireNonNull(identity, "identity 不得为 null");
        Objects.requireNonNull(partition, "partition 不得为 null");
        Set<PartitionClass> visible = VISIBLE.get(identity);
        if (!visible.contains(partition)) {
            throw new IllegalStateException("评测分区查询拒绝: identity=" + identity
                    + " 对 partition=" + partition + " 恒 0 可见（可见面=" + visible + "）");
        }
    }

    /**
     * GT（ground truth）可读面：仅评分身份与封存门（V3/V11 延迟授权沿用——
     * 报告封存后才可读；Agent/RAG/调优界面身份恒不可读）。
     */
    public boolean isGroundTruthReadable(EvalIdentity identity) {
        Objects.requireNonNull(identity, "identity 不得为 null");
        return identity == EvalIdentity.SCORING || identity == EvalIdentity.HOLDOUT_GATE;
    }
}
