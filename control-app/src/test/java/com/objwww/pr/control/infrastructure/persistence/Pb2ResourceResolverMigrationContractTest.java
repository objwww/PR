package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V115（PB-B2）本地静态门：权威清单 + 快照锚 + 扩张台账（§2.2 R6）。
 * 真实 PG 行为由 195 真机台账与 Testcontainers IT 覆盖。
 */
class Pb2ResourceResolverMigrationContractTest {

    private static final Path V115 = Path.of(
            "src/main/resources/db/migration/V115__pb2_resource_resolver.sql");

    private String normalized() throws IOException {
        return String.join(" ", Files.readAllLines(V115)).replaceAll("\\s+", " ");
    }

    @Test
    void v115_authoritativeInventory() throws IOException {
        String sql = normalized();
        assertThat(sql).contains("create table resource_inventory");
        assertThat(sql).contains("create table resource_alias");
        // B 组不变量语义锚：告警标签零授权效力、enabled=false fail-closed
        assertThat(sql).contains("零授权效力");
        assertThat(sql).contains("fail-closed");
        assertThat(sql).contains("resource_version");
        assertThat(sql).contains("enabled");
    }

    @Test
    void v115_intentSnapshotColumnsAndExpansionAnchor() throws IOException {
        String sql = normalized();
        // 意图携带解析结果（快照 + hash 锚）
        assertThat(sql).contains("add column resolved_resource_uid");
        assertThat(sql).contains("add column scope_snapshot");
        assertThat(sql).contains("add column scope_snapshot_hash");
        // 扩张台账：无锚 APPROVED 被 DDL 拒绝（scope 扩张必有新审批锚定）
        assertThat(sql).contains("create table scope_expansion");
        assertThat(sql).contains("ck_scope_expansion_anchor");
        assertThat(sql).contains("(status = 'APPROVED') = (approval_id is not null)");
        assertThat(sql).contains("'PENDING_APPROVAL','APPROVED','REJECTED'");
    }
}
