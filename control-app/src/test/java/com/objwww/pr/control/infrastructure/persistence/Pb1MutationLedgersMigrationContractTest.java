package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V114（PB-B1）本地静态门：action_intent + rca_operation 账本层（§1/§2.1/§2.11）。
 * 真实 PG 行为由 195 真机台账与 Testcontainers IT 覆盖。
 */
class Pb1MutationLedgersMigrationContractTest {

    private static final Path V114 = Path.of(
            "src/main/resources/db/migration/V114__pb1_mutation_ledgers.sql");

    private String normalized() throws IOException {
        return String.join(" ", Files.readAllLines(V114)).replaceAll("\\s+", " ");
    }

    @Test
    void v114_intentLedgerAnchoredByDigest() throws IOException {
        String sql = normalized();
        assertThat(sql).contains("create table action_intent");
        // §2.1：全链 digest 锚
        assertThat(sql).contains("action_digest");
        assertThat(sql).contains("constraint ck_action_intent_status");
        assertThat(sql).contains("'OPEN','PLANNED','VOIDED'");
        // PLANNED 必须回填 operation_id（生命周期一致性）
        assertThat(sql).contains("(status = 'PLANNED') = (operation_id is not null)");
    }

    @Test
    void v114_operationLedgerStateMachineAndDryRunPhase() throws IOException {
        String sql = normalized();
        assertThat(sql).contains("create table rca_operation");
        assertThat(sql).contains("references action_intent(intent_id)");
        // §2.11 状态全集（含 UNKNOWN/RECONCILING 中间态与 ESCALATED 终态）
        assertThat(sql).contains("'PREPARED','DISPATCHED','ACKNOWLEDGED','VERIFIED','COMPLETED'");
        assertThat(sql).contains("'UNKNOWN','RECONCILING','RETRYABLE','ESCALATED'");
        assertThat(sql).contains("'FAILED_CONFIRMED','CANCELLED_BEFORE_DISPATCH'");
        // Phase B 铁律：dry_run DDL 钉死（Phase D 解锁日退役）
        assertThat(sql).contains("constraint ck_rca_operation_dry_run_phase check (dry_run)");
        // 资源定位边界：resource_uid 可空（B2 Resolver 权威解析前不得定位资源）
        assertThat(sql).contains("resource_uid");
        assertThat(sql).contains("resource_epoch");
    }
}
