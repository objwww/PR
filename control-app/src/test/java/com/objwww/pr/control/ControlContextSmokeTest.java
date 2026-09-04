package com.objwww.pr.control;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认 profile 空跑验证：无 DataSource、无 docker 专属 bean，应用上下文可启动。
 * docker-only 装配（PersistenceConfig/M3ModelGatewayConfig/SelfCheckConfig/告警 webhook）
 * 在此 profile 一律不激活。
 */
@SpringBootTest
class ControlContextSmokeTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsWithoutDataSource() {
        assertThat(context).isNotNull();
        // 默认 profile：无 DataSource、无 PG repository、无启动自检
        assertThat(context.getBeanNamesForType(
                javax.sql.DataSource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(
                com.objwww.pr.control.domain.ai.ModelCallLedgerRepository.class)).isEmpty();
        assertThat(context.getBeanNamesForType(
                com.objwww.pr.control.application.ModelGateway.class)).isEmpty();
        assertThat(context.getBeanNamesForType(
                com.objwww.pr.control.infrastructure.selfcheck.StartupSelfCheckRunner.class)).isEmpty();
    }
}
