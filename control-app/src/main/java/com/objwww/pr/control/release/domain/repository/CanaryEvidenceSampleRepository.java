package com.objwww.pr.control.release.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Canary 采集样本仓储（B4，V30 canary_evidence_sample）：append-only，
 * run_id 唯一 = 一 run 一样本（幂等锚）；窗口任务按采集时间窗读。
 * 零框架（release.domain.repository 规则）：jsonb 载荷以 Map 承载，序列化归基础设施。
 * 写入唯一入口 = {@code CanaryEvidenceSampleCollector}（ArchUnit 钉：LIVE_CANARY
 * 只能由生产采集适配器构造，INV-AM6-9 禁脚本补数）。
 */
public interface CanaryEvidenceSampleRepository {

    /** 追加样本；false = run_id 已有样本（幂等重放，非错误） */
    boolean insert(SampleRow row);

    /** 采集时间窗读面（窗口任务评窗输入；created_at ∈ [from, to)） */
    List<SampleRow> findByCollectedBetween(Instant from, Instant to);

    /** V30 canary_evidence_sample 列投影 */
    record SampleRow(UUID runId,
                     UUID incidentId,
                     String stickinessKey,
                     String evidenceClass,
                     Map<String, Object> provenance,
                     Map<String, Object> observed,
                     Instant createdAt) {
    }
}
