package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V113（PA-A3）本地静态门：alert_inbox QUARANTINED 第七态（L0-5 隔离区）。
 * 真实 PG 行为由 195 真机台账与 Testcontainers IT 覆盖。
 */
class Pa3InjectionQuarantineMigrationContractTest {

    private static final Path V113 = Path.of(
            "src/main/resources/db/migration/V113__pa3_injection_quarantine.sql");

    private String normalized() throws IOException {
        return String.join(" ", Files.readAllLines(V113)).replaceAll("\\s+", " ");
    }

    @Test
    void v113_widensInboxStateWithQuarantined() throws IOException {
        String sql = normalized();
        assertThat(sql).contains("alter table alert_inbox drop constraint ck_alert_inbox_state");
        assertThat(sql).contains("'QUARANTINED'");
        // 既有六态不回退
        assertThat(sql).contains("'RECEIVED'");
        assertThat(sql).contains("'DEAD_LETTER'");
    }

    @Test
    void v113_documentsQuarantineSemantics() throws IOException {
        String sql = normalized();
        // 语义锚：初始态直插 + claim 不可达 + 人工放行边
        assertThat(sql).contains("初始态直插");
        assertThat(sql).contains("claim 面不可达");
        assertThat(sql).contains("QUARANTINED→RECEIVED");
    }
}
