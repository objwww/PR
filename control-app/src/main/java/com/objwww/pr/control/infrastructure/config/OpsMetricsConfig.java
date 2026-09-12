package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.infrastructure.metrics.PrometheusRangeGateway;
import com.objwww.pr.control.ops.application.MetricsWhitelistService;
import com.objwww.pr.control.ops.domain.repository.MetricsRangeGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 监控页指标白名单代理接线（仅 docker profile；方案 §三.12 + 全量联通方案 §5.11/§6 B6）。
 *
 * <p>Prometheus 地址复用 AM4 同一配置事实源
 * {@code app.alert.am4.prometheus.base-url}（AlertAm4Config 同键同默认值）——
 * 监控页与 Agent 工具面读同一个 Prometheus，但互不借用执行器代码。
 */
@Configuration
@Profile("docker")
public class OpsMetricsConfig {

    private static final String PROMETHEUS_BASE_URL_KEY =
            "${app.alert.am4.prometheus.base-url:http://prometheus:9090}";

    @Bean
    public MetricsRangeGateway metricsRangeGateway(
            @Value(PROMETHEUS_BASE_URL_KEY) String prometheusBaseUrl) {
        return new PrometheusRangeGateway(prometheusBaseUrl);
    }

    @Bean
    public MetricsWhitelistService metricsWhitelistService(MetricsRangeGateway gateway) {
        return new MetricsWhitelistService(gateway, java.time.Instant::now);
    }
}
