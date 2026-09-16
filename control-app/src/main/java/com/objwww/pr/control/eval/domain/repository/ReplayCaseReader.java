package com.objwww.pr.control.eval.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 回放案例只读端口（P2 执行集接通）：case_version × dataset_version 按**数据集版本
 * 字符串**精确键取当前生效案例（[valid_from, valid_to) 半开区间），供
 * DatasetCaseMapper 映射为 REPLAY 形态 GoldenCase 合入执行注册表。
 *
 * <p>eval_app 身份（V20 SELECT 授权）；HOLDOUT 分区行由 V21 RLS 在 DB 面天然滤除
 * ——读面与执行面同视界，不冒充全量。payload 为 EvalCaseV1 序列化原文（::text
 * 上抛，解析归应用服务）。
 */
public interface ReplayCaseReader {

    /** 回放案例行（payload 原文 + 身份列；解析与校验归 DatasetCaseMapper） */
    record ReplayCaseRow(UUID datasetVersionId, String datasetName, String datasetVersion,
                         String caseKey, String scenarioFamilyId, String payloadJson,
                         String contentDigest, Instant validFrom) {
    }

    /** 指定数据集版本字符串的当前生效案例集（升序稳定序；无匹配 → 空表） */
    List<ReplayCaseRow> listReplayCases(String datasetVersion);
}
