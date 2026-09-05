package com.objwww.pr.control.alert.domain.claim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 规则驱动的断言裁决器（AM4 M4-22 纯逻辑）。
 *
 * <p>同 claimKey 分组后按固定优先级裁决（全部确定性，INV-AM4-2）：
 * <ol>
 *   <li>全一致 → UNANIMOUS（corroborated）；</li>
 *   <li>权威源规则：配置的权威 source 有断言时以权威源为准；
 *       多个权威源互不一致 → 无法裁决（权威冲突不擅自取舍）；</li>
 *   <li>双源佐证优先：≥2 个独立 source 佐证同一状态 → MULTI_SOURCE_CORROBORATION；</li>
 *   <li>其余 → NEEDS_REVIEW（status=UNKNOWN，确定性升级人工，不自动糊弄）。</li>
 * </ol>
 * <b>禁止任何置信度投票逻辑</b>（多数和高分不制造真相——Harness 评审定案）。
 * 输出按 claimKey 字典序（可复现）；verdict 的 evidenceRefs 为胜出断言的证据并集（保序去重）。
 */
public final class ClaimReducer {

    private final Set<String> authoritativeSources;

    public ClaimReducer(Set<String> authoritativeSources) {
        this.authoritativeSources = Set.copyOf(authoritativeSources);
    }

    public List<ClaimVerdict> reduce(Collection<Claim> claims) {
        Map<String, List<Claim>> byKey = new TreeMap<>();
        for (Claim c : claims) {
            byKey.computeIfAbsent(c.claimKey(), k -> new ArrayList<>()).add(c);
        }
        List<ClaimVerdict> verdicts = new ArrayList<>();
        for (Map.Entry<String, List<Claim>> group : byKey.entrySet()) {
            verdicts.add(reduceGroup(group.getKey(), group.getValue()));
        }
        return verdicts;
    }

    private ClaimVerdict reduceGroup(String claimKey, List<Claim> group) {
        // 1) 全一致
        ClaimStatus first = group.get(0).status();
        if (group.stream().allMatch(c -> c.status() == first)) {
            return new ClaimVerdict(claimKey, first, ClaimVerdict.Basis.UNANIMOUS,
                    mergedEvidence(group));
        }

        // 2) 权威源规则
        List<Claim> authoritative = group.stream()
                .filter(c -> authoritativeSources.contains(c.source()))
                .toList();
        if (!authoritative.isEmpty()) {
            ClaimStatus authStatus = authoritative.get(0).status();
            if (authoritative.stream().allMatch(c -> c.status() == authStatus)) {
                return new ClaimVerdict(claimKey, authStatus,
                        ClaimVerdict.Basis.AUTHORITATIVE_SOURCE, mergedEvidence(authoritative));
            }
            return needsReview(claimKey, group);
        }

        // 3) 双源佐证优先：≥2 独立 source 同状态
        for (ClaimStatus status : ClaimStatus.values()) {
            Set<String> sources = new LinkedHashSet<>();
            List<Claim> supporters = new ArrayList<>();
            for (Claim c : group) {
                if (c.status() == status && sources.add(c.source())) {
                    supporters.add(c);
                }
            }
            if (sources.size() >= 2) {
                return new ClaimVerdict(claimKey, status,
                        ClaimVerdict.Basis.MULTI_SOURCE_CORROBORATION,
                        mergedEvidence(supporters));
            }
        }

        // 4) 无法裁决
        return needsReview(claimKey, group);
    }

    private static ClaimVerdict needsReview(String claimKey, List<Claim> group) {
        return new ClaimVerdict(claimKey, ClaimStatus.UNKNOWN,
                ClaimVerdict.Basis.NEEDS_REVIEW, mergedEvidence(group));
    }

    private static List<String> mergedEvidence(List<Claim> claims) {
        Set<String> merged = new LinkedHashSet<>();
        for (Claim c : claims) {
            merged.addAll(c.evidenceRefs());
        }
        return List.copyOf(merged);
    }
}
