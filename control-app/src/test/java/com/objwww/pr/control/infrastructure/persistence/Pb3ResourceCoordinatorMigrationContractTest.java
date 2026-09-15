package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V116（PB-B3）本地静态门：资源 mutation 锁（§2.9 R3 首日正确）。
 * 真实 PG 行为由 195 真机台账与 Testcontainers IT 覆盖。
 */
class Pb3ResourceCoordinatorMigrationContractTest {

    private static final Path V116 = Path.of(
            "src/main/resources/db/migration/V116__pb3_resource_coordinator.sql");

    private String normalized() throws IOException {
        return String.join(" ", Files.readAllLines(V116)).replaceAll("\\s+", " ");
    }

    @Test
    void v116_lockLifecycleBoundToOperationState() throws IOException {
        String sql = normalized();
        assertThat(sql).contains("create table resource_mutation_lock");
        // TTL 语义：只驱动孤儿化，绝不让渡（B 组不变量）
        assertThat(sql).contains("只驱动孤儿化");
        assertThat(sql).contains("锁行不删");
        // ORPHANED 也是 BUSY（行存在即 BUSY）
        assertThat(sql).contains("'HELD','ORPHANED'");
        assertThat(sql).contains("resource_epoch");
    }

    @Test
    void v116_epochSurvivesLockRelease() throws IOException {
        String sql = normalized();
        // 代数独立成表：锁释放删行后 resource_epoch 仍单调
        assertThat(sql).contains("create table resource_mutation_counter");
        assertThat(sql).contains("锁释放删行后代数仍单调");
        assertThat(sql).contains("lease_epoch 无关");
    }
}
