package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ME-T12a（D08）上下文漂移评测落库面本地静态门（沿
 * PostgresEvalCaseCollabSinkSqlContractTest 范式）：锁 V164 双表结构与 Sink SQL
 * 的 insert-only/幂等/授权三条纪律；真 PG 行为归 Testcontainers IT。
 */
class PostgresEvalCaseDriftSinkSqlContractTest {

    private static String normalized(Path p) throws IOException {
        return Files.readString(p).toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void v164TablesAreInsertOnlyWithKeyAndGrantDiscipline() throws IOException {
        String sql = normalized(Path.of(
                "src/main/resources/db/migration/V164__me12_case_drift.sql"));
        // eval_case_drift：case_result_id 外键 + uq(案例, grader 版本)——重评并存
        assertThat(sql)
                .contains("create table eval_case_drift")
                .contains("references eval_case_result (id)")
                .contains("unique (case_result_id, grader_version)")
                .contains("grant select, insert on eval_case_drift to eval_app")
                .contains("grant select on eval_case_drift to control_app")
                .doesNotContain("grant update")
                .doesNotContain("grant delete");
        // D08 列面（summary_digest 可空 + jsonb 五列含 consumption/deferred）
        assertThat(sql).contains("grader_version").contains("summary_digest")
                .contains("consumption").contains("checks").contains("metrics")
                .contains("failure_labels").contains("deferred")
                .contains("created_at");
        // rca_compaction_consumption：append 面（无 uq），写入身份 control_app、
        // eval_app 只读（与 eval_case_* 写读方向相反——主链生产侧写）
        assertThat(sql)
                .contains("create table rca_compaction_consumption")
                .contains("summary_committed").contains("consumer_invoked")
                .contains("consumed").contains("policy_digest")
                .contains("grant select, insert on rca_compaction_consumption to control_app")
                .contains("grant select on rca_compaction_consumption to eval_app");
        // 投影源只读授权（eval 身份首用 V92 摘要档；零写开口）
        assertThat(sql).contains("grant select on rca_context_summary to eval_app")
                .doesNotContain("grant insert on rca_context_summary");
    }

    @Test
    void sinkInsertIsIdempotentOnCaseAndGraderVersion() {
        String sql = PostgresEvalCaseDriftSink.SQL.toLowerCase().replaceAll("\\s+", " ");
        assertThat(sql)
                .contains("insert into eval_case_drift")
                .contains("on conflict (case_result_id, grader_version) do nothing")
                .doesNotContain("update ")
                .doesNotContain("delete ");
    }

    @Test
    void consumptionPortInsertIsAppendOnly() {
        String sql = PostgresCompactionConsumptionPort.SQL.toLowerCase()
                .replaceAll("\\s+", " ");
        assertThat(sql)
                .contains("insert into rca_compaction_consumption")
                .doesNotContain("on conflict")
                .doesNotContain("update ")
                .doesNotContain("delete ");
    }
}
