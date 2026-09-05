package com.objwww.pr.control.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 观测装配（M3-27~29）：最小指标 + 结构化事件日志的工具面。无 profile 门——
 * eval/docker 两形态同构（指标注册无害；actuator 暴露面在 application.yml 收口）。
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public AlertMetrics alertMetrics(MeterRegistry registry) {
        return new AlertMetrics(registry);
    }
}
