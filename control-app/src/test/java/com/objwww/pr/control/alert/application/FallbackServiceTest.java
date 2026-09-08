package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.RunFallbackRepository;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.shared.Digest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6-04 FallbackService 场景闭环（in-memory；并发恰一次语义由 195 真 PG IT 覆盖）：
 * NATIVE run 安全/运行故障恰一次铸 HOLMES RERUN——uq_rf_source 唯一占位 + 同事务
 * 铸 run/task + incident 指针上移 + fallback_of 审计事件；封闭错误类 fail-closed、
 * depth=1 结构封死、独立预算、incident 忙裁定、开关面。
 */
class FallbackServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-08T10:00:00Z");

    private static final class MutableClock implements AlertClock {
        volatile Instant now = T0;

        @Override
        public Instant now() {
            return now;
        }
    }

    private AlertInMemoryStores stores;
    private MutableClock clock;
    private FallbackService fallback;

    @BeforeEach
    void setUp() {
        stores = new AlertInMemoryStores();
        clock = new MutableClock();
        fallback = newFallback(20, true);
    }

    private FallbackService newFallback(int budget, boolean enabled) {
        return new FallbackService(stores.runs, stores.incidents, stores.tasks,
                stores.rcaEvents, stores.fallbacks, SlaPolicy.defaults(), clock,
                new AlertMetrics(new SimpleMeterRegistry()), enabled, budget);
    }

    /** 种一个 NATIVE 路由 run（state 可指定；generation/is 不同 incident 由参数区分） */
    private record Seed(UUID incidentId, UUID runId) {
    }

    private Seed seedNativeRun(RcaRunState state, String errorClass, int generation) {
        UUID incidentId = UUID.randomUUID();
        stores.incidents.insert(new Incident(incidentId, "alertname=HighErrorRate|service=svc-"
                + incidentId.toString().substring(0, 8), IncidentStatus.FIRING, generation,
                T0, T0, null, Digest.sha256Of("material-" + incidentId),
                null, 1, 1, 0, null, T0, T0, T0, T0));
        UUID runId = UUID.randomUUID();
        RcaRun run = new RcaRun(runId, incidentId, generation, RunTrigger.INITIAL, state,
                Digest.sha256Of("material-" + incidentId),
                T0, T0, T0, state.isActive() ? null : T0, errorClass);
        stores.runs.insertRouted(run, new RcaRunRouting(RcaEngine.NATIVE,
                Digest.sha256Of("bundle"), "svc", 7, "WHITELISTED"));
        return new Seed(incidentId, runId);
    }

    // ------------------------------------------------------------------ 恰一次铸造

    @Test
    @DisplayName("CAST：占位 + 同事务铸 HOLMES RERUN + 指针上移 + fallback_of 审计事件")
    void castOccupiesThenCastsHolmesRerunAtomically() {
        Seed seed = seedNativeRun(RcaRunState.FAILED, "PROPOSAL_MISSING", 0);

        FallbackService.CastOutcome outcome = fallback.tryCastFromFailedNative(
                stores.runs.findById(seed.runId()).orElseThrow(), "PROPOSAL_MISSING", 2);

        assertThat(outcome).isEqualTo(FallbackService.CastOutcome.CAST);
        // 占位行：源 run 唯一、depth=1、错误类留痕
        assertThat(stores.fallbacks.all()).hasSize(1);
        RunFallbackRepository.OccupancyRow row = stores.fallbacks.findBySourceRunId(seed.runId())
                .orElseThrow();
        assertThat(row.sourceIncidentId()).isEqualTo(seed.incidentId());
        assertThat(row.depth()).isEqualTo(1);
        assertThat(row.errorClass()).isEqualTo("PROPOSAL_MISSING");
        // 铸的 run：HOLMES 语义（plain insert 无路由 → DB 默认 HOLMES）、RERUN、QUEUED、同代同材料
        assertThat(stores.runs.all()).hasSize(2);
        RcaRun cast = stores.runs.all().stream().filter(r -> !r.id().equals(seed.runId()))
                .findFirst().orElseThrow();
        assertThat(cast.trigger()).isEqualTo(RunTrigger.RERUN);
        assertThat(cast.state()).isEqualTo(RcaRunState.QUEUED);
        assertThat(cast.generation()).isEqualTo(0);
        assertThat(stores.runs.findRoutingById(cast.id()).orElseThrow().engine())
                .isEqualTo(RcaEngine.HOLMES);
        assertThat(row.fallbackRunId()).isEqualTo(cast.id());
        // task：HOLMES_INVESTIGATE（worker 分派面走 holmes 执行器）、READY
        List<RcaTask> tasks = stores.tasks.findByRunId(cast.id());
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).taskKey()).isEqualTo(RcaTask.HOLMES_INVESTIGATE);
        assertThat(tasks.get(0).state()).isEqualTo(RcaTaskState.READY);
        assertThat(tasks.get(0).priority()).isEqualTo(2);
        // incident 指针上移到 fallback run
        assertThat(stores.incidents.findById(seed.incidentId()).orElseThrow().currentRcaRunId())
                .isEqualTo(cast.id());
        // fallback_of 审计事件（挂在 fallback run 流上，载荷携带源身份）
        var events = stores.rcaEvents.all();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo("fallback_of");
        assertThat(events.get(0).runId()).isEqualTo(cast.id());
        assertThat(events.get(0).payloadJson()).contains(seed.runId.toString());
        // BA-55：payload 落真 PG json 列，必须是 JSON 形（非 Map.toString 的 {k=v}）
        assertThat(events.get(0).payloadJson()).startsWith("{").contains("\"source_run_id\"");
        assertThat(events.get(0).payloadJson()).contains("PROPOSAL_MISSING");
    }

    @Test
    @DisplayName("ALREADY_CAST：同源 run 二次触发 = 占位败者，不重铸（恰一次）")
    void secondTriggerForSameSourceIsLoser() {
        Seed seed = seedNativeRun(RcaRunState.FAILED, "TIMEOUT", 0);
        RcaRun failed = stores.runs.findById(seed.runId()).orElseThrow();

        assertThat(fallback.tryCastFromFailedNative(failed, "TIMEOUT", 1))
                .isEqualTo(FallbackService.CastOutcome.CAST);
        assertThat(fallback.tryCastFromFailedNative(failed, "TIMEOUT", 1))
                .isEqualTo(FallbackService.CastOutcome.ALREADY_CAST);

        assertThat(stores.fallbacks.all()).hasSize(1);
        assertThat(stores.runs.all()).hasSize(2);   // 源 + 恰一个 fallback
    }

    // ------------------------------------------------------------------ depth=1 / 封闭错误类

    @Test
    @DisplayName("depth=1：HOLMES run（含 fallback 产物）失败不再触发——引擎裁定结构性封死")
    void depthTwoIsRejectedByEngineGuard() {
        Seed seed = seedNativeRun(RcaRunState.FAILED, "EXECUTOR_ERROR", 0);
        assertThat(fallback.tryCastFromFailedNative(
                stores.runs.findById(seed.runId()).orElseThrow(), "EXECUTOR_ERROR", 1))
                .isEqualTo(FallbackService.CastOutcome.CAST);

        // fallback 产物（HOLMES 语义）自身失败：错误类在封闭集内也绝不二跳
        RcaRun fallbackRun = stores.runs.all().stream()
                .filter(r -> !r.id().equals(seed.runId())).findFirst().orElseThrow();
        assertThat(fallback.tryCastFromFailedNative(fallbackRun, "EXECUTOR_ERROR", 1))
                .isEqualTo(FallbackService.CastOutcome.INELIGIBLE_ENGINE);
        assertThat(stores.fallbacks.all()).hasSize(1);
        assertThat(stores.runs.all()).hasSize(2);
    }

    @Test
    @DisplayName("封闭错误类：低质量/语义域（ADAPTER_PACKAGE_REJECTED/PLAN_REJECTED）不触发")
    void semanticClassesNeverTrigger() {
        Seed lowQuality = seedNativeRun(RcaRunState.FAILED, "ADAPTER_PACKAGE_REJECTED", 0);
        assertThat(fallback.tryCastFromFailedNative(
                stores.runs.findById(lowQuality.runId()).orElseThrow(),
                "ADAPTER_PACKAGE_REJECTED", 1))
                .isEqualTo(FallbackService.CastOutcome.INELIGIBLE_ERROR_CLASS);
        Seed planRejected = seedNativeRun(RcaRunState.FAILED, "PLAN_REJECTED", 0);
        assertThat(fallback.tryCastFromFailedNative(
                stores.runs.findById(planRejected.runId()).orElseThrow(), "PLAN_REJECTED", 1))
                .isEqualTo(FallbackService.CastOutcome.INELIGIBLE_ERROR_CLASS);
        Seed unknown = seedNativeRun(RcaRunState.FAILED, "SOME_FUTURE_CLASS", 0);
        assertThat(fallback.tryCastFromFailedNative(
                stores.runs.findById(unknown.runId()).orElseThrow(), "SOME_FUTURE_CLASS", 1))
                .isEqualTo(FallbackService.CastOutcome.INELIGIBLE_ERROR_CLASS);

        assertThat(stores.fallbacks.all()).isEmpty();
        assertThat(stores.runs.all()).hasSize(3);   // 只有三个源，零 fallback
    }

    // ------------------------------------------------------------------ incident 忙 / 开关 / 预算

    @Test
    @DisplayName("incident 忙：同 incident 已有活跃 run（RERUN 已上位）→ 不再 fallback（资格消耗留审计）")
    void incidentBusySkipsFallback() {
        Seed seed = seedNativeRun(RcaRunState.FAILED, "TIMEOUT", 0);
        // 并发铸造者已上位一个活跃 run
        stores.runs.insert(new RcaRun(UUID.randomUUID(), seed.incidentId(), 0,
                RunTrigger.RERUN, RcaRunState.QUEUED,
                Digest.sha256Of("new-material"),
                T0, T0, null, null, null));

        assertThat(fallback.tryCastFromFailedNative(
                stores.runs.findById(seed.runId()).orElseThrow(), "TIMEOUT", 1))
                .isEqualTo(FallbackService.CastOutcome.INCIDENT_BUSY);
        // 占位行保留（资格已消耗，incident 被并发 run 覆盖无空窗），但零新铸
        assertThat(stores.fallbacks.all()).hasSize(1);
        assertThat(stores.runs.all()).hasSize(2);
    }

    @Test
    @DisplayName("开关面：enabled=false 一律 DISABLED（M6-07 退场时的 sanctioned 闸）")
    void disabledFlagShortCircuits() {
        Seed seed = seedNativeRun(RcaRunState.FAILED, "TIMEOUT", 0);
        FallbackService off = newFallback(20, false);

        assertThat(off.tryCastFromFailedNative(
                stores.runs.findById(seed.runId()).orElseThrow(), "TIMEOUT", 1))
                .isEqualTo(FallbackService.CastOutcome.DISABLED);
        assertThat(stores.fallbacks.all()).isEmpty();
    }

    @Test
    @DisplayName("独立预算：窗口内占位数达预算上限即拒；窗外行不计数")
    void independentBudgetExhaustsAndSlidesWindow() {
        Seed first = seedNativeRun(RcaRunState.FAILED, "TIMEOUT", 0);
        FallbackService tight = newFallback(1, true);
        assertThat(tight.tryCastFromFailedNative(
                stores.runs.findById(first.runId()).orElseThrow(), "TIMEOUT", 1))
                .isEqualTo(FallbackService.CastOutcome.CAST);

        Seed second = seedNativeRun(RcaRunState.FAILED, "TIMEOUT", 0);
        assertThat(tight.tryCastFromFailedNative(
                stores.runs.findById(second.runId()).orElseThrow(), "TIMEOUT", 1))
                .isEqualTo(FallbackService.CastOutcome.BUDGET_EXHAUSTED);
        assertThat(stores.fallbacks.all()).hasSize(1);

        // 预算窗滑动：24h 后旧占位出窗，预算恢复
        clock.now = T0.plusSeconds(BUDGET_WINDOW_PLUS_1S);
        Seed third = seedNativeRun(RcaRunState.FAILED, "TIMEOUT", 0);
        assertThat(tight.tryCastFromFailedNative(
                stores.runs.findById(third.runId()).orElseThrow(), "TIMEOUT", 1))
                .isEqualTo(FallbackService.CastOutcome.CAST);
        assertThat(stores.fallbacks.all()).hasSize(2);
    }

    /** 24h + 1s（预算窗滑动用秒数） */
    private static final long BUDGET_WINDOW_PLUS_1S = 24 * 3600L + 1;
}
