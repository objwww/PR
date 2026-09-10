package com.objwww.pr.control.eval.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * EV-07 对比结论落档模型（V85 eval_comparison；insert-only，落档即冻结——
 * 重复落档 = 换新 id 一行，与 V10 eval_case_result / M5-08 EvaluationRecordV1 同律）。
 * 一次基线×候选对比的完整证据：可比性维度清单 + 配对计数 + PairedTrialStats 溯源
 * 快照（jsonb 原文）+ 对比质量门结论与机器码原因（规则版本锚可复现）。
 *
 * <p>值面 jsonb 字段（dimensionDiffsJson/statsSnapshotJson）以 ::text 原文形态持有，
 * 结构装配归应用服务（EvalCompareService）——本类型零框架不反向依赖 application 包。
 *
 * @param id               落档 id（insert-only 主键）
 * @param baselineRunId    基线 run
 * @param candidateRunId   候选 run（≠ baseline，DB CHECK 兜底）
 * @param comparable       严格维度全等 = true（false 时不出配对结论）
 * @param dimensionDiffsJson 可比性维度清单快照（jsonb 数组原文）
 * @param pairedCount      双侧都有结果且输入 digest 未冲突的配对案例数
 * @param unpairedCount    单侧缺席 / 输入 digest 冲突的案例数
 * @param improvedCount    改善计数（baseline 未命中 & candidate 命中）
 * @param regressedCount   退化计数（baseline 命中 & candidate 未命中）
 * @param flatCount        持平计数（两侧命中面一致）
 * @param statsSnapshotJson PairedTrialStats 溯源快照原文；null = 无配对（不伪造统计面）
 * @param gateOutcome      对比质量门结论 PASS/FAIL/INCONCLUSIVE/NOT_EVALUABLE
 * @param gateReasons      机器码原因（PASS 空，非 PASS 必非空——解释完整性）
 * @param gateRuleVersion  门规则版本锚（eval-compare-gate-vN）
 * @param actor            落档主体（认证面唯一来源）
 * @param createdAt        落档时刻
 */
public record EvalComparisonRecord(UUID id,
                                   UUID baselineRunId,
                                   UUID candidateRunId,
                                   boolean comparable,
                                   String dimensionDiffsJson,
                                   int pairedCount,
                                   int unpairedCount,
                                   int improvedCount,
                                   int regressedCount,
                                   int flatCount,
                                   String statsSnapshotJson,
                                   String gateOutcome,
                                   List<String> gateReasons,
                                   String gateRuleVersion,
                                   String actor,
                                   Instant createdAt) {

    /** 门结论词表（V85 CHECK 同序） */
    public static final String OUTCOME_PASS = "PASS";
    public static final String OUTCOME_FAIL = "FAIL";
    public static final String OUTCOME_INCONCLUSIVE = "INCONCLUSIVE";
    public static final String OUTCOME_NOT_EVALUABLE = "NOT_EVALUABLE";

    public EvalComparisonRecord {
        Objects.requireNonNull(id, "id 不得为 null");
        Objects.requireNonNull(baselineRunId, "baselineRunId 不得为 null");
        Objects.requireNonNull(candidateRunId, "candidateRunId 不得为 null");
        if (baselineRunId.equals(candidateRunId)) {
            throw new IllegalArgumentException("baseline 与 candidate 不得为同一 run");
        }
        Objects.requireNonNull(dimensionDiffsJson, "dimensionDiffsJson 不得为 null");
        if (pairedCount < 0 || unpairedCount < 0 || improvedCount < 0
                || regressedCount < 0 || flatCount < 0) {
            throw new IllegalArgumentException("配对计数不得为负");
        }
        Objects.requireNonNull(gateOutcome, "gateOutcome 不得为 null");
        Objects.requireNonNull(gateReasons, "gateReasons 不得为 null");
        gateReasons = List.copyOf(gateReasons);
        if (OUTCOME_PASS.equals(gateOutcome) && !gateReasons.isEmpty()) {
            throw new IllegalArgumentException("PASS 不得携带未过原因");
        }
        if (!OUTCOME_PASS.equals(gateOutcome) && gateReasons.isEmpty()) {
            throw new IllegalArgumentException("非 PASS 结论必须携带机器码原因（解释完整性）");
        }
        Objects.requireNonNull(gateRuleVersion, "gateRuleVersion 不得为 null");
        Objects.requireNonNull(actor, "actor 不得为 null");
        Objects.requireNonNull(createdAt, "createdAt 不得为 null");
    }
}
