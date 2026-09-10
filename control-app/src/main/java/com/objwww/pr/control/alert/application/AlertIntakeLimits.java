package com.objwww.pr.control.alert.application;

/**
 * 入口尺寸限制（§6.4"全部可配"；AM 侧配套 webhook_configs max_alerts=100/timeout=10s）。
 */

//各字段含义
//字段	默认值	含义
//maxBodyBytes	512 * 1024 = 512 KiB	HTTP 请求体原始最大字节数，防止请求体过大
//maxAlerts	200	一次请求中最多允许的告警条数
//maxLabelChars	2_000	单个标签值/标签内容最大字符数，注释说“单值 2KB”
//maxTotalLabelChars	32_000	所有标签合计最大字符数，防止标签总量爆炸
//maxDepth	32	JSON 或嵌套结构最大深度，防止深层嵌套攻击
//gzipMaxBytes	2 * 1024 * 1024 = 2 MiB	gzip 解压后的最大字节数，防止压缩炸弹
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
