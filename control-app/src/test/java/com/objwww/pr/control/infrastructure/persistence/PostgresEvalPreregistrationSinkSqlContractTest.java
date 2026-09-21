package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ME-T12b（D09）评测预登记落库面本地静态门（沿
 * PostgresEvalCaseDriftSinkSqlContractTest 范式）：锁 V165 表结构与 Sink SQL 的
 * insert-only/幂等/授权三条纪律；真 PG 行为归 Testcontainers IT。
 */
class PostgresEvalPreregistrationSinkSqlContractTest {

    private static String normalized(Path p) throws IOException {
        return Files.readString(p).toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void v165TableIsInsertOnlyWithKeyAndGrantDiscipline() throws IOException {
        String sql = normalized(Path.of(
                "src/main/resources/db/migration/V165__me12_eval_preregistration.sql"));
        // eval_preregistration：eval_run 外键 + uq(run)——一 run 一登记永不改写
        assertThat(sql)
                .contains("create table eval_preregistration")
                .contains("references eval_run (id)")
                .contains("unique (eval_run_id)")
                .contains("grant select, insert on eval_preregistration to eval_app")
                .contains("grant select on eval_preregistration to control_app")
                .doesNotContain("grant update")
                .doesNotContain("grant delete");
        // D09 列面（簇数锚 + 登记自证摘要 + 登记时刻）
        assertThat(sql).contains("min_clusters").contains("prereg_digest")
                .contains("registered_at");
    }

    @Test
    void sinkInsertIsIdempotentOnEvalRun() {
        String sql = PostgresEvalPreregistrationSink.SQL.toLowerCase()
                .replaceAll("\\s+", " ");
        assertThat(sql)
                .contains("insert into eval_preregistration")
                .contains("on conflict (eval_run_id) do nothing")
                .doesNotContain("update ")
                .doesNotContain("delete ");
    }
}
