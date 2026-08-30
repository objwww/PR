package com.objwww.pr.control;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认 profile 空跑验证：无 DataSource、无 docker 专属 bean，应用上下文可启动。
 * docker-only 装配（PersistenceConfig/ReviewFlowConfig/WebhookController）在此 profile 一律不激活。
 */
@SpringBootTest
class ControlContextSmokeTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsWithoutDataSource() {
        assertThat(context).isNotNull();
        // 默认 profile：无 DataSource、无 webhook 端点、无 PG repository
        assertThat(context.getBeanNamesForType(
                javax.sql.DataSource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(
                com.objwww.pr.control.interfaces.webhook.WebhookController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(
                com.objwww.pr.control.domain.repository.PRSubjectRepository.class)).isEmpty();
        // T10/T14 的新组件同属 docker-only 装配：Worker 与窄接口客户端默认 profile 不在
        assertThat(context.getBeanNamesForType(
                com.objwww.pr.control.application.WorkItemWorker.class)).isEmpty();
        assertThat(context.getBeanNamesForType(
                com.objwww.pr.control.domain.port.CredentialTokenPort.class)).isEmpty();
        // T15 启动自检（需 DataSource）同样只在 docker profile 装配
        assertThat(context.getBeanNamesForType(
                com.objwww.pr.control.infrastructure.selfcheck.StartupSelfCheckRunner.class)).isEmpty();
    }
}
