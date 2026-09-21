package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ME-T12（D05）死循环评测落库面本地静态门（沿 PostgresEvalCaseBehaviorSinkSqlContractTest
 * 范式）：锁 V162 表结构与 Sink SQL 的 insert-only/幂等/授权三条纪律；
 * 真 PG 行为归 Testcontainers IT。
 */
class PostgresEvalCaseLoopSinkSqlContractTest {

    private static String normalized(Path p) throws IOException {
        return Files.readString(p).toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void v162TableIsInsertOnlyWithCaseAndGraderVersionKey() throws IOException {
        String sql = normalized(Path.of(
                "src/main/resources/db/migration/V162__me12_case_loop.sql"));
        // case_result_id 外键 + uq(案例, grader 版本)：重评并存，历史不被覆盖
        assertThat(sql)
                .contains("create table eval_case_loop")
                .contains("references eval_case_result (id)")
                .contains("unique (case_result_id, grader_version)")
                .contains("grant select, insert on eval_case_loop to eval_app")
                .contains("grant select on eval_case_loop to control_app")
                .doesNotContain("grant update")
                .doesNotContain("grant delete");
        // D05 列面（观测标量可空 + jsonb 三列）
        assertThat(sql).contains("grader_version").contains("stop_reason")
                .contains("detection_event_index")
                .contains("first_no_progress_event_index")
                .contains("post_stop_new_actions")
                .contains("physical_calls_from_onset").contains("tokens_from_onset")
                .contains("seconds_from_onset")
                .contains("checks").contains("metrics").contains("failure_labels")
                .contains("created_at");
    }

    @Test
    void sinkInsertIsIdempotentOnCaseAndGraderVersion() {
        String sql = PostgresEvalCaseLoopSink.SQL.toLowerCase().replaceAll("\\s+", " ");
        assertThat(sql)
                .contains("insert into eval_case_loop")
                .contains("on conflict (case_result_id, grader_version) do nothing")
                .doesNotContain("update ")
                .doesNotContain("delete ");
    }
}
