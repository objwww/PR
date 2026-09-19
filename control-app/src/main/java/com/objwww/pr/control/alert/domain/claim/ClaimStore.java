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
     *  kind（V37 列，A4 起投影）可空——旧行无类型如实 null，四值见 {@link ClaimKind}；
     *  rootComponent/rootFaultType/rootReasonCode（V147 列）可空——ROOT_CAUSE 断言的
     *  结构化评分面，null=未提供（诚实降级，旧行同）；
     *  symptomCodes（V151 列）可空——SYMPTOM 断言的症状码评分面，null=未声明
     *  （诚实降级，旧行同；禁止来源标签冒充，BA-158 同族） */
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
            ClaimKind kind,
            String rootComponent,
            String rootFaultType,
            String rootReasonCode,
            List<String> symptomCodes) {

        /** 20 参 compat 构造（存量调用点零改动）：症状码缺省 = null（未声明） */
        public ClaimRow(UUID id, UUID runId, String fingerprint, String claimHash,
                String claimKey, ClaimStatus status, EvidenceBasis evidenceBasis,
                ClaimLifecycle lifecycle, String reason, String scope, String timeRange,
                long observedGeneration, List<String> sources, List<String> evidenceRefs,
                String policyVersion, String snapshotDigest, ClaimKind kind,
                String rootComponent, String rootFaultType, String rootReasonCode) {
            this(id, runId, fingerprint, claimHash, claimKey, status, evidenceBasis,
                    lifecycle, reason, scope, timeRange, observedGeneration, sources,
                    evidenceRefs, policyVersion, snapshotDigest, kind, rootComponent,
                    rootFaultType, rootReasonCode, null);
        }

        /** 17 参 compat 构造（存量调用点零改动）：根因三元组缺省 = null（未提供） */
        public ClaimRow(UUID id, UUID runId, String fingerprint, String claimHash,
                String claimKey, ClaimStatus status, EvidenceBasis evidenceBasis,
                ClaimLifecycle lifecycle, String reason, String scope, String timeRange,
                long observedGeneration, List<String> sources, List<String> evidenceRefs,
                String policyVersion, String snapshotDigest, ClaimKind kind) {
            this(id, runId, fingerprint, claimHash, claimKey, status, evidenceBasis,
                    lifecycle, reason, scope, timeRange, observedGeneration, sources,
                    evidenceRefs, policyVersion, snapshotDigest, kind, null, null, null,
                    null);
        }

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
            symptomCodes = symptomCodes == null ? null : List.copyOf(symptomCodes);
        }
    }
}
