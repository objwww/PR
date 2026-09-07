package com.objwww.pr.control.alert.application.holmes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.claim.Claim;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Holmes Baseline Adapter（AM4 M4-31）：把 Holmes（AM3 M3-08 调查包）输出转成与
 * Native 路径<b>相同</b>的 Evidence/Claim 边界——M4-34 Shadow Router 在同一
 * snapshot_digest 下对比两路的对齐面。包本身落一条证据信封（evidence_type=
 * holmes.report），ReportClaim 逐条映射为 {@link Claim}（claimKey=claim_type、
 * scope=component、source=holmes、快照/代际继承本 run 绑定）；空 evidence_refs 的
 * 因果型断言桥接到本包证据信封（Claim 契约禁空 refs）。
 *
 * <p><b>Adapter 失败可见</b>（验收行）：包解析失败/claims 缺失/断言字段非法 →
 * FAILED 结局携带原因显式返回，禁静默吞成零断言；合法空 claims → NO_CLAIMS
 * （诚实空不是失败）。
 */
public class HolmesEvidenceAdapter {

    public static final String SOURCE = "holmes";
    public static final String EVIDENCE_TYPE = "holmes.report";

    public enum AdaptOutcome {ADAPTED, NO_CLAIMS, FAILED}

    public record AdaptResult(AdaptOutcome outcome, EvidenceEnvelope evidence,
                              List<Claim> claims, String failReason) {

        public static AdaptResult failed(String reason) {
            return new AdaptResult(AdaptOutcome.FAILED, null, List.of(), reason);
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param schemaVersion 证据信封 schema（恒 {@link EvidenceEnvelope#SCHEMA_VERSION}，
     *                      参数保留以显式命名绑定面）
     */
    public AdaptResult adapt(UUID runId, UUID taskId, long generation, String snapshotDigest,
            String schemaVersion, String packageJson) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(packageJson, "packageJson");
        if (snapshotDigest == null || snapshotDigest.isBlank()) {
            return AdaptResult.failed("SNAPSHOT_REQUIRED: Holmes 适配必须绑定冻结快照");
        }
        JsonNode pkg;
        try {
            pkg = mapper.readTree(packageJson);
        } catch (Exception e) {
            return AdaptResult.failed("PACKAGE_UNPARSEABLE: Holmes 报告包不是合法 JSON");
        }
        JsonNode claimsNode = pkg.get("claims");
        if (claimsNode == null || !claimsNode.isArray()) {
            return AdaptResult.failed("CLAIMS_MISSING: 报告包缺 claims 数组");
        }
        if (claimsNode.isEmpty()) {
            return new AdaptResult(AdaptOutcome.NO_CLAIMS, null, List.of(), null);
        }

        List<Claim> claims = new ArrayList<>();
        EvidenceEnvelope envelope;
        try {
            Map<String, Object> payload = mapper.convertValue(pkg, Map.class);
            envelope = EvidenceEnvelope.create(UUID.randomUUID(), runId, taskId,
                    EVIDENCE_TYPE, schemaVersion, generation, SOURCE,
                    Map.of("input_snapshot_digest", snapshotDigest), null, null, payload);
            for (JsonNode node : claimsNode) {
                claims.add(toClaim(node, generation, snapshotDigest, envelope.evidenceId()));
            }
        } catch (Exception e) {
            return AdaptResult.failed("CLAIM_INVALID: " + e.getMessage());
        }
        return new AdaptResult(AdaptOutcome.ADAPTED, envelope, List.copyOf(claims), null);
    }

    private static Claim toClaim(JsonNode node, long generation, String snapshotDigest,
            UUID packageEvidenceId) {
        String claimKey = requireText(node, "claim_type");
        String statusText = requireText(node, "status");
        ClaimStatus status;
        try {
            status = ClaimStatus.valueOf(statusText);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("claim status 非法: " + statusText);
        }
        String component = textOrNull(node.get("component"));
        List<String> refs = new ArrayList<>();
        JsonNode refsNode = node.get("evidence_refs");
        if (refsNode != null && refsNode.isArray()) {
            refsNode.forEach(ref -> refs.add(ref.asText()));
        }
        if (refs.isEmpty()) {
            refs.add(packageEvidenceId.toString()); // 因果型断言桥接包证据
        }
        return new Claim(claimKey, status,
                textOrNull(node.get("fault_type")) == null
                        ? "holmes-claim" : textOrNull(node.get("fault_type")),
                component == null ? "" : component,
                textOrNull(node.get("time_range")) == null
                        ? Claim.TIME_RANGE_UNKNOWN : textOrNull(node.get("time_range")),
                generation, List.copyOf(refs), SOURCE, snapshotDigest);
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new IllegalArgumentException("claim 缺必填字段: " + field);
        }
        return value.asText();
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() || node.asText().isBlank()
                ? null : node.asText();
    }
}
