package com.objwww.pr.control.release.domain.model;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Canary 窗口评判策略（M6-01，O-63）：全部取值来自 active bundle
 * {@code canary.window} 段，随 bundle digest 版本化——<b>禁硬编码默认值</b>
 * （50 样本/72h 等真实值归基线 spike 后由操作者经 bundle 显式声明）。
 * 段缺失/键缺失/类型非法 → {@link #fromBundle} 返回 empty（fail-closed：
 * 无策略不建窗不评判，绝不猜参数）。
 *
 * @param minSamplesPerWindow  单窗最小独立样本数（incident 聚类后）
 * @param consecutiveWindows   晋升要求的连续 PASS 窗数 K
 * @param windowLength         单窗墙钟长度
 * @param maxAbsoluteFailRate  绝对 SLO 门：native 失败率上限
 * @param relativeTolerance    相对门：native 失败率 - control 失败率的容差
 * @author wanghua
 * @date 2026-09-08
 */
public record CanaryWindowPolicy(int minSamplesPerWindow,
                                int consecutiveWindows,
                                Duration windowLength,
                                double maxAbsoluteFailRate,
                                double relativeTolerance) {

    public CanaryWindowPolicy {
        Objects.requireNonNull(windowLength, "windowLength");
        if (minSamplesPerWindow < 1 || consecutiveWindows < 1) {
            throw new IllegalArgumentException("min_samples/consecutive_windows 必须 ≥1");
        }
        if (windowLength.isNegative() || windowLength.isZero()) {
            throw new IllegalArgumentException("window_minutes 必须为正");
        }
        if (maxAbsoluteFailRate < 0 || maxAbsoluteFailRate > 1
                || relativeTolerance < 0 || relativeTolerance > 1) {
            throw new IllegalArgumentException("失败率/容差必须在 [0,1]");
        }
    }

    private static final String SECTION = "window";

    /** bundle canary 段装载：段缺失/键缺/类型非法 → empty（fail-closed，禁猜默认） */
    public static Optional<CanaryWindowPolicy> fromBundle(Map<String, Object> canarySection) {
        if (!(canarySection.get(SECTION) instanceof Map<?, ?> raw)) {
            return Optional.empty();
        }
        Integer minSamples = intOf(raw.get("min_samples"));
        Integer consecutive = intOf(raw.get("consecutive_windows"));
        Integer windowMinutes = intOf(raw.get("window_minutes"));
        Double absoluteRate = doubleOf(raw.get("max_absolute_fail_rate"));
        Double tolerance = doubleOf(raw.get("relative_tolerance"));
        if (minSamples == null || consecutive == null || windowMinutes == null
                || absoluteRate == null || tolerance == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new CanaryWindowPolicy(minSamples, consecutive,
                    Duration.ofMinutes(windowMinutes), absoluteRate, tolerance));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Integer intOf(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static Double doubleOf(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }
}
