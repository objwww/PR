package com.objwww.pr.control.alert.application;

/**
 * 入口尺寸限制（§6.4"全部可配"；AM 侧配套 webhook_configs max_alerts=100/timeout=10s）。
 */
public record AlertIntakeLimits(
        int maxBodyBytes,
        int maxAlerts,
        int maxLabelChars,
        int maxTotalLabelChars,
        int maxDepth,
        int gzipMaxBytes
) {
    /** 默认：body 512KB / 单组 200 条（>AM 侧 100 截断，留头寸）/ 单值 2KB / 总 32KB / 深 32 / 解压 2MB */
    public static AlertIntakeLimits defaults() {
        return new AlertIntakeLimits(512 * 1024, 200, 2_000, 32_000, 32, 2 * 1024 * 1024);
    }

    public AlertIntakeLimits {
        if (maxBodyBytes < 1 || maxAlerts < 1 || maxLabelChars < 1
                || maxTotalLabelChars < 1 || maxDepth < 1 || gzipMaxBytes < 1) {
            throw new IllegalArgumentException("入口限制必须为正");
        }
    }
}
