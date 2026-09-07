package com.objwww.pr.control.eval.domain.repository;

import com.objwww.pr.control.eval.domain.model.CaseVersion;
import com.objwww.pr.control.eval.domain.model.DatasetVersion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * dataset_version / case_version 端口（M5-01，V20；INV-AM5-1 历史不可覆盖）。
 *
 * <p>SQL 契约：insertDatasetVersion 撞 UNIQUE(name,version) 上抛
 * DuplicateKeyException；insertCaseVersion 撞 UNIQUE(dataset_version_id,case_key)
 * 返回 false（纠错 = 新 dataset_version 携带修正行）；findCasesValidAt 按
 * [valid_from, valid_to) 半开区间解析时点生效集。接口只存在 insert/find 方法面
 * ——无任何 UPDATE/DELETE 路径。
 */
public interface DatasetVersionRepository {

    /** 数据集版本插入；同 (name,version) 已存在抛 DuplicateKeyException */
    void insertDatasetVersion(DatasetVersion version);

    /** 案例版本插入；同 (dataset,case_key) 已存在 = false（不覆盖） */
    boolean insertCaseVersion(CaseVersion version);

    /** 数据集版本查找（二元组身份） */
    Optional<DatasetVersion> findDataset(String name, String version);

    /** 时点生效案例集：valid_from <= at 且 (valid_to 为空 或 valid_to > at) */
    List<CaseVersion> findCasesValidAt(UUID datasetVersionId, Instant at);
}
