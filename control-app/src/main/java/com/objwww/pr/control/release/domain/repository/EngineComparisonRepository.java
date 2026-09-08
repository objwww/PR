package com.objwww.pr.control.release.domain.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * engine_comparison 端口（V32，M6-02）：引擎对照结论 append-only 落账面。
 *
 * <p>insert-only 证据表纪律同 V25/V30：实现只允许 INSERT+SELECT，零 UPDATE/DELETE
 * 路径（授权面同构封死）；uq_ec_pair 冲突 = 同对照幂等（append 返回 false，非错误）。
 * jsonb 载荷以 Map/List 传入，序列化在仓储边界完成（release 域零框架）。
 */
public interface EngineComparisonRepository {

    /**
     * 追加一行对照结论。uq_ec_pair (native_run_id, comparison_key) 冲突 = 同对照
     * 已落账（幂等重放），返回 false。
     */
    boolean append(ComparisonRow row);

    /** 按 Native 侧 run 读对照行（created_at 升序；供观察面/测试回读） */
    List<ComparisonRow> findByNativeRunId(UUID nativeRunId);

    /**
     * 一行对照结论。holmesOutcome/nativeOutcome = 双侧归一化结论 Map（同形状）；
     * disagreeFlags = 六维差异标记（元素含 dim/holmes/native 键）；noiseBaseline
     * M6-02 恒 null（M6-05 底噪校准回填）；costCompare 键缺侧省略。
     */
    record ComparisonRow(
            UUID nativeRunId,
            String comparisonKey,
            String shadowExecRef,
            String snapshotDigest,
            Map<String, Object> holmesOutcome,
            Map<String, Object> nativeOutcome,
            List<Map<String, Object>> disagreeFlags,
            Map<String, Object> noiseBaseline,
            Map<String, Object> costCompare) {
        public ComparisonRow {
            java.util.Objects.requireNonNull(nativeRunId, "nativeRunId");
            java.util.Objects.requireNonNull(comparisonKey, "comparisonKey");
            java.util.Objects.requireNonNull(shadowExecRef, "shadowExecRef");
            java.util.Objects.requireNonNull(snapshotDigest, "snapshotDigest");
            java.util.Objects.requireNonNull(disagreeFlags, "disagreeFlags");
            disagreeFlags = List.copyOf(disagreeFlags);
        }
    }
}
