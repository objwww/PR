package com.objwww.pr.control.alert.domain.claim;

import java.util.List;
import java.util.Objects;

/**
 * 断言契约（AM4 M4-21；结构抄 K8s Condition，FUT-16）：
 * claimKey/status/reason/scope/timeRange/observedGeneration/evidenceRefs。
 *
 * <p>在 Condition 五元组上增补两项：
 * <ul>
 *   <li>evidenceRefs——断言必须挂证据（无证据不产确认根因，AM4 §3.1 ReportAssembler 前提）；</li>
 *   <li>source——产出该断言的 Agent/工具身份，ClaimReducer 的权威源规则与
 *       多源佐证都以 source 区分"独立来源"（K8s 无此字段，K8s Condition 天然单写者）。</li>
 * </ul>
 * 纯值对象，不做裁决执行。
 */
public record Claim(
        String claimKey,
        ClaimStatus status,
        String reason,
        String scope,
        String timeRange,
        long observedGeneration,
        List<String> evidenceRefs,
        String source) {

    public Claim {
        Objects.requireNonNull(claimKey, "claimKey");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(timeRange, "timeRange");
        Objects.requireNonNull(source, "source");
        evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
    }
}
