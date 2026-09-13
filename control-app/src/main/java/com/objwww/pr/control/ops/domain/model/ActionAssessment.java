package com.objwww.pr.control.ops.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 调查动作价值分析行（OP-03，V105 rca_action_assessment）：版本化派生台账——
 * 只读原始调用账本/证据/报告后聚合落档，不改任何源行。分析口径变更 =
 * 新 assessorVersion；迟到对账/报告变化 = 新 evidenceSnapshotDigest 重算
 * （新行并存，旧行保留）。
 *
 * <p>classification 是<b>描述性归因，不是因果效益</b>（方案 §4.1：声称某能力
 * 提升根因质量须开关配对实验）。优先级（确定性规则，assessor v1）：
 * SOURCE_FAILED &gt; UNDETERMINED &gt; NO_DATA &gt; DUPLICATE_SAME_SNAPSHOT &gt;
 * CONFIRMS_OR_REFUTES &gt; NEW_OBSERVATION。report 未引用不等于动作无用
 * （NEW_OBSERVATION 不因未引用降级）；NO_DATA 可能排除方向，不自动判无价值。
 */
public record ActionAssessment(UUID id,
                               UUID runId,
                               UUID taskId,
                               String logicalActionKey,
                               String assessorVersion,
                               String evidenceSnapshotDigest,
                               int physicalAttempts,
                               int newObservationCount,
                               List<String> gapResolutionRefs,
                               int reportCitationCount,
                               String classification,
                               String confidenceKind,
                               Instant computedAt) {

    public static final String NEW_OBSERVATION = "NEW_OBSERVATION";
    public static final String CONFIRMS_OR_REFUTES = "CONFIRMS_OR_REFUTES";
    public static final String NO_DATA = "NO_DATA";
    public static final String DUPLICATE_SAME_SNAPSHOT = "DUPLICATE_SAME_SNAPSHOT";
    public static final String SOURCE_FAILED = "SOURCE_FAILED";
    public static final String UNDETERMINED = "UNDETERMINED";

    public ActionAssessment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(computedAt, "computedAt");
        if (logicalActionKey == null || logicalActionKey.isBlank()) {
            throw new IllegalArgumentException("logicalActionKey 不得为 blank");
        }
        if (assessorVersion == null || assessorVersion.isBlank()) {
            throw new IllegalArgumentException("assessorVersion 不得为 blank");
        }
        if (evidenceSnapshotDigest == null || evidenceSnapshotDigest.isBlank()) {
            throw new IllegalArgumentException("evidenceSnapshotDigest 不得为 blank");
        }
        if (classification == null || classification.isBlank()) {
            throw new IllegalArgumentException("classification 不得为 blank");
        }
        if (confidenceKind == null || confidenceKind.isBlank()) {
            throw new IllegalArgumentException("confidenceKind 不得为 blank");
        }
        if (physicalAttempts < 0 || newObservationCount < 0 || reportCitationCount < 0) {
            throw new IllegalArgumentException("计数字段不得为负");
        }
        gapResolutionRefs = gapResolutionRefs == null ? List.of() : List.copyOf(gapResolutionRefs);
    }
}
