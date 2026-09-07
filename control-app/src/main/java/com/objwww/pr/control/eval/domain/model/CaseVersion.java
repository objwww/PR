package com.objwww.pr.control.eval.domain.model;

import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * CaseVersion（M5-01）：insert-only 案例版本行。同 (dataset_version_id, case_key)
 * 唯一——纠错 = 导入新 dataset_version 携带修正后的案例行，历史版本行永不变更
 * （INV-AM5-1）。适用期 [valid_from, valid_to) 半开区间，valid_to 为 null = 开放期；
 * source_artifact_ref 是原始 artifact 的引用指针（不覆盖来源九字段）。
 */
public record CaseVersion(UUID id,
                          UUID datasetVersionId,
                          String caseKey,
                          String scenarioFamilyId,
                          Instant validFrom,
                          Instant validTo,
                          Digest contentDigest,
                          String sourceArtifactRef,
                          EvalCaseV1 caseContent) {

    public CaseVersion {
        Objects.requireNonNull(id, "id 不得为 null");
        Objects.requireNonNull(datasetVersionId, "datasetVersionId 不得为 null");
        Objects.requireNonNull(validFrom, "validFrom 不得为 null");
        Objects.requireNonNull(contentDigest, "contentDigest 不得为 null");
        Objects.requireNonNull(caseContent, "caseContent 不得为 null");
        DatasetVersion.requireText(caseKey, "caseKey");
        DatasetVersion.requireText(scenarioFamilyId, "scenarioFamilyId");
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("valid_to 必须晚于 valid_from（或 null 开放期）");
        }
    }
}
