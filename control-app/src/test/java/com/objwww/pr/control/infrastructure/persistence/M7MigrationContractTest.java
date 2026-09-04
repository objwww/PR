package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AM1-T01 本地静态门：Docker 不可用时也锁住 V7 的关键结构与权限边界（§6.1/§7 INV-AM1-2/4/5）。
 * 真 PostgreSQL 约束/授权行为由 CT-A01 起的集成测试覆盖（195）。
 * 范式沿 M2MigrationContractTest：规范化大小写/空白后整体比对，断言锚定 DDL 形状。
 */
class M7MigrationContractTest {

    private static final Path V7 = Path.of(
            "src/main/resources/db/migration/V7__am1_alert_domain.sql");

    private static String normalized() throws IOException {
        return Files.readString(V7).toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void v7ContainsNineAlertDomainTablesWithEnvelopeShape() throws IOException {
        String sql = normalized();

        assertThat(sql)
                .contains("create table alert_inbox")
                .contains("create table alert_event")
                .contains("create table incident")
                .contains("create table rca_run")
                .contains("create table rca_task")
                .contains("create table rca_attempt")
                .contains("create table rca_report")
                .contains("create table external_invocation_ledger")
                .contains("create table scheduler_slot")
                // group envelope 全字段（AM webhook.go Message）
                .contains("group_key").contains("group_labels")
                .contains("common_labels").contains("common_annotations")
                .contains("group_status").contains("truncated_alerts")
                .contains("payload_raw bytea not null")
                .contains("payload_digest char(64) not null")
                // inbox 六态（V3 webhook_inbox 同构）+ 投影期 decision
                .contains("check (state in ('received','processing','retry_wait',"
                        + "'processed','ignored','dead_letter'))")
                .contains("check (decision is null or decision in "
                        + "('accepted','deferred','suppressed'))");
    }

    @Test
    void alertEventIsImmutableAppendOnlyWithDualHash() throws IOException {
        // §6.3 双哈希分离 + 投影幂等锚点（uq(fingerprint, payload_hash, starts_at)）
        String sql = normalized();

        assertThat(sql)
                .contains("payload_hash char(64) not null")
                .contains("investigation_hash char(64) not null")
                .contains("constraint uq_alert_event_dedup"
                        + " unique (fingerprint, payload_hash, starts_at)")
                .contains("inbox_id uuid not null references alert_inbox(id)")
                .contains("incident_id uuid not null references incident(id)")
                .contains("check (status in ('firing','resolved'))");
    }

    @Test
    void incidentIdentityExcludesSeverityAndSplitsThreeCounts() throws IOException {
        // INV-AM1-4：incident_key 不含 severity（升级不换单）——incident 表块内不得出现 severity 列
        // （只扫表块不扫全文件：设计注释允许讨论"为什么不含 severity"）
        String sql = normalized();
        int from = sql.indexOf("create table incident");
        int to = sql.indexOf("create table", from + 1);
        String incidentBlock = sql.substring(from, to < 0 ? sql.length() : to);

        assertThat(sql)
                .contains("incident_key text not null unique")
                .contains("check (status in ('firing','resolved'))")
                .contains("received_count bigint not null default 0")
                .contains("distinct_event_count bigint not null default 0")
                .contains("notification_count bigint not null default 0")
                .contains("current_rca_run_id uuid")
                .contains("episode_started_at timestamptz not null")
                .contains("pending_investigation_hash char(64)");

        assertThat(incidentBlock).doesNotContain("severity");
    }

    @Test
    void rcaRunHasPartialUniqueActiveIncident() throws IOException {
        // INV-AM1-2：唯一活跃约束在 run 层；谓词整体锚定防撞串
        String sql = normalized();
        int from = sql.indexOf("create table rca_run");
        int to = sql.indexOf("create table", from + 1);
        String runBlock = sql.substring(from, to < 0 ? sql.length() : to);

        assertThat(sql)
                .contains("check (state in ('queued','running','succeeded','failed',"
                        + "'cancelled','superseded'))")
                .contains("create unique index uq_rca_run_active_incident"
                        + " on rca_run(incident_id) where state in ('queued','running')")
                .contains("foreign key (current_rca_run_id) references rca_run(id)");
        // 材料快照锚定在 run 表块内（alert_event 有同名列，全文件 contains 无锚定力）
        assertThat(runBlock).contains("investigation_hash char(64) not null");
    }

    @Test
    void rcaTaskCarriesSlaSchedulingColumns() throws IOException {
        // §6.2：deadline_at = ready_since + sla(priority)；critical 用 infinity 永不到期
        String sql = normalized();

        assertThat(sql)
                .contains("constraint uq_rca_task_key unique (run_id, task_key)")
                .contains("ready_since timestamptz not null")
                .contains("deadline_at timestamptz not null")
                .contains("available_at timestamptz not null")
                .contains("check (state in ('ready','leased','retry_wait','done',"
                        + "'cancelled','dead'))")
                .contains("create index ix_rca_task_claim on rca_task(priority desc, deadline_at, created_at)");
    }

    @Test
    void ledgerAndAppendOnlyTablesFollowLeastPrivilege() throws IOException {
        // INV-AM1-5：alert_event/external_invocation_ledger 只增不改（账本终态列走列级 UPDATE，V5 惯例）
        String sql = normalized();

        assertThat(sql)
                .contains("grant select, insert on alert_event to control_app")
                .contains("grant select, insert on external_invocation_ledger to control_app")
                // BA-10②/G0-04：报告不可变——rca_report 与账本同组，只授 SELECT+INSERT，
                // 整行 UPDATE 授权不得存在（修订/发布走独立 publication 表，AA-16）
                .contains("grant select, insert on rca_report to control_app")
                .doesNotContainPattern("grant [a-z ,]*update on rca_report")
                .contains("grant update ( state, response_digest, http_status, latency_ms,"
                        + " prompt_tokens, completion_tokens, total_tokens, usage_missing,"
                        + " holmes_version, model, toolset_version,"
                        + " error_class, sanitized_message, finished_at"
                        + " ) on external_invocation_ledger to control_app")
                // 整行 UPDATE 授权必须不存在（列级括号不在 [a-z ,] 内，不会误匹配）
                .doesNotContainPattern("grant [a-z ,]*update on alert_event")
                .doesNotContainPattern("grant [a-z ,]*update on external_invocation_ledger")
                // 固定槽位表只租约翻转，不增删行
                .contains("grant select, update on scheduler_slot to control_app")
                .doesNotContainPattern("grant [a-z ,]*insert on scheduler_slot")
                // 账本状态机一致性（V5 同构）
                .contains("check ((state = 'started' and response_digest is null and finished_at is null)"
                        + " or (state in ('succeeded','failed','unknown') and finished_at is not null))")
                // 固定槽位预置 2 行
                .contains("insert into scheduler_slot(scope, slot_no) values ('rca', 1), ('rca', 2)");
    }

    @Test
    void publisherAppIsExplicitlyFrozenOnAllNineTables() throws IOException {
        // V2 惯例：显式 REVOKE 防未来 grant all 漂移
        String sql = normalized();

        String expectedRevoke = "revoke all on alert_inbox, alert_event, incident, rca_run,"
                + " rca_task, rca_attempt, rca_report, external_invocation_ledger, scheduler_slot"
                + " from publisher_app;";

        assertThat(sql)
                .contains(expectedRevoke)
                .contains(expectedRevoke.replace("publisher_app", "public"));
    }
}
