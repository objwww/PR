package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.identity.InvestigationInputs;
import com.objwww.pr.control.alert.domain.repository.RcaModelCallUsageReader;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaModelCallLedger;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaModelCallUsageReader;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRunConfigEpochRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §三.5/RV08 费用透出读面的真 PG 验收（本机无 docker 自动跳过）：
 * rca_model_call（V48）run 级聚合口径——
 * <ul>
 *   <li>无行 → Optional.empty（前端显「无模型调用」而非全 0）；</li>
 *   <li>SUCCESS 已定价行贡献 tokens/cost；SUCCESS+usageMissing 贡献 tokens 不贡献
 *       cost 且入 usageMissing；UNKNOWN/FAILED 计 callCount 且入 usageMissing
 *       （费用为下限口径，V48 头注"usage 缺失不猜零"）；</li>
 *   <li>currency/pricingVersion 已定价行唯一时透出。</li>
 * </ul>
 * 语义级口径钉在 L0：RunQueryServiceTest；本类钉 SQL 聚合在真 PG 的事实。
 */
class PostgresRcaModelCallUsageReaderIT extends PostgresITBase {

    private PostgresRcaRunRepository runs;
    private PostgresIncidentRepository incidents;
    private PostgresRcaModelCallLedger ledger;
    private PostgresRcaModelCallUsageReader reader;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(controlDataSource());
        runs = new PostgresRcaRunRepository(jdbc, new PostgresRunConfigEpochRepository(jdbc));
        incidents = new PostgresIncidentRepository(jdbc);
        ledger = new PostgresRcaModelCallLedger(jdbc, new ObjectMapper(), controlTx);
        reader = new PostgresRcaModelCallUsageReader(jdbc);
    }

    @Test
    void emptyWhenNoModelCalls() {
        UUID runId = mintRoutedRun("usage-empty").id();
        assertThat(reader.summarizeByRun(runId)).isEmpty();
    }

    @Test
    void aggregatesPricedMissingAndUnknownCalls() {
        UUID runId = mintRoutedRun("usage-agg").id();
        UUID[] ta = mintTaskAttempt(runId);

        // ① SUCCESS 已定价：100 入 / 50 出，1500 micros CNY
        UUID priced = UUID.randomUUID();
        ledger.open(call(priced, runId, ta, 0L));
        assertThat(ledger.succeed(priced, new RcaModelCallLedger.UsageOutcome(
                100, 50, 150, false, 1500L, "pv-it", "CNY",
                "req-1", "route-a", "demo-model", 42, null))).isTrue();

        // ② SUCCESS 但 usage 未回报：tokens 不猜零（不入合计），cost 未决入 usageMissing
        UUID missing = UUID.randomUUID();
        ledger.open(call(missing, runId, ta, 1L));
        assertThat(ledger.succeed(missing, new RcaModelCallLedger.UsageOutcome(
                0, 0, 0, true, null, null, null,
                null, "route-a", "demo-model", 30, null))).isTrue();

        // ③ UNKNOWN：是否已执行不确定，计 callCount 且费用未决
        UUID unknown = UUID.randomUUID();
        ledger.open(call(unknown, runId, ta, 2L));
        assertThat(ledger.markUnknown(unknown)).isTrue();

        // ④ FAILED：请求失败无 usage 账面，同入费用未决口径
        UUID failed = UUID.randomUUID();
        ledger.open(call(failed, runId, ta, 3L));
        assertThat(ledger.fail(failed, "UPSTREAM_5XX")).isTrue();

        RcaModelCallUsageReader.RunUsage u = reader.summarizeByRun(runId).orElseThrow();
        assertThat(u.callCount()).isEqualTo(4);
        assertThat(u.tokensIn()).as("仅已回报行贡献").isEqualTo(100);
        assertThat(u.tokensOut()).isEqualTo(50);
        assertThat(u.costMicros()).isEqualTo(1500L);
        assertThat(u.usageMissing()).as("usage 缺失 SUCCESS + UNKNOWN + FAILED").isEqualTo(3);
        assertThat(u.currency()).isEqualTo("CNY");
        assertThat(u.pricingVersion()).isEqualTo("pv-it");
    }

    @Test
    void settledUsageExportRoundTripsFromJsonbUsage() {
        // R6 真窗回归（195 R4 探针 2026-09-12 首证）：listSettledUsageByRunId 曾按
        // 裸列名读 tokens——V48 的 usage 是 jsonb，真 PG 直接 BadSqlGrammar 且
        // 打死每一条 native run 的收尾。本例钉 jsonb 键读取 + FAILED 行 usage 恒空。
        UUID runId = mintRoutedRun("usage-export").id();
        UUID[] ta = mintTaskAttempt(runId);

        UUID priced = UUID.randomUUID();
        ledger.open(call(priced, runId, ta, 0L));
        assertThat(ledger.succeed(priced, new RcaModelCallLedger.UsageOutcome(
                100, 50, 150, false, 1500L, "pv-it", "CNY",
                "req-1", "route-a", "demo-model", 42, null))).isTrue();

        UUID failed = UUID.randomUUID();
        ledger.open(call(failed, runId, ta, 1L));
        assertThat(ledger.fail(failed, "QUOTA_EXHAUSTED")).isTrue();

        var rows = ledger.listSettledUsageByRunId(runId);
        assertThat(rows).hasSize(2);

        var pricedRow = rows.stream().filter(r -> r.actionSeq() == 0L)
                .findFirst().orElseThrow();
        assertThat(pricedRow.promptTokens()).isEqualTo(100);
        assertThat(pricedRow.completionTokens()).isEqualTo(50);
        assertThat(pricedRow.totalTokens()).isEqualTo(150);
        assertThat(pricedRow.costMicros()).isEqualTo(1500L);
        assertThat(pricedRow.pricingVersion()).isEqualTo("pv-it");
        assertThat(pricedRow.currency()).isEqualTo("CNY");
        assertThat(pricedRow.usageMissing()).isFalse();
        assertThat(pricedRow.state()).isEqualTo("SUCCESS");

        var failedRow = rows.stream().filter(r -> r.actionSeq() == 1L)
                .findFirst().orElseThrow();
        assertThat(failedRow.promptTokens()).as("FAILED 行 usage 恒空").isNull();
        assertThat(failedRow.completionTokens()).isNull();
        assertThat(failedRow.costMicros()).isNull();
        assertThat(failedRow.usageMissing()).as("usage_missing 是落库列，fail 不改写").isFalse();
        assertThat(failedRow.state()).isEqualTo("FAILED");
    }

    // ------------------------------------------------------------------ 辅助（En04ConfigEpochIT 同形）

    private RcaRun mintRoutedRun(String tag) {
        Incident incident = insertIncident(tag);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        RcaRun run = new RcaRun(id, incident.id(), 0, RunTrigger.INITIAL,
                RcaRunState.QUEUED, Digest.sha256Of("run-" + tag),
                now.minus(Duration.ofMinutes(4)), now, null, null, null);
        runs.insertRouted(run, new RcaRunRouting(RcaEngine.NATIVE,
                Digest.sha256Of(tag + "-bundle"), incident.incidentKey(), 37,
                "IT_USAGE"), InvestigationInputs.freezeAt(incident, now));
        return run;
    }

    private Incident insertIncident(String tag) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Incident incident = new Incident(id, "alertname=HighErrorRate|service=" + tag,
                IncidentStatus.FIRING, 0, now.minus(Duration.ofMinutes(5)),
                now.minus(Duration.ofMinutes(5)), null, null, null, 0, 0, 0, null,
                now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(5)), now, now);
        incidents.insert(incident);
        return incident;
    }

    /** 真种 rca_task + rca_attempt（rca_model_call 双 FK 的实在面；返回 [taskId, attemptId]）。 */
    private UUID[] mintTaskAttempt(UUID runId) {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO rca_task (id, run_id, task_key, state,
                    available_at, ready_since, deadline_at, created_at, updated_at)
                VALUES (:id, :run, :key, 'DONE', now(), now(), now(), now(), now())
                """)
                .param("id", taskId).param("run", runId)
                .param("key", "USAGE_LEDGER_" + taskId.toString().substring(0, 8))
                .update();
        jdbc.sql("""
                INSERT INTO rca_attempt (id, task_id, attempt_no, lease_epoch, worker_id,
                    status, started_at, finished_at)
                VALUES (:id, :task, 1, 0, 'usage-it', 'SUCCEEDED', now(), now())
                """)
                .param("id", attemptId).param("task", taskId)
                .update();
        return new UUID[]{taskId, attemptId};
    }

    private static RcaModelCallLedger.OpenRow call(UUID id, UUID runId, UUID[] taskAttempt,
            long actionSeq) {
        return new RcaModelCallLedger.OpenRow(id, runId, taskAttempt[0],
                taskAttempt[1], actionSeq, 1, 0, "primary", "1",
                Digest.sha256Of("role").hex(), Digest.sha256Of("prompt-" + actionSeq).hex(),
                null, null, null, null, 0);
    }
}
