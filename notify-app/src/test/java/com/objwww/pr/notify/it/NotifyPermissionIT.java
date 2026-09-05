package com.objwww.pr.notify.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 授权矩阵（真 PG，notify_app 真实角色；BA-10②/E2E-M3-07 落地面）：
 * notify_app 只碰投递面——不改报告正文/调查记录、不产 outbox、不改 operation_id
 * 与 payload（列级 UPDATE 白名单外全部拒绝）。
 */
class NotifyPermissionIT extends NotifyPostgresITBase {

    /** 断言辅助：执行应抛异常，且整条 cause 链文本包含预期片段 */
    private boolean chainContains(Runnable action, String fragment) {
        try {
            action.run();
        } catch (RuntimeException e) {
            StringBuilder chain = new StringBuilder();
            for (Throwable c = e; c != null; c = c.getCause()) {
                chain.append(c.getMessage()).append('\n');
            }
            return chain.toString().contains(fragment);
        }
        return false;
    }

    @Test
    @DisplayName("notify_app 不得改报告正文（rca_report 零 UPDATE/DELETE 授权）")
    void cannotMutateReportBody() {
        ReportSeed seed = seedValidatedReport();

        assertThat(chainContains(() -> notifyJdbc.sql(
                        "UPDATE rca_report SET package_json = package_json WHERE false").update(),
                "permission denied")).isTrue();
        assertThat(chainContains(() -> notifyJdbc.sql(
                        "DELETE FROM rca_report WHERE false").update(),
                "permission denied")).isTrue();
        assertThat(chainContains(() -> notifyJdbc.sql(
                        "UPDATE rca_investigation_result SET execution_status = execution_status "
                                + "WHERE false").update(),
                "permission denied")).isTrue();
    }

    @Test
    @DisplayName("notify_app 不产 outbox：INSERT notify_outbox / report_publication 拒绝（生产方=control_app）")
    void cannotProduceOutboxRows() {
        ReportSeed seed = seedValidatedReport();

        assertThat(chainContains(() -> notifyJdbc.sql("""
                        INSERT INTO notify_outbox(id, publication_id, report_id, channel,
                            template_version, operation_id, payload_json, state, created_at, updated_at)
                        VALUES (:id, :id, :report, 'ch', 'v1', :op, '{}'::jsonb, 'PENDING', now(), now())
                        """).param("id", UUID.randomUUID()).param("report", seed.reportId())
                        .param("op", UUID.randomUUID()).update(),
                "permission denied")).isTrue();
        assertThat(chainContains(() -> notifyJdbc.sql("""
                        INSERT INTO report_publication(id, report_id, state, created_at, updated_at)
                        VALUES (:id, :report, 'READY', now(), now())
                        """).param("id", UUID.randomUUID()).param("report", seed.reportId()).update(),
                "permission denied")).isTrue();
    }

    @Test
    @DisplayName("列级白名单：notify_app 可推进投递面列；payload_json/operation_id/report_id 不可写")
    void columnLevelUpdateWhitelist() {
        ReportSeed seed = seedValidatedReport();
        UUID pub = seedPublicationWithOutbox(seed, "dingtalk-test", UUID.randomUUID());
        UUID row = adminJdbc.sql("SELECT id FROM notify_outbox WHERE publication_id = :p")
                .param("p", pub).query(UUID.class).single();

        // 投递面列放行（V9 授权列）
        notifyJdbc.sql("""
                UPDATE notify_outbox SET state = 'CLAIMED', lease_owner = 'it',
                    lease_until = now() + interval '60 seconds',
                    lease_epoch = lease_epoch + 1, attempt_count = attempt_count + 1,
                    available_at = now(), last_error = '{}'::jsonb, updated_at = now()
                WHERE id = :id
                """).param("id", row).update();
        String state = adminJdbc.sql("SELECT state FROM notify_outbox WHERE id = :id")
                .param("id", row).query(String.class).single();
        assertThat(state).isEqualTo("CLAIMED");

        // 白名单外列拒绝（payload/身份列不可写；sent_at 在 V9 授权列内，markSent 用）
        assertThat(chainContains(() -> notifyJdbc.sql(
                        "UPDATE notify_outbox SET payload_json = '{}'::jsonb WHERE false").update(),
                "permission denied")).isTrue();
        assertThat(chainContains(() -> notifyJdbc.sql(
                        "UPDATE notify_outbox SET operation_id = :op WHERE false")
                        .param("op", UUID.randomUUID()).update(),
                "permission denied")).isTrue();
        assertThat(chainContains(() -> notifyJdbc.sql(
                        "UPDATE notify_outbox SET report_id = :r WHERE false")
                        .param("r", UUID.randomUUID()).update(),
                "permission denied")).isTrue();
        assertThat(chainContains(() -> notifyJdbc.sql(
                        "UPDATE notify_outbox SET template_version = 'v2' WHERE false").update(),
                "permission denied")).isTrue();
    }
}
