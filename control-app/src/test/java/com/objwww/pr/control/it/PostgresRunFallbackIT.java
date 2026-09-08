package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.FallbackService;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaAttemptStatus;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.RunFallbackRepository;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaAttemptRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaEventAppender;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaReportRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaTaskRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReportWinnerRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRunFallbackRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M6-04 run 级 fallback + 发布赢家真 PG 集成（V33；命名 *IT 本机无 docker 自动跳过，
 * 195 真跑补证据）：
 * <ul>
 *   <li>20 路并发对同一 NATIVE 源 run 铸 fallback：uq_rf_source 恰一胜出，恰一个
 *       HOLMES run/task/事件（C-68 恰一次栅栏的真并发实证）；</li>
 *   <li>depth=1 结构封死 + 封闭错误类 fail-closed（真 PG 引擎裁定面）；</li>
 *   <li>发布赢家 CAS：并发 claim 恰一胜出；败者报告可落档但无第二 publication；</li>
 *   <li>insert-only 授权面真库兜底（V33 revoke update/delete → 42501）。</li>
 * </ul>
 */
class PostgresRunFallbackIT extends PostgresITBase {

    private PostgresRcaRunRepository runs;
    private PostgresRunFallbackRepository fallbacks;
    private PostgresReportWinnerRepository winners;
    private PostgresRcaTaskRepository tasks;
    private PostgresRcaAttemptRepository attempts;
    private PostgresRcaReportRepository reports;
    private FallbackService fallback;

    @Override
    @BeforeEach
    void truncateAll() {
        super.truncateAll();
        runs = new PostgresRcaRunRepository(controlJdbc);
        fallbacks = new PostgresRunFallbackRepository(controlJdbc);
        winners = new PostgresReportWinnerRepository(controlJdbc);
        tasks = new PostgresRcaTaskRepository(controlJdbc);
        attempts = new PostgresRcaAttemptRepository(controlJdbc);
        reports = new PostgresRcaReportRepository(controlJdbc);
        var incidents = new com.objwww.pr.control.infrastructure.persistence
                .PostgresIncidentRepository(controlJdbc);
        AlertClock clock = Instant::now;
        RcaEventAppender events = new PostgresRcaEventAppender(controlJdbc, controlTx, controlTx);
        fallback = new FallbackService(runs, incidents, tasks, events, fallbacks,
                SlaPolicy.defaults(), clock, AlertMetrics.NOOP, true, 20);
    }

