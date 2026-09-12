package com.objwww.pr.control.alert.domain.tool;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 只读工具调用账本端口（AM4 M4-18，V15 rca_tool_invocation）。
 * PENDING 先行 → 终态 CAS（PENDING → SUCCESS/FAILED/UNKNOWN，单向单次）；
 * 唯一 operation_id = 行主键；逻辑幂等键 UNIQUE(run,task,attempt,call_seq,tool)——
 * 同一逻辑调用只可能落一行账（重复调用 = 键冲突显式失败）。
 * 实现方每方法自含短事务。
 */
public interface RcaToolInvocationLedger {

    /** 落账身份五元组 + 工具语义字段（action_digest 由 Gateway 侧 canonicalize 得出） */
    record InvocationIdentity(UUID operationId, UUID runId, UUID taskId, UUID attemptId,
            long callSeq, String toolName, String toolVersion, String actionDigest) {
    }

    /**
     * EX-A3（F08/F09）恢复读行：checkpoint 判定所需最小投影——run/task 维度由
     * 查询参数固定，行内只剩结算状态与结果引用（P1-03 checkpoint 字段映射见
     * docs/告警-EXA3-可恢复驱动.md §1）。
     */
    record InvocationRecovery(UUID operationId, long callSeq, UUID attemptId,
            String actionDigest, ToolInvocationState state, UUID resultRef) {
    }

    /** PENDING 先行（同事务先行；键冲突=重复调用，显式抛 DataIntegrity 异常族） */
    void open(InvocationIdentity identity);

    /** PENDING → SUCCESS（CAS；非 PENDING 返回 false，终态不可改写） */
    boolean succeed(UUID operationId);

    /** PENDING → FAILED/UNKNOWN + 原因码（CAS；非 PENDING 返回 false） */
    boolean fail(UUID operationId, ToolInvocationState terminal, ToolReasonCode reasonCode);

    /**
     * EX-A4a（F16）：恢复扫描——PENDING 悬挂超 cutoff → UNKNOWN/TRANSPORT_UNKNOWN
     * （进程死后的孤儿回执永不达；BA-13② 同律，Holmes ExternalInvocation 与
     * InvestigationResult 之外的第一方工具账本）。单语句条件写，返回收敛行数。
     * default 抛出=假件环境未镜像（EX-A2 reclaimExpired 同款先例）——真实 PG 实现覆盖。
     */
    default int reclaimPendingOlderThan(Instant cutoff) {
        throw new UnsupportedOperationException(
                "reclaimPendingOlderThan 仅 Postgres 账本实现（恢复扫描面）");
    }

    /**
     * EX-A3（F08/F09）：恢复读——某 run 某任务的账本行（call_seq 序），四阶段分诊
     * 的 checkpoint 判定输入。default 空表 = 假件环境无在途知识（阶段① 新驱动面）；
     * 恢复语义测试的假件必须覆写，真实 PG 实现覆盖。
     */
    default List<InvocationRecovery> findRecoveryByTask(UUID runId, UUID taskId) {
        return List.of();
    }

    /**
     * MC24 同现场复用读面：某 run 全部已成功且带结果引用的账本行（call_seq 序）
     * ——SingleToolEvidenceAgent 在物理执行前按 action_digest 匹配本 run 已有
     * 证据行（同 digest=同语义查询，复用 evidenceId 不重打工具）。default 空
     * 表 = 假件环境无复用知识；真实 PG 实现覆盖。
     */
    default List<InvocationRecovery> findSuccessfulByRun(UUID runId) {
        return List.of();
    }

    /**
     * EX-A3（F09）：结果引用随账落档——evidence.insert 后、succeed 前调用；
     * CAS 锚 PENDING（succeed 后不可改写）。返回 false = 行不在 PENDING（调用方
     * 忽略——随后 succeed 的 CAS 同样失败，账本一致）。default no-op = 假件环境
     * 不挂引用；生产 PG 实现必落，恢复语义测试的假件必须覆写。
     */
    default boolean markResultRef(UUID operationId, UUID evidenceId) {
        return false;
    }
}
