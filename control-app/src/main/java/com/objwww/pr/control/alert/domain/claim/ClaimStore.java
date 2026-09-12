package com.objwww.pr.control.alert.domain.claim;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 断言投影仓储端口（AM4 M4-21，V17 rca_claim）。
 *
 * <p>{@link #append} 实现四分支（判定语义见 {@link ClaimProjection}）：写行与
 * CLAIM_CREATED / CLAIM_UNCHANGED / CLAIM_REVISED 判定事件同短事务（状态事实，
 * join 调用方事务）；REVISED 事件载荷必须携带修订前内容（历史不可变的落库面——
 * 当前投影被 CAS 更新，旧内容只活在事件账本里）。新代落新行时同事务把同 proposition
 * 的更旧 ACTIVE 投影标 SUPERSEDED（只动 lifecycle 列）。
 *
 * <p>{@link #markUnresolved}：<b>不创建空 Claim 表达无结论</b>——只追加一次幂等
 * CLAIM_UNRESOLVED 判定事件（判定事件连 none 也落库，审计）。
 */
public interface ClaimStore {

    /** 四分支写：返回分支结果与本次判定事件 seq */
    ClaimAppendResult append(UUID runId, ClaimVerdict verdict);

    /** 无结论：幂等 CLAIM_UNRESOLVED 事件（不建行） */
    long markUnresolved(UUID runId, ClaimIdentity identity, String policyVersion);

    /** 按 run 读全部投影（当前面；供组装/审查/测试） */
    List<ClaimRow> findByRunId(UUID runId);

    /**
     * 追加结果。supersededCount = 本次 CREATED 顺带标 SUPERSEDED 的旧投影数
     * （仅非 CREATED 分支恒为 0）。
     */
    record ClaimAppendResult(
            ClaimProjection.Outcome outcome,
            String fingerprint,
            String claimHash,
            String priorHash,
            int supersededCount,
            long eventSeq) {
        public ClaimAppendResult {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(claimHash, "claimHash");
        }
    }

    /** rca_claim 行读面（内容字段只读；lifecycle 变化不伴随内容变化）。
     *  kind（V37 列，A4 起投影）可空——旧行无类型如实 null，四值见 {@link ClaimKind} */
    record ClaimRow(
            UUID id,
            UUID runId,
            String fingerprint,
            String claimHash,
            String claimKey,
            ClaimStatus status,
            EvidenceBasis evidenceBasis,
            ClaimLifecycle lifecycle,
            String reason,
            String scope,
            String timeRange,
            long observedGeneration,
            List<String> sources,
            List<String> evidenceRefs,
            String policyVersion,
            String snapshotDigest,
            ClaimKind kind) {
        public ClaimRow {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(claimHash, "claimHash");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(evidenceBasis, "evidenceBasis");
            Objects.requireNonNull(lifecycle, "lifecycle");
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
            evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
        }
    }
}
