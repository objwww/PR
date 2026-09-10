package com.objwww.pr.control.alert.domain.claim;

import com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 裁决后的断言投影（AM4 M4-21/22，v1.3 统一状态模型）。三正交字段 =
 * 命题状态 {@link ClaimStatus} + 证据基础 {@link EvidenceBasis} + 生命周期
 * {@link ClaimLifecycle}（lifecycle 落库默认 ACTIVE，由仓储面维护）；<b>无独立
 * verdict 出口枚举</b>（v1.2 CONFIRMED/SUPPORTED 与 basis 重叠矛盾，评审作废）。
 *
 * <p><b>双哈希</b>（M4-21/22）：
 * <ul>
 *   <li>{@link #fingerprint()} —— 身份 = 类型/键+scope+时间窗+generation+input snapshot
 *       （与裁决分组键同集）；身份相同的内容演化走 REVISED，身份不同永不互相覆盖；</li>
 *   <li>{@link #contentHash()} —— 内容 = 状态+原因+证据引用+来源+<b>策略版本</b>；
 *       身份五元组不进内容哈希（正交：换代际/换快照不动内容哈希）。</li>
 * </ul>
 * 双哈希均对内部字段 map 做 InternalCanonicalJsonV1 规范化后 sha256（字段序无关；
 * kind 标签分隔两个哈希空间）。sources/evidenceRefs 构造即排序去重——内容哈希与
 * 输入顺序无关（可复现）。
 *
 * <p>EX-A4a（F06）：{@link ClaimKind} 四类型存储契约——kind 是<b>准入元数据非内容
 * 身份</b>，不进 fingerprint/contentHash 双哈希（回放比对稳定）；11 参 compat 构造
 * 默认 {@link ClaimKind#HYPOTHESIS}（无类型断言的保守形态，结构性禁止默认
 * ROOT_CAUSE）。类型准入/转换逻辑归 R7c 单一责任人（P1-01）。
 */
public record ClaimVerdict(
        String claimKey,
        String scope,
        String timeRange,
        long observedGeneration,
        String snapshotDigest,
        ClaimStatus status,
        EvidenceBasis evidenceBasis,
        List<String> sources,
        String reason,
        List<String> evidenceRefs,
        String policyVersion,
        ClaimKind kind) {

    /** 11 参 compat 构造（存量调用点零改动）：kind 缺省 = HYPOTHESIS（保守形态） */
    public ClaimVerdict(String claimKey, String scope, String timeRange,
            long observedGeneration, String snapshotDigest, ClaimStatus status,
            EvidenceBasis evidenceBasis, List<String> sources, String reason,
            List<String> evidenceRefs, String policyVersion) {
        this(claimKey, scope, timeRange, observedGeneration, snapshotDigest, status,
                evidenceBasis, sources, reason, evidenceRefs, policyVersion,
                ClaimKind.HYPOTHESIS);
    }

    public ClaimVerdict {
        requireNonBlank(claimKey, "claimKey");
        requireNonBlank(scope, "scope");
        requireNonBlank(timeRange, "timeRange");
        requireNonBlank(reason, "reason");
        requireNonBlank(policyVersion, "policyVersion");
        if (observedGeneration < 0) {
            throw new IllegalArgumentException("observedGeneration 不得为负: " + observedGeneration);
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(evidenceBasis, "evidenceBasis");
        Objects.requireNonNull(kind, "kind");
        // snapshotDigest 可为 null（无快照约束）；canonicalize 对 null 输出 "null"
        sources = sortedDistinct(sources, "sources");
        evidenceRefs = sortedDistinct(evidenceRefs, "evidenceRefs");
        if (evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException("evidenceRefs 不得为空——无证据不成断言");
        }
    }

    /** 断言身份（分组键五元组，去掉内容）；scope 归一（strip） */
    public ClaimIdentity identity() {
        return new ClaimIdentity(claimKey, scope.strip(), timeRange, observedGeneration,
                snapshotDigest);
    }

    /** claim_fingerprint：身份五元组 canonical 后 sha256（hex 64） */
    public String fingerprint() {
        return identity().fingerprint();
    }

    /** claim_hash：内容（状态+原因+证据引用+来源+策略版本）canonical 后 sha256（hex 64） */
    public String contentHash() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("kind", "claim-hash");
        content.put("status", status.name());
        content.put("reason", reason);
        content.put("evidenceRefs", evidenceRefs);
        content.put("sources", sources);
        content.put("policyVersion", policyVersion);
        return InternalCanonicalJsonV1.sha256(content);
    }

    /** 确认级别：仅 ≥2 独立来源一致的裁决（MULTI_SOURCE_CONSISTENT） */
    public boolean corroborated() {
        return evidenceBasis == EvidenceBasis.MULTI_SOURCE_CONSISTENT;
    }

    private static List<String> sortedDistinct(List<String> values, String field) {
        Objects.requireNonNull(values, field);
        return List.copyOf(new TreeSet<>(values));
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为空/blank");
        }
    }
}
