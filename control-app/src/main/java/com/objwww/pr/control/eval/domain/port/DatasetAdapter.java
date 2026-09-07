package com.objwww.pr.control.eval.domain.port;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.domain.model.DatasetVersion;
import com.objwww.pr.control.eval.domain.model.EvalCaseV1;
import com.objwww.pr.control.eval.domain.model.PartitionClass;
import com.objwww.pr.control.eval.domain.model.SourceClass;
import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * DatasetAdapter 端口（M5-01，v1.1 数据集三层统一）：外部数据集 → 内部
 * EvalCaseV1 的纯转换面，签名供 M5-05/06 复用。新增 benchmark 只加实现，
 * 不动 EvalCaseV1 契约与门禁。实现落 eval/infrastructure/adapter（infra 层），
 * 不触网：artifact 获取/解析归各 E2E runner，本端口只做已解析条目的规范化
 * 转换与来源策略（source/source_class/adapter_version/版本锚定/授权门）。
 */
public interface DatasetAdapter {

    /** 数据集版本头：来源身份/分级/适配器版本由适配器策略固定，id/importedAt 由调用方注入 */
    DatasetVersion datasetVersion(ImportRequest request, UUID id, Instant importedAt);

    /** 规范化条目 → EvalCaseV1 序列（family/原始 artifact 原样保留；整批拒绝语义） */
    List<EvalCaseV1> importCases(ImportRequest request);

    /**
     * family 整组分区断言（M5-01 应用层，M5-02 以 case_version 上
     * unique (scenario_family_id, partition_class) 兜底）：同 scenario_family_id
     * 拆分跨 TUNING/HOLDOUT 等分区直接拒绝。assigned 为累积的 族→分区 指派面，
     * 导入 runner 在落每族前调用（通过后自行 putIfAbsent）。
     */
    static void assertFamilyPartition(String family,
                                      PartitionClass partition,
                                      Map<String, PartitionClass> assigned) {
        Objects.requireNonNull(family, "family 不得为 null");
        Objects.requireNonNull(partition, "partition 不得为 null");
        Objects.requireNonNull(assigned, "assigned 不得为 null");
        PartitionClass existing = assigned.get(family);
        if (existing != null && existing != partition) {
            throw new IllegalArgumentException("family 整组分区违约: scenario_family_id="
                    + family + " 已划入 " + existing + "，禁止拆入 " + partition);
        }
    }

    /** 数据集身份面：版本 + manifest 摘要锚定（RCA-100 禁浮动 latest 的检查对象） */
    record DatasetRef(String name, String version, Digest manifestDigest) {
        public DatasetRef {
            DatasetVersion.requireText(name, "name");
            DatasetVersion.requireText(version, "version");
            Objects.requireNonNull(manifestDigest, "manifestDigest 不得为 null");
        }
    }

    /**
     * 外部数据集的单条规范化案例（M5-01 中性导入面）：artifact 解析
     * （Parquet/JSON/…归各 E2E runner）产出后进入统一转换；
     * sourceArtifactRef 为原始 artifact 引用指针（可空，不覆盖来源字段）。
     */
    record ImportEntry(String caseKey,
                       String scenarioFamilyId,
                       TypedRootCause expectedRootCause,
                       List<String> expectedSymptomCodes,
                       Map<String, Object> rawArtifact,
                       String sourceArtifactRef) {}

    /** 导入请求面：数据集身份 + 四分区归属声明 + 来源请求侧字段 + 已解析条目 */
    record ImportRequest(DatasetRef datasetRef,
                         PartitionClass partitionClass,
                         String sourceUri,
                         String license,
                         String accessClass,
                         List<ImportEntry> entries) {

        public ImportRequest {
            Objects.requireNonNull(datasetRef, "datasetRef 不得为 null");
            Objects.requireNonNull(partitionClass, "partitionClass 不得为 null");
            DatasetVersion.requireText(sourceUri, "sourceUri");
            DatasetVersion.requireText(license, "license");
            DatasetVersion.requireText(accessClass, "accessClass");
            Objects.requireNonNull(entries, "entries 不得为 null");
            entries = List.copyOf(entries);
        }
    }

    /** 三实现共用的版本头组装：name/version/manifest 取自 datasetRef，其余策略入参 */
    static DatasetVersion version(ImportRequest r, UUID id, Instant importedAt,
                                  String source, SourceClass sourceClass,
                                  PartitionClass partitionClass, String adapterVersion,
                                  Digest scenarioFamilyDigest) {
        return new DatasetVersion(id, source, r.datasetRef().name(), r.datasetRef().version(),
                r.sourceUri(), r.license(), r.accessClass(), r.datasetRef().manifestDigest(),
                adapterVersion, importedAt, sourceClass, partitionClass, scenarioFamilyDigest);
    }

    /** 三实现共用的案例转换：EvalCaseV1 构造器自身校验 caseKey/family 非空（整批拒绝） */
    static List<EvalCaseV1> cases(ImportRequest r) {
        return r.entries().stream()
                .map(e -> new EvalCaseV1(e.caseKey(), e.scenarioFamilyId(),
                        e.expectedRootCause(), e.expectedSymptomCodes(), e.rawArtifact()))
                .toList();
    }
}
