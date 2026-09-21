package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.eval.domain.repository.EvalPreregistrationSink;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link EvalPreregistrationSink} 的 Postgres 实现（ME-T12b/D09，V165）。
 * insert-only + on conflict (eval_run_id) do nothing：一 run 一登记幂等，
 * 登记永不改写（沿 eval_case_* sink 族惯例）。写身份 = eval_app（批起始链）。
 */
public class PostgresEvalPreregistrationSink implements EvalPreregistrationSink {

    /** 包内可见供 SQL 契约测试锁定形状（insert-only/幂等纪律） */
    static final String SQL = """
            insert into eval_preregistration (
                id, eval_run_id, min_clusters, prereg_digest, registered_at)
            values (:id, :evalRunId, :minClusters, :preregDigest, :registeredAt)
            on conflict (eval_run_id) do nothing
            """;

    private final JdbcClient jdbc;

    public PostgresEvalPreregistrationSink(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void insert(UUID evalRunId, int minClusters, String preregDigest,
                       Instant registeredAt) {
        jdbc.sql(SQL)
                .param("id", UUID.randomUUID())
                .param("evalRunId", evalRunId)
                .param("minClusters", minClusters)
                .param("preregDigest", preregDigest)
                .param("registeredAt", java.sql.Timestamp.from(registeredAt))
                .update();
    }
}
