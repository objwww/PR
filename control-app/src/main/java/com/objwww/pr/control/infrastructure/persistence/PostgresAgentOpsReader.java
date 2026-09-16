package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.ops.domain.repository.AgentOpsReader;
import com.objwww.pr.control.ops.domain.repository.AgentOpsReader.WorkerActivity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
                  (select count(*) from rca_model_call
                     where created_at >= :since) as llm_calls_24h,
                  (select sum(nullif(usage->>'total_tokens', '')::bigint)
                     from rca_model_call where created_at >= :since) as tokens_24h
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

        // 真实工具名（前端产品化波次1 切源）：rca_tool_invocation 按工具分组 Top5
        List<ToolCallCount> topTools = jdbc.sql("""
                select tool_name as tool, count(*) as calls
                from rca_tool_invocation
                where started_at >= :since and tool_name is not null
                group by tool_name
                order by calls desc, tool_name asc
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

    /**
     * 执行器活性投影（监控页「执行器」区，方案 §三.12）：按租约活动推导，非心跳注册表。
     * 四源 UNION 分组：
     * <ul>
     *   <li>rca：rca_attempt 账本（started/finished 落窗）+ rca_task 在飞租约
     *       （LEASED 且 lease_until 未过期——过期租约=失联，不计在飞也不计活动）；</li>
     *   <li>eval：eval_run_command 领取列（worker_id + claimed_at/finished_at；
     *       worker 写面无 updated_at，活动时刻 = coalesce(finished, claimed)，
     *       CLAIMED 未完结计在飞）；</li>
     *   <li>drill：drill_job 领取列（相位推进刷 updated_at；活动集中六态计在飞）。</li>
     * </ul>
     * 全部表 control_app 既有 SELECT 授权（V7/V81/V86），零写面。
     */
    @Override
    public List<WorkerActivity> workerActivity(Instant now, Duration window) {
        Timestamp at = Timestamp.from(now);
        Timestamp since = Timestamp.from(now.minus(window));
        return jdbc.sql("""
                select source, worker_id,
                       max(last_activity_at) as last_activity_at,
                       sum(in_flight) as in_flight_tasks
                from (
                  select 'rca' as source, a.worker_id as worker_id,
                         greatest(a.started_at, coalesce(a.finished_at, a.started_at))
                           as last_activity_at,
                         0 as in_flight
                  from rca_attempt a
                  where greatest(a.started_at, coalesce(a.finished_at, a.started_at))
                        >= :since
                  union all
                  select 'rca', t.lease_owner, t.updated_at, 1
                  from rca_task t
                  where t.state = 'LEASED' and t.lease_owner is not null
                    and t.lease_until > :now
                  union all
                  select 'eval', c.worker_id, coalesce(c.finished_at, c.claimed_at),
                         case when c.state = 'CLAIMED' then 1 else 0 end
                  from eval_run_command c
                  where c.worker_id is not null
                    and coalesce(c.finished_at, c.claimed_at) >= :since
                  union all
                  select 'drill', j.worker_id, j.updated_at,
                         case when j.state in ('PRECHECK','INJECTING','OBSERVING',
                               'RECOVERING','VERIFYING','RECOVERY_FAILED')
                              then 1 else 0 end
                  from drill_job j
                  where j.worker_id is not null and j.updated_at >= :since
                ) s
                group by source, worker_id
                order by last_activity_at desc, source, worker_id
                """)
                .param("now", at)
                .param("since", since)
                .query((rs, i) -> new WorkerActivity(rs.getString("source"),
                        rs.getString("worker_id"),
                        rs.getTimestamp("last_activity_at").toInstant(),
                        rs.getLong("in_flight_tasks")))
                .list();
    }

    /**
     * OP-03：动作分析行聚合（computed_at >= since 窗）——SQL 侧计数，
     * 未被分析的 Run 不在表内即不进任何分子分母（NOT_ASSESSED ≠ 0 价值）。
     */
    @Override
    public AgentOpsReader.ActionAssessmentStats actionAssessmentStats(Instant since) {
        Long logical = jdbc.sql("""
                        select count(*) from rca_action_assessment
                        where computed_at >= :since
                        """)
                .param("since", Timestamp.from(since))
                .query(Long.class)
                .single();
        Map<String, Long> byClass = new java.util.HashMap<>();
        jdbc.sql("""
                        select classification, count(*) as n from rca_action_assessment
                        where computed_at >= :since group by classification
                        """)
                .param("since", Timestamp.from(since))
                .query((rs, i) -> Map.entry(rs.getString("classification"), rs.getLong("n")))
                .list()
                .forEach(e -> byClass.put(e.getKey(), e.getValue()));
        Long runs = jdbc.sql("""
                        select count(distinct run_id) from rca_action_assessment
                        where computed_at >= :since
                        """)
                .param("since", Timestamp.from(since))
                .query(Long.class)
                .single();
        return new AgentOpsReader.ActionAssessmentStats(
                logical == null ? 0 : logical,
                byClass.getOrDefault("DUPLICATE_SAME_SNAPSHOT", 0L),
                byClass.getOrDefault("NEW_OBSERVATION", 0L),
                byClass.getOrDefault("CONFIRMS_OR_REFUTES", 0L),
                byClass.getOrDefault("NO_DATA", 0L),
                byClass.getOrDefault("SOURCE_FAILED", 0L),
                byClass.getOrDefault("UNDETERMINED", 0L),
                runs == null ? 0 : runs);
    }

    @Override
    public LatencyLayers latencyLayers(Instant since) {
        Timestamp from = Timestamp.from(since);
        record Row(long runs, Long runP50, Long runP95, long llmCalls,
                   Long llmP50, Long llmP95, long toolCalls, Long toolP50, Long toolP95) {
        }
        Row row = jdbc.sql("""
                select
                  (select count(*) from rca_run
                     where finished_at is not null and created_at >= :since) as runs,
                  (select percentile_cont(0.5) within group (
                       order by extract(epoch from (finished_at - created_at)) * 1000)
                     from rca_run
                     where finished_at is not null and created_at >= :since) as run_p50,
                  (select percentile_cont(0.95) within group (
                       order by extract(epoch from (finished_at - created_at)) * 1000)
                     from rca_run
                     where finished_at is not null and created_at >= :since) as run_p95,
                  (select count(*) from rca_model_call
                     where created_at >= :since and latency_ms is not null) as llm_calls,
                  (select percentile_cont(0.5) within group (order by latency_ms)
                     from rca_model_call
                     where created_at >= :since and latency_ms is not null) as llm_p50,
                  (select percentile_cont(0.95) within group (order by latency_ms)
                     from rca_model_call
                     where created_at >= :since and latency_ms is not null) as llm_p95,
                  (select count(*) from rca_tool_invocation
                     where started_at >= :since and settled_at is not null) as tool_calls,
                  (select percentile_cont(0.5) within group (
                       order by extract(epoch from (settled_at - started_at)) * 1000)
                     from rca_tool_invocation
                     where started_at >= :since and settled_at is not null) as tool_p50,
                  (select percentile_cont(0.95) within group (
                       order by extract(epoch from (settled_at - started_at)) * 1000)
                     from rca_tool_invocation
                     where started_at >= :since and settled_at is not null) as tool_p95
                """)
                .param("since", from)
                .query((rs, i) -> {
                    // percentile_cont 返回 numeric——getObject(Long) 直拒（本类 javadoc 同律），走 BigDecimal
                    java.math.BigDecimal runP50 = rs.getBigDecimal("run_p50");
                    java.math.BigDecimal runP95 = rs.getBigDecimal("run_p95");
                    java.math.BigDecimal llmP50 = rs.getBigDecimal("llm_p50");
                    java.math.BigDecimal llmP95 = rs.getBigDecimal("llm_p95");
                    java.math.BigDecimal toolP50 = rs.getBigDecimal("tool_p50");
                    java.math.BigDecimal toolP95 = rs.getBigDecimal("tool_p95");
                    return new Row(rs.getLong("runs"),
                            runP50 == null ? null : runP50.longValue(),
                            runP95 == null ? null : runP95.longValue(),
                            rs.getLong("llm_calls"),
                            llmP50 == null ? null : llmP50.longValue(),
                            llmP95 == null ? null : llmP95.longValue(),
                            rs.getLong("tool_calls"),
                            toolP50 == null ? null : toolP50.longValue(),
                            toolP95 == null ? null : toolP95.longValue());
                })
                .single();
        return new LatencyLayers(row.runs(), row.runP50(), row.runP95(),
                row.llmCalls(), row.llmP50(), row.llmP95(),
                row.toolCalls(), row.toolP50(), row.toolP95());
    }
}
