package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.eval.domain.repository.EvalCaseSixPartsSink;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;
import java.util.UUID;

/**
 * {@link EvalCaseSixPartsSink} 的 Postgres 实现（V152；insert-only，同键幂等 DO NOTHING）。
 */
public class PostgresEvalCaseSixPartsSink implements EvalCaseSixPartsSink {

    /** 包内可见供 SQL 契约测试锁定形状（insert-only/幂等纪律） */
    static final String SQL = """
            insert into eval_case_six_parts (
                id, eval_run_id, scenario_id, round_no,
                what_happened, root_cause, evidence_basis, impact,
                confidence, recommendation, confidence_level, complete)
            values (:id, :evalRunId, :scenarioId, :roundNo,
                :what, :root, :basis, :impact,
                :confidence, :recommendation, :level, :complete)
            on conflict (eval_run_id, scenario_id, round_no) do nothing
            """;

    private final JdbcClient jdbc;

    public PostgresEvalCaseSixPartsSink(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void insert(UUID evalRunId, String scenarioId, int roundNo,
                       boolean whatHappened, boolean rootCause, boolean evidenceBasis,
                       boolean impact, boolean confidence, boolean recommendation,
                       String confidenceLevel, boolean complete) {
        jdbc.sql(SQL)
                .param("id", UUID.randomUUID())
                .param("evalRunId", evalRunId)
                .param("scenarioId", scenarioId)
                .param("roundNo", roundNo)
                .param("what", whatHappened)
                .param("root", rootCause)
                .param("basis", evidenceBasis)
                .param("impact", impact)
                .param("confidence", confidence)
                .param("recommendation", recommendation)
                .param("level", confidenceLevel)
                .param("complete", complete)
                .update();
    }
}