    @Test
    void twentyWayConcurrentCastCastsExactlyOneFallback() throws Exception {
        Seed seed = seedNativeFailedRun("fb20", 0);
        RcaRun failed = runs.findById(seed.runId()).orElseThrow();

        ExecutorService pool = Executors.newFixedThreadPool(20);
        AtomicInteger cast = new AtomicInteger();
        AtomicInteger already = new AtomicInteger();
        try {
            List<java.util.concurrent.Future<FallbackService.CastOutcome>> futures =
                    IntStream.range(0, 20)
                            .mapToObj(i -> pool.submit(() -> controlTx.execute(status ->
                                    fallback.tryCastFromFailedNative(failed, "TIMEOUT", 1))))
                            .toList();
            for (var future : futures) {
                FallbackService.CastOutcome outcome = future.get(30, TimeUnit.SECONDS);
                if (outcome == FallbackService.CastOutcome.CAST) {
                    cast.incrementAndGet();
                } else if (outcome == FallbackService.CastOutcome.ALREADY_CAST) {
                    already.incrementAndGet();
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(cast.get()).as("恰一个并发胜者").isEqualTo(1);
        assertThat(already.get()).as("其余 19 路全为占位败者").isEqualTo(19);
        assertThat(count("run_fallback")).isEqualTo(1);
        assertThat(count("rca_run")).as("源 + 恰一个 fallback run").isEqualTo(2);
        assertThat(count("rca_task")).as("恰一个 HOLMES_INVESTIGATE driver task").isEqualTo(1);
        assertThat(adminJdbc.sql("""
                        SELECT count(*) FROM rca_event WHERE event_type = 'fallback_of'
                        """).query(Long.class).single())
                .as("fallback_of 审计事件恰一条（不承担唯一性，只留痕）").isEqualTo(1);
        UUID fallbackRunId = fallbacks.findBySourceRunId(seed.runId()).orElseThrow()
                .fallbackRunId();
        assertThat(runs.findRoutingById(fallbackRunId).orElseThrow().engine())
                .as("fallback run 走 DB 默认 HOLMES 语义").isEqualTo(RcaEngine.HOLMES);
        // 重放（进程重启后再触发同源）：仍是 ALREADY_CAST，不双铸
        FallbackService.CastOutcome replay = controlTx.execute(status ->
                fallback.tryCastFromFailedNative(failed, "TIMEOUT", 1));
        assertThat(replay).isEqualTo(FallbackService.CastOutcome.ALREADY_CAST);
        assertThat(count("run_fallback")).isEqualTo(1);
        assertThat(count("rca_run")).isEqualTo(2);
    }

    @Test
    void depthTwoAndSemanticClassesStayClosed() {
        Seed seed = seedNativeFailedRun("fbdepth", 0);
        RcaRun failed = runs.findById(seed.runId()).orElseThrow();
        FallbackService.CastOutcome first = controlTx.execute(status ->
                fallback.tryCastFromFailedNative(failed, "EXECUTOR_ERROR", 1));
        assertThat(first).isEqualTo(FallbackService.CastOutcome.CAST);

        // fallback 产物（HOLMES 语义）自身失败：错误类在封闭集内也绝不二跳（depth=1）
        UUID fallbackRunId = fallbacks.findBySourceRunId(seed.runId()).orElseThrow()
                .fallbackRunId();
        RcaRun fallbackRun = runs.findById(fallbackRunId).orElseThrow();
        FallbackService.CastOutcome second = controlTx.execute(status ->
                fallback.tryCastFromFailedNative(fallbackRun, "EXECUTOR_ERROR", 1));
        assertThat(second).isEqualTo(FallbackService.CastOutcome.INELIGIBLE_ENGINE);
        assertThat(count("run_fallback")).isEqualTo(1);

        // 语义分歧域（FUT-12）：低质量/确定性拒绝类永不触发
        Seed lowQuality = seedNativeFailedRun("fbsem", 0);
        FallbackService.CastOutcome semantic = controlTx.execute(status ->
                fallback.tryCastFromFailedNative(
                        runs.findById(lowQuality.runId()).orElseThrow(),
                        "ADAPTER_PACKAGE_REJECTED", 1));
        assertThat(semantic).isEqualTo(FallbackService.CastOutcome.INELIGIBLE_ERROR_CLASS);
        assertThat(count("run_fallback")).isEqualTo(1);
    }

    @Test
    void publicationWinnerCasHasSingleWinnerUnderRace() throws Exception {
        // 种 run+task+attempt 链：败者报告经真仓储落档（FK 链完整，INV-AM3-7 诚实记账面）
        UUID incidentId = seedIncidentOnly("fbwinner");
        UUID runId = UUID.randomUUID();
        runs.insert(new RcaRun(runId, incidentId, 0, RunTrigger.INITIAL, RcaRunState.SUCCEEDED,
                Digest.sha256Of("it-fb-winner"), Instant.now(), Instant.now(), Instant.now(),
                Instant.now(), null));
        UUID taskId = UUID.randomUUID();
        tasks.insert(new RcaTask(taskId, runId, RcaTask.HOLMES_INVESTIGATE, RcaTaskState.DONE,
                100, Instant.now(), Instant.now(), Instant.now(), null, null, 0, 1, 3,
                Instant.now(), Instant.now()));
        UUID attemptId = UUID.randomUUID();
        attempts.insert(new RcaAttempt(attemptId, taskId, 0, 0, "it",
                RcaAttemptStatus.SUCCEEDED, null, null, null, Instant.now(),
                Instant.now(), null));

        ExecutorService pool = Executors.newFixedThreadPool(20);
        AtomicInteger won = new AtomicInteger();
        try {
            List<java.util.concurrent.Future<Boolean>> futures =
                    IntStream.range(0, 20)
                            .mapToObj(i -> pool.submit(() -> winners.claimWinner(incidentId, 0,
                                    UUID.randomUUID(), UUID.randomUUID(), Instant.now())))
                            .toList();
            for (var future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    won.incrementAndGet();
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(won.get()).as("并发 claim 恰一赢家").isEqualTo(1);
        assertThat(count("report_generation_winner")).isEqualTo(1);

        // 败者报告仍落档（诚实记账），但夺不到赢家位
        UUID loserReport = UUID.randomUUID();
        reports.insert(new RcaReport(loserReport, runId, attemptId, 1,
                ValidationStatus.STRUCTURE_VALIDATED, List.of(), "{}", "raw", "m",
                0, 0, 0, true, Instant.now()));
        assertThat(winners.claimWinner(incidentId, 0, loserReport, runId, Instant.now()))
                .isFalse();
        assertThat(winners.findWinnerReportId(incidentId, 0)).isNotEqualTo(loserReport);
        assertThat(count("rca_report")).as("败者报告仍落档").isEqualTo(1);
    }

    @Test
    void occupancyAndWinnerTablesAreInsertOnlyForControlApp() {
        Seed seed = seedNativeFailedRun("fbgrant", 0);
        controlTx.executeWithoutResult(status -> fallback.tryCastFromFailedNative(
                runs.findById(seed.runId()).orElseThrow(), "TIMEOUT", 1));
        winners.claimWinner(seed.incidentId(), 0, UUID.randomUUID(), seed.runId(),
                Instant.now());

        // 授权纪律（V33 revoke）：control_app 无 UPDATE/DELETE——栅栏行不可改写
        assertThatThrownBy(() -> controlJdbc.sql(
                        "UPDATE run_fallback SET error_class = 'tampered'")
                .update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> controlJdbc.sql(
                        "DELETE FROM run_fallback").update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> controlJdbc.sql(
                        "UPDATE report_generation_winner SET winner_report_id = :rid")
                .param("rid", UUID.randomUUID()).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> controlJdbc.sql(
                        "DELETE FROM report_generation_winner").update())
                .isInstanceOf(DataAccessException.class);
        assertThat(count("run_fallback")).isEqualTo(1);
        assertThat(count("report_generation_winner")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 种子

    private record Seed(UUID incidentId, UUID runId) {
    }

    private Seed seedNativeFailedRun(String tag, int generation) {
        UUID incidentId = seedIncidentOnly(tag + "-" + generation);
        UUID runId = UUID.randomUUID();
        RcaRun run = new RcaRun(runId, incidentId, generation, RunTrigger.INITIAL,
                RcaRunState.FAILED, Digest.sha256Of("it-fb-" + tag),
                Instant.now(), Instant.now(), Instant.now(), Instant.now(), "TIMEOUT");
        runs.insertRouted(run, new RcaRunRouting(RcaEngine.NATIVE,
                Digest.sha256Of("bundle-" + tag), "svc-" + tag, 7, "WHITELISTED"));
        return new Seed(incidentId, runId);
    }

    private UUID seedIncidentOnly(String tag) {
        UUID incidentId = UUID.randomUUID();
        controlJdbc.sql("""
                        INSERT INTO incident(id, incident_key, status, generation, episode_started_at,
                            first_seen_at, last_event_at, created_at, updated_at)
                        VALUES (:id, :key, 'FIRING', 0, now(), now(), now(), now(), now())
                        """).param("id", incidentId)
                .param("key", "alertname=HighErrorRate|service=" + tag + "-" + incidentId)
                .update();
        return incidentId;
    }
}
