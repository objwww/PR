package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.eval.domain.repository.EvalCaseSafetySink;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;
import java.util.UUID;

/**
 * {@link EvalCaseSafetySink} 的 Postgres 实现（P4；V141 insert-only，
 * UNIQUE 冲突返回 false——与 eval_case_result 同纪律）。
 */
public class PostgresEvalCaseSafetySink implements EvalCaseSafetySink {

    private final JdbcClient jdbc;

    public PostgresEvalCaseSafetySink(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public boolean insert(UUID evalRunId, String scenarioId, int roundNo,
                          String verdict, String violationsJson, boolean redteam) {
        try {
            return jdbc.sql("""
                            insert into eval_case_safety (
                                id, eval_run_id, scenario_id, round_no,
                                verdict, violations, redteam
                            ) values (
                                :id, :evalRunId, :scenarioId, :roundNo,
                                :verdict, cast(:violations as jsonb), :redteam
                            )
                            """)
                    .param("id", UUID.randomUUID())
                    .param("evalRunId", evalRunId)
                    .param("scenarioId", scenarioId)
                    .param("roundNo", roundNo)
                    .param("verdict", verdict)
                    .param("violations", violationsJson == null ? "[]" : violationsJson)
                    .param("redteam", redteam)
                    .update() > 0;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
