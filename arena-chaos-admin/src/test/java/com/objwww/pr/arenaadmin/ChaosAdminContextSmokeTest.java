package com.objwww.pr.arenaadmin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认 profile 空跑验证（M2-02 骨架面）：无 DataSource，上下文可启动。
 */
@SpringBootTest
class ChaosAdminContextSmokeTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsWithoutDataSource() {
        assertThat(context).isNotNull();
        assertThat(context.getBeanNamesForType(javax.sql.DataSource.class)).isEmpty();
    }
}
