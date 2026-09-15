package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V119（PC-C1）本地静态门：审批四账本（§2.4/§2.5/§2.6）。
 * 真实 PG 行为由 195 真机台账与 Testcontainers IT 覆盖。
 */
class Pc1ApprovalLedgersMigrationContractTest {

    private static final Path V119 = Path.of(
            "src/main/resources/db/migration/V119__pc1_approval_ledgers.sql");

    private String normalized() throws IOException {
        return String.join(" ", Files.readAllLines(V119)).replaceAll("\\s+", " ");
    }

    @Test
    void v119_requestBindsStableFactsOnly() throws IOException {
        String sql = normalized();
        assertThat(sql).contains("create table approval_request");
        // §2.4 稳定事实锚：digest + observed_generation + 快照锚 + policy_version；
        // 零瞬时执行身份——列定义形态断言（列真不存在，注释提及不算）
        assertThat(sql).contains("observed_generation");
        assertThat(sql).contains("scope_snapshot_hash");
        assertThat(sql).contains("policy_version");
        assertThat(sql).doesNotContain("owner_worker text");
        assertThat(sql).doesNotContain("lease_epoch");
        assertThat(sql).doesNotContain("attempt_id uuid");
        assertThat(sql).contains("'PENDING','APPROVED','DENIED','EXPIRED','REVOKED','VOIDED'");
    }

    @Test
    void v119_decisionsUniqueAndGrantLayers() throws IOException {
        String sql = normalized();
        // §2.6：同人同审批仅一条（UNIQUE 结构拒绝）
        assertThat(sql).contains("UNIQUE (request_id, approver_id)");
        // §2.5：Grant 可复用范围 + OperationAuthorization single-use
        assertThat(sql).contains("create table approval_grant");
        assertThat(sql).contains("'ONCE','SESSION'");
        assertThat(sql).contains("scope_kind <> 'ONCE' or max_operations = 1");
        assertThat(sql).contains("create table operation_authorization");
        assertThat(sql).contains("'ISSUED','CONSUMED'");
        assertThat(sql).contains("(state = 'CONSUMED') = (consumed_at is not null)");
    }
}
