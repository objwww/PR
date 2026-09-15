package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V112（PA-A2）本地静态门：rca_event 哈希链双列 + 口径成文（assumed DB write
 * boundary）。真实 PG 链写/验链行为由 195 真机台账与 Testcontainers IT 覆盖。
 */
class Pa2EventHashChainMigrationContractTest {

    private static final Path V112 = Path.of(
            "src/main/resources/db/migration/V112__pa2_event_hash_chain.sql");

    private String normalized() throws IOException {
        return String.join(" ", Files.readAllLines(V112)).replaceAll("\\s+", " ");
    }

    @Test
    void v112_addsHashChainColumnsAsVarchar64() throws IOException {
        String sql = normalized();
        assertThat(sql).contains("alter table rca_event add column prev_hash varchar(64)");
        assertThat(sql).contains("alter table rca_event add column event_hash varchar(64)");
    }

    @Test
    void v112_documentsTamperEvidentBoundaryClaim() throws IOException {
        // R9 评审口径成文：不宣称"密码学不可改"，只声明库内写边界的篡改可证
        assertThat(normalized()).contains("tamper-evident under the assumed DB write boundary");
    }
}
