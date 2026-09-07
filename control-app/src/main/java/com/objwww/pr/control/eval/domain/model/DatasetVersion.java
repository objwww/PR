package com.objwww.pr.control.eval.domain.model;

import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * DatasetVersion（M5-01）：insert-only 数据集版本头。
 * 来源九字段（source/name/version/source_uri/license/access_class/content_digest/
 * adapter_version/imported_at，方案 §3.1 v1.1）+ source_class 分级身份 +
 * partition_class 四分区归属 + scenario_family_digest（本版本案例族清单摘要，
 * 整组分区键 scenario_family_id 列在 case 侧）。历史不可覆盖（INV-AM5-1），
 * 不实现评分。
 */
public record DatasetVersion(UUID id,
                             String source,
                             String name,
                             String version,
                             String sourceUri,
                             String license,
                             String accessClass,
                             Digest contentDigest,
                             String adapterVersion,
                             Instant importedAt,
                             SourceClass sourceClass,
                             PartitionClass partitionClass,
                             Digest scenarioFamilyDigest) {

    public DatasetVersion {
        Objects.requireNonNull(id, "id 不得为 null");
        Objects.requireNonNull(contentDigest, "contentDigest 不得为 null");
        Objects.requireNonNull(importedAt, "importedAt 不得为 null");
        Objects.requireNonNull(sourceClass, "sourceClass 不得为 null");
        Objects.requireNonNull(partitionClass, "partitionClass 不得为 null");
        Objects.requireNonNull(scenarioFamilyDigest, "scenarioFamilyDigest 不得为 null");
        requireText(source, "source");
        requireText(name, "name");
        requireText(version, "version");
        requireText(sourceUri, "sourceUri");
        requireText(license, "license");
        requireText(accessClass, "accessClass");
        requireText(adapterVersion, "adapterVersion");
    }

    /**
     * 案例族清单摘要：本数据集版本内去重排序后的 scenario_family_id 规范串
     * （'\n' 连接）做 SHA-256。导入批次内任意族增删都会改变摘要——版本锚定面之一。
     */
    public static Digest familyDigest(List<EvalCaseV1> cases) {
        Objects.requireNonNull(cases, "cases 不得为 null");
        String canonical = cases.stream()
                .map(EvalCaseV1::scenarioFamilyId)
                .distinct()
                .sorted()
                .collect(Collectors.joining("\n"));
        return Digest.sha256Of(canonical);
    }

    /** 文本字段非空校验（model/port 两个域子包共用的契约面） */
    public static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " 不得为 null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为 blank");
        }
        return value;
    }
}
