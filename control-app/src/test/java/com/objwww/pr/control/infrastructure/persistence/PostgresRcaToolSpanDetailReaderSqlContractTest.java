package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trace 工具 span 明细 SQL 本地静态门（范式沿 RcaClaimMigrationContractTest）：
 * Docker 不可用时也锁住关联/截断/排序三条纪律；真 PG 行为由 Testcontainers IT 覆盖。
 */
class PostgresRcaToolSpanDetailReaderSqlContractTest {

    private static String normalized() {
        return PostgresRcaToolSpanDetailReader.SQL.toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void joinsEvidenceViaResultRefWithLeftJoin() {
        String sql = normalized();
        // LEFT JOIN：result_ref 为 null（VALIDATE_ONLY/失败调用）的行如实保留，三要素 null
        assertThat(sql)
                .contains("from rca_tool_invocation ti")
                .contains("left join rca_evidence e on e.id = ti.result_ref");
    }

    @Test
    void summariesAreTruncatedInSqlNotInApp() {
        String sql = normalized();
        // 摘要语义在 SQL 内截断（scope 300 / payload 500）——禁止整段 payload 下发前端
        assertThat(sql)
                .contains("left(e.scope, 300) as scope_summary")
                .contains("left(e.payload, 500) as result_summary")
                .doesNotContain("e.payload as result_summary")
                .doesNotContain("select e.payload");
    }

    @Test
    void deterministicOrderingByStartedAtThenCallSeq() {
        assertThat(normalized())
                .contains("order by ti.started_at asc, ti.call_seq asc");
    }

    @Test
    void ba190ReasonDetailProjectedFromLedgerColumn() {
        // BA-190（W3）：拒因具体消息列（V155 reason_detail）随明细透出——账本直读，不造数
        assertThat(normalized())
                .contains("ti.reason_detail as reason_detail");
    }
}
