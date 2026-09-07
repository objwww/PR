package com.objwww.pr.control.alert.domain.tool;

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

    /** PENDING 先行（同事务先行；键冲突=重复调用，显式抛 DataIntegrity 异常族） */
    void open(InvocationIdentity identity);

    /** PENDING → SUCCESS（CAS；非 PENDING 返回 false，终态不可改写） */
    boolean succeed(UUID operationId);

    /** PENDING → FAILED/UNKNOWN + 原因码（CAS；非 PENDING 返回 false） */
    boolean fail(UUID operationId, ToolInvocationState terminal, ToolReasonCode reasonCode);
}
