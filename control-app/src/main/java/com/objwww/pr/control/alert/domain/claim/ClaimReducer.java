package com.objwww.pr.control.alert.domain.claim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则驱动的断言裁决器（AM4 M4-22 纯逻辑）。
 *
 * <p>分组键 = claimKey + normalizedScope + timeRange + observedGeneration + snapshotDigest
 * 五元组——不同时间窗/代际/快照的断言是不同事实，不得合并裁决。
 * normalizedScope = scope.strip()（首尾空白不制造新分组，不做大小写折叠）。
 *
 * <p>组内裁决（全部确定性，INV-AM4-2；同一 source 的重复断言去重后只算一票）：
 * <ol>
 *   <li>状态全一致：≥2 独立 source → UNANIMOUS（corroborated）；
 *       单一 source → 权威源则 AUTHORITATIVE_SOURCE，否则 SINGLE_SOURCE（非确认级别）；</li>
 *   <li>存在冲突时权威源规则：配置的权威 source 有断言时以权威源为准；
 *       多个权威源互不一致 → NEEDS_REVIEW（权威冲突不擅自取舍）；</li>
 *   <li>无权威源：统计各状态的独立 source 数，恰好一个状态获 ≥2 独立 source 佐证
 *       → MULTI_SOURCE_CORROBORATION；TRUE 与 FALSE 各有 ≥2 独立来源（或没有任何状态
 *       达到双源）→ NEEDS_REVIEW——<b>禁枚举顺序优先，对峙即人工</b>；</li>
 *   <li>NEEDS_REVIEW 的 status 恒为 UNKNOWN，确定性升级人工，不自动糊弄。</li>
 * </ol>
 * <b>禁止任何置信度投票逻辑</b>（多数和高分不制造真相——Harness 评审定案）。
 * 输出按分组键字典序（可复现）；verdict 的 evidenceRefs 为胜出断言的证据并集（保序去重）。
 */
public final class ClaimReducer {

    private final Set<String> authoritativeSources;

    public ClaimReducer(Set<String> authoritativeSources) {
        this.authoritativeSources = Set.copyOf(authoritativeSources);
    }

    public List<ClaimVerdict> reduce(Collection<Claim> claims) {
        Map<GroupKey, List<Claim>> byGroup = new LinkedHashMap<>();
        for (Claim c : claims) {
            byGroup.computeIfAbsent(GroupKey.of(c), k -> new ArrayList<>()).add(c);
        }
        List<ClaimVerdict> verdicts = new ArrayList<>();
        for (Map.Entry<GroupKey, List<Claim>> group : byGroup.entrySet()) {
            verdicts.add(reduceGroup(group.getKey(), group.getValue()));
        }
        verdicts.sort(Comparator.comparing(ClaimVerdict::claimKey)
                .thenComparing(ClaimVerdict::scope)
                .thenComparing(ClaimVerdict::timeRange)
                .thenComparingLong(ClaimVerdict::observedGeneration)
                .thenComparing(v -> v.snapshotDigest() == null ? "" : v.snapshotDigest()));
        return verdicts;
    }

    private ClaimVerdict reduceGroup(GroupKey key, List<Claim> group) {
        // 投票视图：同一 source 的重复断言去重后只算一票（按 source+status 去重）
        Map<ClaimStatus, Set<String>> sourcesByStatus = new LinkedHashMap<>();
        for (Claim c : group) {
            sourcesByStatus.computeIfAbsent(c.status(), k -> new LinkedHashSet<>()).add(c.source());
        }
        Set<String> distinctSources = new LinkedHashSet<>();
        group.forEach(c -> distinctSources.add(c.source()));

        // 1) 状态全一致
        if (sourcesByStatus.size() == 1) {
            ClaimStatus status = group.get(0).status();
            if (distinctSources.size() >= 2) {
                return verdict(key, status, ClaimVerdict.Basis.UNANIMOUS, group);
            }
            String onlySource = distinctSources.iterator().next();
            if (authoritativeSources.contains(onlySource)) {
                return verdict(key, status, ClaimVerdict.Basis.AUTHORITATIVE_SOURCE, group);
            }
            return verdict(key, status, ClaimVerdict.Basis.SINGLE_SOURCE, group);
        }

        // 2) 冲突时权威源规则
        List<Claim> authoritative = group.stream()
                .filter(c -> authoritativeSources.contains(c.source()))
                .toList();
        if (!authoritative.isEmpty()) {
            ClaimStatus authStatus = authoritative.get(0).status();
            if (authoritative.stream().allMatch(c -> c.status() == authStatus)) {
                return verdict(key, authStatus, ClaimVerdict.Basis.AUTHORITATIVE_SOURCE,
                        authoritative);
            }
            return needsReview(key, group);
        }

        // 3) 双源佐证：恰好一个状态获 ≥2 独立 source 才成立；对峙（多状态各 ≥2）即人工
        List<ClaimStatus> corroborated = new ArrayList<>();
        for (Map.Entry<ClaimStatus, Set<String>> e : sourcesByStatus.entrySet()) {
            if (e.getValue().size() >= 2) {
                corroborated.add(e.getKey());
            }
        }
        if (corroborated.size() == 1) {
            ClaimStatus status = corroborated.get(0);
            Set<String> seen = new LinkedHashSet<>();
            List<Claim> supporters = new ArrayList<>();
            for (Claim c : group) {
                if (c.status() == status && seen.add(c.source())) {
                    supporters.add(c);
                }
            }
            return verdict(key, status, ClaimVerdict.Basis.MULTI_SOURCE_CORROBORATION,
                    supporters);
        }

        // 4) 无法裁决（含 TRUE/FALSE 各有 ≥2 独立来源的对峙）
        return needsReview(key, group);
    }

    private static ClaimVerdict verdict(GroupKey key, ClaimStatus status,
            ClaimVerdict.Basis basis, List<Claim> claims) {
        return new ClaimVerdict(key.claimKey, key.scope, key.timeRange,
                key.observedGeneration, key.snapshotDigest, status, basis,
                mergedEvidence(claims));
    }

    private static ClaimVerdict needsReview(GroupKey key, List<Claim> group) {
        return verdict(key, ClaimStatus.UNKNOWN, ClaimVerdict.Basis.NEEDS_REVIEW, group);
    }

    private static List<String> mergedEvidence(List<Claim> claims) {
        Set<String> merged = new LinkedHashSet<>();
        for (Claim c : claims) {
            merged.addAll(c.evidenceRefs());
        }
        return List.copyOf(merged);
    }

    /** 裁决分组键：claimKey + normalizedScope + timeRange + observedGeneration + snapshotDigest */
    private record GroupKey(String claimKey, String scope, String timeRange,
            long observedGeneration, String snapshotDigest) {
        static GroupKey of(Claim c) {
            return new GroupKey(c.claimKey(), c.scope().strip(), c.timeRange(),
                    c.observedGeneration(), c.snapshotDigest());
        }
    }
}
