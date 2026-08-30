package com.objwww.pr.publisher;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** 默认 profile 空跑验证：Publisher 上下文可启动（M0-T11+ 的 bean 尚未装配）。 */
@SpringBootTest
class PublisherContextSmokeTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
        // T14 窄接口与 CredentialBroker 是 docker-only 装配：默认 profile 不暴露 HTTP 入站
        assertThat(context.getBeanNamesForType(
                com.objwww.pr.publisher.interfaces.token.ReadOnlyTokenController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(
                com.objwww.pr.publisher.infrastructure.credential.CredentialBroker.class)).isEmpty();
        // T15 启动自检（需 DataSource）同样只在 docker profile 装配
        assertThat(context.getBeanNamesForType(
                com.objwww.pr.publisher.infrastructure.selfcheck.StartupSelfCheckRunner.class)).isEmpty();
    }
}
