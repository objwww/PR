package com.objwww.pr.control.alert.domain.claim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 规则驱动的断言裁决器（AM4 M4-22 纯逻辑；v1.3 统一状态模型——输出三正交字段中的
 * 命题状态 + 证据基础，<b>无独立 verdict 出口枚举</b>）。
 *
 * <p>分组键 = claimKey + normalizedScope + timeRange + observedGeneration + snapshotDigest
 * 五元组（与 {@link ClaimIdentity#fingerprint()} 身份同集）——不同时间窗/代际/快照的断言
 * 是不同事实，不得合并裁决。normalizedScope = scope.strip()。
 *
 * <p>组内裁决（全部确定性，INV-AM4-2；同一 source 的重复断言去重后只算一票）：
 * <ol>
 *   <li>状态全一致：整组为胜出子集；</li>
 *   <li>存在冲突时权威源规则：配置的权威 source 有断言时以权威源为准；
 *       多个权威源互不一致 → 无法裁决（权威冲突不擅自取舍）；</li>
 *   <li>无权威源：恰好一个状态获 ≥2 独立 source 佐证 → 该状态支持者为胜出子集；
 *       TRUE 与 FALSE 各有 ≥2 独立来源（或没有任何状态达到双源）→ 无法裁决——
 *       <b>禁枚举顺序优先，对峙即人工</b>；</li>
 *   <li>证据基础按胜出子集的<b>真实独立来源数</b>计：≥2 → MULTI_SOURCE_CONSISTENT
 *       （确认级别），否则 SINGLE_SOURCE（含权威源裁决——权威定状态，非佐证计数）；
 *       无法裁决 → UNKNOWN + MULTI_SOURCE_CONFLICT，证据引用为全组并集。</li>
 * </ol>
 * <b>禁止任何置信度投票逻辑</b>（多数和高分不制造真相——Harness 评审定案）。
 * reason/sources/证据引用均为排序去重的确定性归并（输入顺序无关 → 同事实同 contentHash）；
 * 输出按分组键字典序（可复现）。policyVersion 是裁决策略版本（权威源表），进入 claim_hash
 * 内容——策略换版即内容变更，走 REVISED 而非静默重写。
 */
public final class ClaimReducer {

    private final Set<String> authoritativeSources;
    private final String policyVersion;

    public ClaimReducer(Set<String> authoritativeSources, String policyVersion) {
        this.authoritativeSources = Set.copyOf(Objects.requireNonNull(authoritativeSources,
                "authoritativeSources"));
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("policyVersion 不得为空/blank");
        }
        this.policyVersion = policyVersion;
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

        // 1) 状态全一致：整组为胜出子集
        if (sourcesByStatus.size() == 1) {
            return verdict(key, group, group.get(0).status());
        }

        // 2) 冲突时权威源规则
        List<Claim> authoritative = group.stream()
                .filter(c -> authoritativeSources.contains(c.source()))
                .toList();
        if (!authoritative.isEmpty()) {
            ClaimStatus authStatus = authoritative.get(0).status();
            if (authoritative.stream().allMatch(c -> c.status() == authStatus)) {
                return verdict(key, authoritative, authStatus);
            }
            return unresolved(key, group);
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
            return verdict(key, supporters, status);
        }

        // 4) 无法裁决（含 TRUE/FALSE 各有 ≥2 独立来源的对峙）
        return unresolved(key, group);
    }

    /** 胜出子集裁决：证据基础按子集真实独立来源数计 */
    private ClaimVerdict verdict(GroupKey key, List<Claim> winning, ClaimStatus status) {
        return assemble(key, winning, status, null);
    }

    /** 无法裁决：UNKNOWN + MULTI_SOURCE_CONFLICT，证据与来源为全组并集（审计完整呈堂） */
    private ClaimVerdict unresolved(GroupKey key, List<Claim> group) {
        return assemble(key, group, ClaimStatus.UNKNOWN, EvidenceBasis.MULTI_SOURCE_CONFLICT);
    }

    /** basis=null 时按胜出子集真实独立来源数计；显式传入（CONFLICT）则不数来源 */
    private ClaimVerdict assemble(GroupKey key, List<Claim> winning, ClaimStatus status,
            EvidenceBasis forcedBasis) {
        Set<String> sources = new TreeSet<>();
        Set<String> reasons = new TreeSet<>();
        Set<String> evidence = new TreeSet<>();
        for (Claim c : winning) {
            sources.add(c.source());
            reasons.add(c.reason());
            evidence.addAll(c.evidenceRefs());
        }
        EvidenceBasis basis = forcedBasis != null ? forcedBasis
                : sources.size() >= 2 ? EvidenceBasis.MULTI_SOURCE_CONSISTENT
                : EvidenceBasis.SINGLE_SOURCE;
        return new ClaimVerdict(key.claimKey, key.scope, key.timeRange, key.observedGeneration,
                key.snapshotDigest, status, basis, List.copyOf(sources),
                String.join("; ", reasons), List.copyOf(evidence), policyVersion);
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
