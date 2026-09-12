package com.objwww.pr.control.alert.domain.model;

import com.objwww.pr.control.alert.domain.claim.ClaimStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * EvidencePackage v2（AM3 §6.3；M3-01）：v1 六段式的类型化升级——保留
 * summary/evidence/impact/remediation/references（人读报告 + 通知白名单渲染的素材），
 * 自由文本 root_cause 换成 {@link TypedRootCause}，并新增 {@link ReportClaim} 列表。
 *
 * <p>{@code fromMap} 只做<b>形状映射</b>（键存在/类型正确/枚举合法），形状错误抛
 * IllegalArgumentException 由调用方转 REJECTED_*；长度上限、非 blank、artifact_ref
 * 政策由 record 构造器与 Validator 各管一段（typed 部分自校验，共享文本字段与
 * scheme 白名单仍是 Validator 的政策）。schema_version=2 固定，未知版本在路由层拒绝。
 * 入参为<b>中立树</b>（BA-22：Map/List/String/Number/Boolean/null，无 Jackson 类型——
 * 解析归 {@code EvidencePackageJsonCodec}，application 层）。
 */
public record EvidencePackageV2(int schemaVersion,
                                String summary,
                                TypedRootCause rootCause,
                                List<ReportClaim> claims,
                                List<String> evidence,
                                String impact,
                                String remediation,
                                List<String> referenceArtifactRefs) {

    public static final int SCHEMA_VERSION = 2;
    /** claims 条数上限（防御性资源上限；与 v1 evidence 上限同量级） */
    public static final int MAX_CLAIMS = 32;

    public EvidencePackageV2 {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("schema_version 必须为 " + SCHEMA_VERSION);
        }
        Objects.requireNonNull(summary, "summary 不得为 null");
        Objects.requireNonNull(rootCause, "root_cause 不得为 null");
        Objects.requireNonNull(claims, "claims 不得为 null");
        if (claims.size() > MAX_CLAIMS) {
            throw new IllegalArgumentException("claims 条数超上限: " + claims.size() + " > " + MAX_CLAIMS);
        }
        claims = List.copyOf(claims);
        Objects.requireNonNull(evidence, "evidence 不得为 null");
        evidence = List.copyOf(evidence);
        Objects.requireNonNull(impact, "impact 不得为 null");
        Objects.requireNonNull(remediation, "remediation 不得为 null");
        Objects.requireNonNull(referenceArtifactRefs, "referenceArtifactRefs 不得为 null");
        referenceArtifactRefs = List.copyOf(referenceArtifactRefs);
    }

    /**
     * 从 analysis 内嵌 JSON 对象（中立树）做形状映射。缺失键/类型错/枚举值外 →
     * IllegalArgumentException（消息进拒绝原因链）。长度/blank/scheme 校验不在此处
     * （见类注释）。
     */
    public static EvidencePackageV2 fromMap(Map<String, Object> pkg) {
        requireObject(pkg);
        requireInt(pkg, "schema_version");
        int schemaVersion = (Integer) pkg.get("schema_version");
        requireText(pkg, "summary");
        requireText(pkg, "impact");
        requireText(pkg, "remediation");
        requireArray(pkg, "evidence");
        requireArray(pkg, "claims");
        requireArray(pkg, "references");

        Map<?, ?> rc = requireObjectMember(pkg, "root_cause");
        TypedRootCause rootCause = new TypedRootCause(
                text(rc, "component"), text(rc, "fault_type"), text(rc, "reason_code"));

        List<ReportClaim> claims = new ArrayList<>();
        for (Object claimObj : requireList(pkg, "claims")) {
            if (!(claimObj instanceof Map<?, ?> claim)) {
                throw new IllegalArgumentException("必须是 JSON 对象");
            }
            claims.add(new ReportClaim(
                    text(claim, "claim_type"),
                    ClaimStatus.valueOf(text(claim, "status")),
                    text(claim, "component"),
                    text(claim, "fault_type"),
                    stringList(claim, "symptom_codes"),
                    stringList(claim, "evidence_refs")));
        }

        List<String> evidence = new ArrayList<>();
        for (Object ev : requireList(pkg, "evidence")) {
            if (!(ev instanceof String s)) {
                throw new IllegalArgumentException("evidence 条目必须为字符串");
            }
            evidence.add(s);
        }

        List<String> refs = new ArrayList<>();
        for (Object refObj : requireList(pkg, "references")) {
            if (!(refObj instanceof Map<?, ?> ref)) {
                throw new IllegalArgumentException("必须是 JSON 对象");
            }
            refs.add(text(ref, "artifact_ref"));
        }

        return new EvidencePackageV2(schemaVersion, (String) pkg.get("summary"), rootCause,
                claims, evidence, (String) pkg.get("impact"), (String) pkg.get("remediation"), refs);
    }

    private static void requireObject(Object node) {
        if (!(node instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("必须是 JSON 对象");
        }
    }

    private static Map<?, ?> requireObjectMember(Map<String, Object> pkg, String field) {
        Object node = pkg.get(field);
        if (!(node instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("缺少对象字段: " + field);
        }
        return map;
    }

    private static void requireInt(Map<String, Object> pkg, String field) {
        if (!(pkg.get(field) instanceof Integer)) {
            throw new IllegalArgumentException("缺整型字段: " + field);
        }
    }

    private static void requireText(Map<String, Object> pkg, String field) {
        if (!(pkg.get(field) instanceof String)) {
            throw new IllegalArgumentException("缺字符串字段: " + field);
        }
    }

    private static void requireArray(Map<String, Object> pkg, String field) {
        if (!(pkg.get(field) instanceof List<?>)) {
            throw new IllegalArgumentException("缺数组字段: " + field);
        }
    }

    private static List<?> requireList(Map<String, Object> pkg, String field) {
        Object value = pkg.get(field);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("缺数组字段: " + field);
        }
        return list;
    }

    private static String text(Map<?, ?> node, String field) {
        Object value = node.get(field);
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("缺字符串字段: " + field);
        }
        return s;
    }

    private static List<String> stringList(Map<?, ?> node, String field) {
        Object value = node.get(field);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("缺数组字段: " + field);
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String s)) {
                throw new IllegalArgumentException(field + " 条目必须为字符串");
            }
            out.add(s);
        }
        return out;
    }
}
