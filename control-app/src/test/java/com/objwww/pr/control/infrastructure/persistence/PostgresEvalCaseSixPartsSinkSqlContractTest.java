package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-d T5 六要素落库面本地静态门（范式沿 RcaClaimMigrationContractTest）：锁 V152
 * 表结构与 Sink SQL 的 insert-only/幂等/档位约束三条纪律；真 PG 行为归 Testcontainers IT。
 */
class PostgresEvalCaseSixPartsSinkSqlContractTest {

    private static String normalized(Path p) throws IOException {
        return Files.readString(p).toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void v152TableIsInsertOnlyWithLevelPresenceConstraint() throws IOException {
        String sql = normalized(Path.of(
                "src/main/resources/db/migration/V152__md_case_six_parts.sql"));
        // insert-only 面：仅授 select,insert（沿 V141 先例），unique 键同安全面
        assertThat(sql)
                .contains("create table eval_case_six_parts")
                .contains("unique (eval_run_id, scenario_id, round_no)")
                .contains("grant select, insert on eval_case_six_parts to eval_app")
                .doesNotContain("grant update")
                .doesNotContain("grant delete");
        // 把握布尔与档位同生共死
        assertThat(sql).contains("check (confidence = (confidence_level is not null))")
                .contains("confidence_level in ('high', 'medium', 'low')");
    }

    @Test
    void sinkInsertIsIdempotentOnBusinessKey() {
        String sql = PostgresEvalCaseSixPartsSink.SQL.toLowerCase().replaceAll("\\s+", " ");
        assertThat(sql)
                .contains("insert into eval_case_six_parts")
                .contains("on conflict (eval_run_id, scenario_id, round_no) do nothing")
                .doesNotContain("update ")
                .doesNotContain("delete ");
    }
}
