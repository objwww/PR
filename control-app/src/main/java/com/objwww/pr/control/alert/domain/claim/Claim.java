package com.objwww.pr.control.alert.domain.claim;

import java.util.List;
import java.util.Objects;

/**
 * 断言契约（AM4 M4-21；结构抄 K8s Condition，FUT-16）：
 * claimKey/status/reason/scope/timeRange/observedGeneration/evidenceRefs。
 *
 * <p>在 Condition 五元组上增补三项：
 * <ul>
 *   <li>evidenceRefs——断言必须挂证据（无证据不产确认根因，AM4 §3.1 ReportAssembler 前提），
 *       且<b>不得为空</b>（空证据断言不是断言）；</li>
 *   <li>source——产出该断言的 Agent/工具身份，ClaimReducer 的权威源规则与
 *       多源佐证都以 source 区分"独立来源"（K8s 无此字段，K8s Condition 天然单写者）；</li>
 *   <li>snapshotDigest——断言所基于的冻结快照摘要，<b>可为 null</b>（无快照约束）；
 *       ClaimReducer 分组键的组成部分，不同快照的断言不得合并裁决。</li>
 * </ul>
 * 纯值对象，不做裁决执行。构造即校验：空/blank claimKey 或 source、空 evidenceRefs、
 * 负数 observedGeneration 一律拒绝。
 */
public record Claim(
        String claimKey,
        ClaimStatus status,
        String reason,
        String scope,
        String timeRange,
        long observedGeneration,
        List<String> evidenceRefs,
        String source,
        String snapshotDigest) {

    public Claim {
        if (claimKey == null || claimKey.isBlank()) {
            throw new IllegalArgumentException("claimKey 不得为空/blank");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(timeRange, "timeRange");
        if (observedGeneration < 0) {
            throw new IllegalArgumentException("observedGeneration 不得为负: " + observedGeneration);
        }
        evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
        if (evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException("evidenceRefs 不得为空——无证据不成断言");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source 不得为空/blank");
        }
        // snapshotDigest 可为 null（无快照约束）
    }
}
