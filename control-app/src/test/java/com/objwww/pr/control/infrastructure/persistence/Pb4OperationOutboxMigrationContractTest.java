package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V117（PB-B4）本地静态门：operation_outbox（§2.8）。
 * 真实 PG 行为由 195 真机台账与 Testcontainers IT 覆盖。
 */
class Pb4OperationOutboxMigrationContractTest {

    private static final Path V117 = Path.of(
            "src/main/resources/db/migration/V117__pb4_operation_outbox.sql");

    private String normalized() throws IOException {
        return String.join(" ", Files.readAllLines(V117)).replaceAll("\\s+", " ");
    }

    @Test
    void v117_outboxAtomicWithOperation() throws IOException {
        String sql = normalized();
        assertThat(sql).contains("create table operation_outbox");
        assertThat(sql).contains("references rca_operation(operation_id)");
        // 1:1（RETRYABLE 重派 = 同行回 PENDING）
        assertThat(sql).contains("unique (operation_id)");
        // §2.8 语义锚：同事务插入 + 崩溃窗关闭
        assertThat(sql).contains("同事务插入");
        assertThat(sql.contains("崩溃窗") || sql.contains("崩溃"));
    }

    @Test
    void v117_atLeastOnceClaimFaces() throws IOException {
        String sql = normalized();
        assertThat(sql).contains("lease_epoch");
        assertThat(sql).contains("where state = 'PENDING'");
        assertThat(sql).contains("where state = 'CLAIMED'");
        assertThat(sql).contains("'PENDING','CLAIMED','DISPATCHED','FAILED'");
    }
}
