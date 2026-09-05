package com.objwww.pr.control.alert.domain.claim;

import java.util.List;
import java.util.Objects;

/**
 * 裁决结果（AM4 M4-22 ClaimReducer 的输出契约）。
 * 身份字段（claimKey/scope/timeRange/observedGeneration/snapshotDigest）与裁决分组键一一对应——
 * 同一 claimKey 在不同时间窗/代际/快照下产出不同 verdict，不得混淆。
 *
 * <p>basis 记录裁决依据的规则（可审计；不存在"投票"依据）。
 * "确认级别"只有 corroborated() 一种判定：≥2 个独立 source 佐证的裁决才算确认
 * （UNANIMOUS / MULTI_SOURCE_CORROBORATION）；SINGLE_SOURCE 与 NEEDS_REVIEW 均非确认，
 * 权威源裁决是另一条独立路径（权威即契约，非佐证计数）。
 */
public record ClaimVerdict(
        String claimKey,
        String scope,
        String timeRange,
        long observedGeneration,
        String snapshotDigest,
        ClaimStatus status,
        Basis basis,
        List<String> evidenceRefs) {

    /** 裁决依据（规则驱动，可审计；不存在"投票"依据） */
    public enum Basis {
        /** ≥2 个独立 source 断言状态全一致（corroborated） */
        UNANIMOUS,
        /** 权威源规则裁决（配置的权威 source 的断言为准） */
        AUTHORITATIVE_SOURCE,
        /** ≥2 个独立 source 佐证同一状态（存在其他反对声音时） */
        MULTI_SOURCE_CORROBORATION,
        /** 单一来源单状态——仍为单一证据，<b>非确认级别</b>（corroborated()=false） */
        SINGLE_SOURCE,
        /** 无法裁决，升级人工（status 恒为 UNKNOWN） */
        NEEDS_REVIEW
    }

    public ClaimVerdict {
        Objects.requireNonNull(claimKey, "claimKey");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(timeRange, "timeRange");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(basis, "basis");
        evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
    }

    /** 是否确认级别：仅 ≥2 独立 source 佐证的裁决（UNANIMOUS / MULTI_SOURCE_CORROBORATION） */
    public boolean corroborated() {
        return basis == Basis.UNANIMOUS || basis == Basis.MULTI_SOURCE_CORROBORATION;
    }
}
