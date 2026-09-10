package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.ops.domain.repository.AgentOpsReader;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * {@link AgentOpsReader} 的 Postgres 实现（UI-6 大盘聚合；JdbcClient 手写 SQL，
 * 单语句标量聚合 + Top5 分组查询——沿 PostgresIncidentQueryReader.overview 惯例）。
 *
 * <p>tokens24h = sum(total_tokens)：sum 返回 numeric——PgResultSet.getObject(Long)
 * 对 numeric 直拒（UI-1 195 真 PG 实证同律），先取 BigDecimal 再转；无行 → null
 * （诚实 null，不回填 0）。
 */
public class PostgresAgentOpsReader implements AgentOpsReader {

    private static final Duration WINDOW_24H = Duration.ofHours(24);

    private final JdbcClient jdbc;

    public PostgresAgentOpsReader(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public AgentOpsAggregate summary(Instant now) {
        Timestamp at = Timestamp.from(now);
        Timestamp since = Timestamp.from(now.minus(WINDOW_24H));

        record Scalars(long activeRuns, long awaitingReviewRuns, long readyTasks,
                       Long oldestReadyWait, long openCases, long outboxPending,
                       long outboxFailed24h, long llmCalls24h, Long tokens24h) {
        }
        Scalars scalars = jdbc.sql("""
                select
                  (select count(*) from rca_run
                     where state in ('QUEUED','RUNNING','REPORTING')) as active_runs,
                  (select count(*) from rca_run
                     where state in ('SUCCEEDED','PARTIAL')) as awaiting_review_runs,
                  (select count(*) from rca_task where state = 'READY') as ready_tasks,
                  (select (extract(epoch from (:now - min(ready_since))))::bigint
                     from rca_task where state = 'READY') as oldest_ready_wait,
                  (select count(*) from operator_case
                     where status in ('OPEN','ACKED')) as open_cases,
                  (select count(*) from notify_outbox
                     where state in ('PENDING','CLAIMED','RETRY_WAIT')) as outbox_pending,
                  (select count(*) from notify_outbox
                     where state = 'DEAD' and updated_at >= :since) as outbox_failed_24h,
                  (select count(*) from external_invocation_ledger
                     where started_at >= :since) as llm_calls_24h,
                  (select sum(total_tokens) from external_invocation_ledger
                     where started_at >= :since) as tokens_24h
                """)
                .param("now", at)
                .param("since", since)
                .query((rs, i) -> {
                    java.math.BigDecimal tokens = rs.getBigDecimal("tokens_24h");
                    return new Scalars(rs.getLong("active_runs"),
                            rs.getLong("awaiting_review_runs"),
                            rs.getLong("ready_tasks"),
                            rs.getObject("oldest_ready_wait", Long.class),
                            rs.getLong("open_cases"),
                            rs.getLong("outbox_pending"),
                            rs.getLong("outbox_failed_24h"),
                            rs.getLong("llm_calls_24h"),
                            tokens == null ? null : tokens.longValue());
                })
                .single();

        // 账本无工具名列（V7 列面）：按 model 分组 Top5，契约键名仍叫 tool
        List<ToolCallCount> topTools = jdbc.sql("""
                select model as tool, count(*) as calls
                from external_invocation_ledger
                where started_at >= :since and model is not null
                group by model
                order by calls desc, model asc
                limit 5
                """)
                .param("since", since)
                .query((rs, i) -> new ToolCallCount(rs.getString("tool"), rs.getLong("calls")))
                .list();

        return new AgentOpsAggregate(scalars.activeRuns(), scalars.awaitingReviewRuns(),
                scalars.readyTasks(), scalars.oldestReadyWait(), scalars.openCases(),
                scalars.outboxPending(), scalars.outboxFailed24h(), scalars.llmCalls24h(),
                scalars.tokens24h(), topTools);
    }
}
