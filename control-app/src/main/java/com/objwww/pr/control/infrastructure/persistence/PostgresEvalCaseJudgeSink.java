package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.eval.domain.repository.EvalCaseJudgeSink;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;
import java.util.UUID;

/**
 * {@link EvalCaseJudgeSink} 的 Postgres 实现（P6-G7；V145 insert-only，
 * UNIQUE 冲突返回 false——与 eval_case_safety 同纪律）。
 */
public class PostgresEvalCaseJudgeSink implements EvalCaseJudgeSink {

    private final JdbcClient jdbc;

    public PostgresEvalCaseJudgeSink(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public boolean insert(UUID evalRunId, String scenarioId, int roundNo,
                          String rubricVersion, String model, String answersJson,
                          Integer passed, Integer total, String verdict, String error) {
        try {
            return jdbc.sql("""
                            insert into eval_case_judge (
                                id, eval_run_id, scenario_id, round_no,
                                rubric_version, model, answers,
                                passed, total, verdict, error
                            ) values (
                                :id, :evalRunId, :scenarioId, :roundNo,
                                :rubricVersion, :model, cast(:answers as jsonb),
                                :passed, :total, :verdict, :error
                            )
                            """)
                    .param("id", UUID.randomUUID())
                    .param("evalRunId", evalRunId)
                    .param("scenarioId", scenarioId)
                    .param("roundNo", roundNo)
                    .param("rubricVersion", rubricVersion)
                    .param("model", model)
                    .param("answers", answersJson == null ? "[]" : answersJson)
                    .param("passed", passed)
                    .param("total", total)
                    .param("verdict", verdict)
                    .param("error", error)
                    .update() > 0;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
