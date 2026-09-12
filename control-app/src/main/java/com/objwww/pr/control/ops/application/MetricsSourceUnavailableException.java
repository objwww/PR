package com.objwww.pr.control.ops.application;

/**
 * 监控指标源不可用（方案 §三.12「未采集显示未知」的传输层一半）：
 * Prometheus 未配置 / 不可达 / 应答非法时抛出，HTTP 面映射 503——
 * 前端据此显示「监控数据源未配置/不可达」，不得以空数据冒充已采集。
 */
public class MetricsSourceUnavailableException extends RuntimeException {

    public MetricsSourceUnavailableException(String message) {
        super(message);
    }

    public MetricsSourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
