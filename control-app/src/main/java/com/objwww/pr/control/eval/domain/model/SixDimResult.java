package com.objwww.pr.control.eval.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * M5-06 六维评测结果（落码方案 §M5-06③ 契约）：每维 {@code {rawCounts, traceRefs[]}}——
 * <b>不聚合成单一分</b>（方案 §3.1；结构面由 SixDimEvaluatorTest 反射锁定）。
 * traceRefs 指回证据面（{@code claim:{i}} / {@code tool_call:{i}}，下标为输入序列位），
 * 支撑"每项原始计数可追溯到 event/evidence"拆解验收。
 *
 * <p>AC 口径（v1.1 评审裁定，方案 §3.1 原文）：当前单根因输出只算 AC@1；
 * AC@3/5 需要 {@code candidate_root_causes[]} 契约，未落契约前
 * {@link #acAt(int)} 对 k≥2 直接拒绝（fail-closed，禁收窄假口径）。
 *
 * <p>零框架（L0：am5DatasetDomainZeroFrameworkDependency 面）。
 */
public record SixDimResult(Dim<DimensionCounts.Result> result,
                           Dim<DimensionCounts.Process> process,
                           Dim<DimensionCounts.Tool> tool,
                           Dim<DimensionCounts.Cost> cost,
                           Dim<DimensionCounts.Collaboration> collaboration,
                           Dim<DimensionCounts.Safety> safety) {

    public SixDimResult {
        Objects.requireNonNull(result, "result 不得为 null");
        Objects.requireNonNull(process, "process 不得为 null");
        Objects.requireNonNull(tool, "tool 不得为 null");
        Objects.requireNonNull(cost, "cost 不得为 null");
        Objects.requireNonNull(collaboration, "collaboration 不得为 null");
        Objects.requireNonNull(safety, "safety 不得为 null");
    }

    /** 单维投影：原始计数 + 证据追溯引用（score 有意缺席——v1 无任何维出分） */
    public record Dim<T>(T rawCounts,
                         List<String> traceRefs) {

        public Dim {
            Objects.requireNonNull(rawCounts, "rawCounts 不得为 null");
            Objects.requireNonNull(traceRefs, "traceRefs 不得为 null");
            traceRefs = List.copyOf(traceRefs);
        }
    }

    /**
     * AC@k：k=1 → 根因命中 1/0；k≥2 → 无 {@code candidate_root_causes[]} 契约，
     * 拒绝计算（v1.1 裁定 fail-closed）；k&lt;1 → 非法口径。
     */
    public int acAt(int k) {
        if (k < 1) {
            throw new IllegalArgumentException("AC@k 的 k 必须 ≥ 1: " + k);
        }
        if (k != 1) {
            throw new UnsupportedOperationException(
                    "AC@" + k + " 需要 candidate_root_causes[] 契约（v1.1 裁定：无契约禁算，"
                            + "单根因输出仅支持 AC@1）");
        }
        return result.rawCounts().rootCauseHit() ? 1 : 0;
    }
}
