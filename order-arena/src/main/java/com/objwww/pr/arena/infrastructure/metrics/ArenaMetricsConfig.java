package com.objwww.pr.arena.infrastructure.metrics;

import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业务指标出口（M2-21/22 的承载面）：手动装配 PrometheusMeterRegistry，
 * /metrics 文本端点供 prometheus 抓取（deploy/alert/prometheus/prometheus.yml 的 arena job）。
 * DomainProbe 的 Gauge/Counter 双族都注册进此 registry——探测产物是唯一指标来源
 * （INV-AM2-5：禁止注入点自报）。
 *
 * <p>JVM 内存/GC 指标显式绑定（micrometer 不自动挂）：DP-C01 的 512MiB 内存水位取证直接读
 * jvm_memory_used_bytes，不依赖容器外观测。
 */
@Configuration
class ArenaMetricsConfig {

    @Bean
    PrometheusMeterRegistry arenaPrometheusRegistry() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        // 告警指纹的冻结标签面之一（C-6）：service=order-arena 必须稳定出现在全部指标上
        registry.config().commonTags("service", "order-arena");
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        return registry;
    }

    @RestController
    static class MetricsController {

        private final PrometheusMeterRegistry registry;

        MetricsController(PrometheusMeterRegistry registry) {
            this.registry = registry;
        }

        @GetMapping(path = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
        String metrics() {
            return registry.scrape();
        }
    }
}
