package com.objwww.pr.control.eval.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 陈旧 worker 自拒护栏：classpath 迁移面扫描 + fail-closed 语义 */
class ClasspathFlywayFreshnessGuardTest {

    @Test
    @DisplayName("classpath 迁移面扫描：测试 classpath 含 src/main/resources 的 "
            + "db/migration，最大版本号为正")
    void classpathScanFindsMigrations() throws Exception {
        ClasspathFlywayFreshnessGuard guard =
                new ClasspathFlywayFreshnessGuard(mock(JdbcClient.class));
        assertThat(guard.classpathMaxVersion()).isPositive();
    }

    @Test
    @DisplayName("检查自身失败 fail-closed：DB 读面异常 → stale=true（不领取）")
    void checkFailureIsFailClosed() {
        JdbcClient jdbc = mock(JdbcClient.class);
        when(jdbc.sql(anyString()))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        assertThat(new ClasspathFlywayFreshnessGuard(jdbc).stale()).isTrue();
    }
}
