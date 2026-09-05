package com.objwww.pr.arena;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认 profile 空跑验证（M2-01）：无 DataSource、无 docker 专属 bean，上下文可启动；
 * 指标 registry 与健康/指标端点可用（容器 health 面的最小闭环）。
 */
@SpringBootTest
class ArenaContextSmokeTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsWithoutDataSource() {
        assertThat(context).isNotNull();
        assertThat(context.getBeanNamesForType(javax.sql.DataSource.class)).isEmpty();
    }

    @Test
    void metricsRegistryAvailable() {
        PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);
        assertThat(registry.scrape()).contains("jvm");
    }
}
