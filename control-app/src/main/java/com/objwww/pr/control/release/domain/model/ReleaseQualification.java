package com.objwww.pr.control.release.domain.model;

import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 发布资格（EN-02，增强线方案 §8.2 评测证明契约）：一次候选组合通过独立评测的
 * 证明记录。至少绑定候选/基线 digest、dataset/split manifest、runner/grader 版本、
 * 各安全门结论（{@code qualityVerdict}）、费用对账状态（{@code usageStatus}）、
 * 允许发布范围与审批/撤销记录。
 *
 * <p><b>MATCHED 只用于费用对账语义，不能代替质量 PASS</b>（E05/S09）：FAIL+MATCHED
 * 行合法存在（费用与质量分开记账），但 {@link #passQualified()} 恒 false。
 * 门语义 = 存在未撤销且 quality_verdict=PASS 的证明；撤销三件套
 * （revokedAt/revokedBy/revokedReason）要么全空要么全在（§8.4 安全撤销面）。
 *
 * <p>零框架（L0：release 域零框架规则，ControlArchitectureTest）。
 */
public record ReleaseQualification(UUID id,
                                   Digest candidateDigest,
                                   Digest baselineDigest,
                                   String datasetManifestDigest,
                                   String runnerVersion,
                                   String graderVersion,
                                   String qualityVerdict,
                                   String usageStatus,
                                   String grantedScope,
                                   String grantedBy,
                                   Instant grantedAt,
                                   Instant revokedAt,
                                   String revokedBy,
                                   String revokedReason) {

    public static final String VERDICT_PASS = "PASS";
    public static final String VERDICT_FAIL = "FAIL";
    public static final String VERDICT_INCONCLUSIVE = "INCONCLUSIVE";
    public static final String USAGE_MATCHED = "MATCHED";
    public static final String USAGE_MISMATCH = "MISMATCH";
    public static final String USAGE_UNKNOWN = "UNKNOWN";

    private static final Set<String> VERDICTS =
            Set.of(VERDICT_PASS, VERDICT_FAIL, VERDICT_INCONCLUSIVE);
    private static final Set<String> USAGES =
            Set.of(USAGE_MATCHED, USAGE_MISMATCH, USAGE_UNKNOWN);

    private static final Pattern DIGEST_HEX = Pattern.compile("[0-9a-f]{64}");

    public ReleaseQualification {
        Objects.requireNonNull(id, "id 不得为 null");
        Objects.requireNonNull(candidateDigest, "candidateDigest 不得为 null");
        Objects.requireNonNull(datasetManifestDigest, "datasetManifestDigest 不得为 null");
        if (!DIGEST_HEX.matcher(datasetManifestDigest).matches()) {
            throw new IllegalArgumentException(
                    "dataset manifest 必须 64 位小写 hex: " + datasetManifestDigest);
        }
        requireNonBlank(runnerVersion, "runner_version");
        requireNonBlank(graderVersion, "grader_version");
        if (qualityVerdict == null || !VERDICTS.contains(qualityVerdict)) {
            throw new IllegalArgumentException(
                    "quality_verdict 必须为 PASS/FAIL/INCONCLUSIVE: " + qualityVerdict);
        }
        if (usageStatus == null || !USAGES.contains(usageStatus)) {
            throw new IllegalArgumentException(
                    "usage_status 必须为 MATCHED/MISMATCH/UNKNOWN: " + usageStatus);
        }
        requireNonBlank(grantedScope, "granted_scope");
        requireNonBlank(grantedBy, "granted_by");
        Objects.requireNonNull(grantedAt, "grantedAt 不得为 null");
        boolean anyRevocation = revokedAt != null || revokedBy != null || revokedReason != null;
        if (anyRevocation && (revokedAt == null || revokedBy == null
                || revokedReason == null || revokedReason.isBlank())) {
            throw new IllegalArgumentException(
                    "撤销记录三件套（at/by/reason）必须同时在场");
        }
    }

    /** 门语义：未撤销且质量 PASS（费用对账状态不参与门，E05/S09） */
    public boolean passQualified() {
        return VERDICT_PASS.equals(qualityVerdict) && revokedAt == null;
    }

    /** 撤销副本（记录不可变：撤销 = 新行替换原行，原证明内容不改写） */
    public ReleaseQualification revoked(String by, String reason, Instant at) {
        return new ReleaseQualification(id, candidateDigest, baselineDigest,
                datasetManifestDigest, runnerVersion, graderVersion, qualityVerdict,
                usageStatus, grantedScope, grantedBy, grantedAt, at, by, reason);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为 blank");
        }
    }
}
