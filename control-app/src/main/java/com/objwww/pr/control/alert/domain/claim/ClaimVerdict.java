package com.objwww.pr.control.alert.domain.claim;

import java.util.List;
import java.util.Objects;

/**
 * 裁决结果（AM4 M4-22 ClaimReducer 的输出契约）。
 * basis 记录裁决依据的规则（可审计）；NEEDS_REVIEW 时 status 恒为 UNKNOWN。
 */
public record ClaimVerdict(
        String claimKey,
        ClaimStatus status,
        Basis basis,
        List<String> evidenceRefs) {

    /** 裁决依据（规则驱动，可审计；不存在"投票"依据） */
    public enum Basis {
        /** 同 key 全部断言状态一致 */
        UNANIMOUS,
        /** 权威源规则裁决（配置的权威 source 的断言为准） */
        AUTHORITATIVE_SOURCE,
        /** ≥2 个独立来源佐证同一状态 */
        MULTI_SOURCE_CORROBORATION,
        /** 无法裁决，升级人工 */
        NEEDS_REVIEW
    }

    public ClaimVerdict {
        Objects.requireNonNull(claimKey, "claimKey");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(basis, "basis");
        evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
    }
}
