package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ME-T12a（D07）协作评测落库面本地静态门（沿 PostgresEvalCaseLoopSinkSqlContractTest
 * 范式）：锁 V163 表结构与 Sink SQL 的 insert-only/幂等/授权三条纪律；
 * 真 PG 行为归 Testcontainers IT。
 */
class PostgresEvalCaseCollabSinkSqlContractTest {

    private static String normalized(Path p) throws IOException {
        return Files.readString(p).toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void v163TableIsInsertOnlyWithCaseAndGraderVersionKey() throws IOException {
        String sql = normalized(Path.of(
                "src/main/resources/db/migration/V163__me12_case_collab.sql"));
        // case_result_id 外键 + uq(案例, grader 版本)：重评并存，历史不被覆盖
        assertThat(sql)
                .contains("create table eval_case_collab")
                .contains("references eval_case_result (id)")
                .contains("unique (case_result_id, grader_version)")
                .contains("grant select, insert on eval_case_collab to eval_app")
                .contains("grant select on eval_case_collab to control_app")
                .doesNotContain("grant update")
                .doesNotContain("grant delete");
        // D07 列面（观测标量可空 + jsonb 五列含归因双轨）
        assertThat(sql).contains("grader_version").contains("edge_count")
                .contains("admitted_count").contains("token_cost_total")
                .contains("checks").contains("metrics").contains("failure_labels")
                .contains("suspected_attributions").contains("supported_attributions")
                .contains("created_at");
        // 投影源只读授权（eval 身份；零写开口）
        assertThat(sql)
                .contains("grant select on rca_delegation_decision to eval_app")
                .contains("grant select on rca_delegation_receipt to eval_app")
                .contains("grant select on rca_task to eval_app");
    }

    @Test
    void sinkInsertIsIdempotentOnCaseAndGraderVersion() {
        String sql = PostgresEvalCaseCollabSink.SQL.toLowerCase().replaceAll("\\s+", " ");
        assertThat(sql)
                .contains("insert into eval_case_collab")
                .contains("on conflict (case_result_id, grader_version) do nothing")
                .doesNotContain("update ")
                .doesNotContain("delete ");
    }
}
