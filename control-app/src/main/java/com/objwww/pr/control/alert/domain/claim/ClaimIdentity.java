package com.objwww.pr.control.alert.domain.claim;

import com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 断言身份五元组（AM4 M4-21）：类型/键 + scope（归一）+ 时间窗 + generation + input
 * snapshot——与 ClaimReducer 裁决分组键同集。不同身份的断言是不同事实，永不互相覆盖；
 * claim_fingerprint 即对身份的 canonical sha256。
 *
 * @param snapshotDigest 可为 null（该断言不依赖冻结快照）
 */
public record ClaimIdentity(
        String claimKey,
        String scope,
        String timeRange,
        long observedGeneration,
        String snapshotDigest) {

    public ClaimIdentity {
        Objects.requireNonNull(claimKey, "claimKey");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(timeRange, "timeRange");
        if (observedGeneration < 0) {
            throw new IllegalArgumentException("observedGeneration 不得为负: " + observedGeneration);
        }
        scope = scope.strip();
    }

    /** claim_fingerprint：身份五元组 canonical 后 sha256（hex 64）；kind 标签分隔哈希空间 */
    public String fingerprint() {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("kind", "claim-fingerprint");
        identity.put("claimKey", claimKey);
        identity.put("scope", scope);
        identity.put("timeRange", timeRange);
        identity.put("observedGeneration", observedGeneration);
        identity.put("snapshotDigest", snapshotDigest);
        return InternalCanonicalJsonV1.sha256(identity);
    }
}
