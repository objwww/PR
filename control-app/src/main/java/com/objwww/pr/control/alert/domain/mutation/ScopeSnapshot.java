package com.objwww.pr.control.alert.domain.mutation;

import com.objwww.pr.shared.Digests;

import java.util.Objects;

/**
 * Scope 快照（PB-B2，设计基线 §2.2）：Scope Authorization 七问的判定底稿——
 * 资源是谁 / 哪个 env / 哪个 team（+ kind/version/policy_version/requested_key），
 * canonical JSON 固定键序 + sha256 锚。审批批的是本快照，执行前同事务复核该锚
 * （漂移即拒绝，§2.7 生命周期作废矩阵）。
 *
 * <p>锚语义：{@code policyVersion} 入快照——审批期间 policy 变更 → 快照锚变化 →
 * 消费拒绝（作废）。requestedKey 入快照保证"同一资源经不同请求键进入"的可审计
 * 差异（身份仍以 resourceUid 为准）。
 */
public record ScopeSnapshot(
        String requestedKey,
        String resourceUid,
        String canonicalEnv,
        String canonicalTeam,
        String resourceKind,
        long resourceVersion,
        String policyVersion) {

    public ScopeSnapshot {
        Objects.requireNonNull(requestedKey, "requestedKey");
        Objects.requireNonNull(resourceUid, "resourceUid");
        Objects.requireNonNull(canonicalEnv, "canonicalEnv");
        Objects.requireNonNull(canonicalTeam, "canonicalTeam");
        Objects.requireNonNull(resourceKind, "resourceKind");
        Objects.requireNonNull(policyVersion, "policyVersion");
    }

    public static ScopeSnapshot of(ResolvedResource resource, String policyVersion) {
        return new ScopeSnapshot(resource.requestedKey(), resource.resourceUid(),
                resource.canonicalEnv(), resource.canonicalTeam(), resource.resourceKind(),
                resource.resourceVersion(), policyVersion);
    }

    /** canonical JSON：固定键序（无 Jackson 依赖，DecisionProvenance 同律） */
    public String toCanonicalJson() {
        return "{\"requested_key\":\"" + escape(requestedKey) + '"'
                + ",\"resource_uid\":\"" + escape(resourceUid) + '"'
                + ",\"canonical_env\":\"" + escape(canonicalEnv) + '"'
                + ",\"canonical_team\":\"" + escape(canonicalTeam) + '"'
                + ",\"resource_kind\":\"" + escape(resourceKind) + '"'
                + ",\"resource_version\":" + resourceVersion
                + ",\"policy_version\":\"" + escape(policyVersion) + "\"}";
    }

    /** 审批锚：审批批这个 hash，消费时同事务重算比对 */
    public String hash() {
        return Digests.sha256Hex(toCanonicalJson());
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
