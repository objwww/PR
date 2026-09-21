package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ME-T04（D04）行为评测落库面本地静态门（沿 PostgresEvalCaseSixPartsSinkSqlContractTest
 * 范式）：锁 V161 表结构与 Sink SQL 的 insert-only/幂等/授权三条纪律；
 * 真 PG 行为归 Testcontainers IT。
 */
class PostgresEvalCaseBehaviorSinkSqlContractTest {

    private static String normalized(Path p) throws IOException {
        return Files.readString(p).toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void v161TableIsInsertOnlyWithCaseAndGraderVersionKey() throws IOException {
        String sql = normalized(Path.of(
                "src/main/resources/db/migration/V161__me04_case_behavior.sql"));
        // case_result_id 外键 + uq(案例, grader 版本)：重评并存，历史不被覆盖
        assertThat(sql)
                .contains("create table eval_case_behavior")
                .contains("references eval_case_result (id)")
                .contains("unique (case_result_id, grader_version)")
                .contains("grant select, insert on eval_case_behavior to eval_app")
                .contains("grant select on eval_case_behavior to control_app")
                .doesNotContain("grant update")
                .doesNotContain("grant delete");
        // D04 第 6 条列面
        assertThat(sql).contains("grader_version").contains("trace_digest")
                .contains("coverage").contains("checks").contains("metrics")
                .contains("failure_labels").contains("evidence_refs")
                .contains("created_at");
    }

    @Test
    void sinkInsertIsIdempotentOnCaseAndGraderVersion() {
        String sql = PostgresEvalCaseBehaviorSink.SQL.toLowerCase().replaceAll("\\s+", " ");
        assertThat(sql)
                .contains("insert into eval_case_behavior")
                .contains("on conflict (case_result_id, grader_version) do nothing")
                .doesNotContain("update ")
                .doesNotContain("delete ");
    }
}
