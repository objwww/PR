package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository.ShadowWorkRow;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository.RoutingView;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HolmesShadowSampler 单测（M6-05 抽样入队面）：裁定封闭集 + 确定性抽样 +
 * 预算对账 + shadow_key 幂等。本类零 reports/publication/outbox 引用
 * （INV-AM6-5 由 ArchUnit 钉死，此处运行时断言工作行之外零副作用）。
 */
class HolmesShadowSamplerTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    private static final class MutableClock implements AlertClock {
        volatile Instant now = NOW;

        @Override
        public Instant now() {
            return now;
        }
    }

    private AlertInMemoryStores stores;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        stores = new AlertInMemoryStores();
        clock = new MutableClock();
    }

    private HolmesShadowSampler sampler(boolean enabled, int dailyBudget, int sampleRate) {
        return new HolmesShadowSampler(stores.runs, stores.shadowWorks, clock,
                AlertMetrics.NOOP, enabled, dailyBudget, sampleRate, 3);
    }

    private RcaRun nativeSucceededRun() {
        RcaRun run = new RcaRun(UUID.randomUUID(), UUID.randomUUID(), 2, RunTrigger.INITIAL,
                RcaRunState.SUCCEEDED, Digest.sha256Of("snapshot"), NOW, NOW,
                NOW.minusSeconds(10), NOW, null);
        stores.runs.insert(run);
        stores.runs.insertRouted(run, new RcaRunRouting(
                com.objwww.pr.control.alert.domain.model.RcaEngine.NATIVE,
                Digest.sha256Of("bundle"), "key", 7, "BUCKETED_NATIVE"));
        return run;
    }

    @Test
    void enqueuesDeterministicComparisonWorkForNativeSuccess() {
        RcaRun run = nativeSucceededRun();

        HolmesShadowSampler.Outcome outcome = sampler(true, 20, 100)
                .tryEnqueueAfterNativeSuccess(run);

        assertThat(outcome).isEqualTo(HolmesShadowSampler.Outcome.ENQUEUED);
        var row = stores.shadowWorks.findByShadowKey(
                HolmesShadowSampler.COMPARISON_KEY_PREFIX + run.id()).orElseThrow();
        assertThat(row.kind()).isEqualTo("COMPARISON");
        assertThat(row.nativeRunId()).isEqualTo(run.id());
        assertThat(row.incidentId()).isEqualTo(run.incidentId());
        assertThat(row.generation()).isEqualTo(run.generation());
        assertThat(row.snapshotDigest()).isEqualTo(run.investigationHash().hex());
        assertThat(row.state()).isEqualTo("QUEUED");
        assertThat(row.maxAttempts()).isEqualTo(3);
        // 影子抽样零发布面副作用：reports/publications/outboxes 全空
        assertThat(stores.reports.all()).isEmpty();
        assertThat(stores.publications.all()).isEmpty();
        assertThat(stores.outboxes.all()).isEmpty();
    }

    @Test
    void replayIsIdempotentAlreadyEnqueued() {
        RcaRun run = nativeSucceededRun();
        HolmesShadowSampler s = sampler(true, 20, 100);
        s.tryEnqueueAfterNativeSuccess(run);

        assertThat(s.tryEnqueueAfterNativeSuccess(run))
                .isEqualTo(HolmesShadowSampler.Outcome.ALREADY_ENQUEUED);
        assertThat(stores.shadowWorks.all()).hasSize(1);
    }

    @Test
    void disabledShortCircuitsBeforeAnyFace() {
        RcaRun run = nativeSucceededRun();

        assertThat(sampler(false, 20, 100).tryEnqueueAfterNativeSuccess(run))
                .isEqualTo(HolmesShadowSampler.Outcome.DISABLED);
        assertThat(stores.shadowWorks.all()).isEmpty();
    }

    @Test
    void holmesRoutedRunIsNotNative() {
        RcaRun run = new RcaRun(UUID.randomUUID(), UUID.randomUUID(), 1, RunTrigger.INITIAL,
                RcaRunState.SUCCEEDED, Digest.sha256Of("m"), NOW, NOW, NOW, NOW, null);
        stores.runs.insert(run);
        stores.runs.insertRouted(run, RcaRunRouting.holmes(null, null, null, "HOLMES_HOLD"));

        assertThat(sampler(true, 20, 100).tryEnqueueAfterNativeSuccess(run))
                .isEqualTo(HolmesShadowSampler.Outcome.NOT_NATIVE);
        assertThat(stores.shadowWorks.all()).isEmpty();
    }

    @Test
    void activeRunIsNotTerminalAndFailedRunIsNotSampled() {
        RcaRun active = new RcaRun(UUID.randomUUID(), UUID.randomUUID(), 1, RunTrigger.INITIAL,
                RcaRunState.RUNNING, Digest.sha256Of("m"), NOW, NOW, NOW, null, null);
        stores.runs.insertRouted(active, new RcaRunRouting(
                com.objwww.pr.control.alert.domain.model.RcaEngine.NATIVE,
                Digest.sha256Of("b"), "k", 1, "BUCKETED_NATIVE"));
        assertThat(sampler(true, 20, 100).tryEnqueueAfterNativeSuccess(active))
                .isEqualTo(HolmesShadowSampler.Outcome.NOT_TERMINAL);

        RcaRun failed = new RcaRun(active.id(), active.incidentId(), 1, RunTrigger.INITIAL,
                RcaRunState.FAILED, active.investigationHash(), NOW, NOW, NOW, NOW, "TIMEOUT");
        stores.runs.update(failed);
        assertThat(sampler(true, 20, 100).tryEnqueueAfterNativeSuccess(failed))
                .isEqualTo(HolmesShadowSampler.Outcome.NOT_SUCCEEDED);
        assertThat(stores.shadowWorks.all()).isEmpty();
    }

    @Test
    void sampleRateZeroNeverSelectsAndRateIsDeterministic() {
        RcaRun run = nativeSucceededRun();
        assertThat(sampler(true, 20, 0).tryEnqueueAfterNativeSuccess(run))
                .isEqualTo(HolmesShadowSampler.Outcome.RATE_NOT_SELECTED);
        // rate=100 恒入选，且裁定与首次一致（同 run 重放确定性）
        assertThat(sampler(true, 20, 100).tryEnqueueAfterNativeSuccess(run))
                .isEqualTo(HolmesShadowSampler.Outcome.ENQUEUED);
        assertThat(sampler(true, 20, 100).tryEnqueueAfterNativeSuccess(run))
                .isEqualTo(HolmesShadowSampler.Outcome.ALREADY_ENQUEUED);
    }

    @Test
    void budgetExhaustedCountsQueueWithinWindow() {
        nativeSucceededRun();
        // 预算=1 已被一条在窗工作占满（直接入队模拟历史占用）
        stores.shadowWorks.enqueue(ShadowWorkRow.forEnqueue("holmes-shadow:other",
                "COMPARISON", UUID.randomUUID(), UUID.randomUUID(), 1,
                Digest.sha256Of("x").hex(), 3));

        RcaRun second = new RcaRun(UUID.randomUUID(), UUID.randomUUID(), 1, RunTrigger.INITIAL,
                RcaRunState.SUCCEEDED, Digest.sha256Of("m2"), NOW, NOW, NOW, NOW, null);
        stores.runs.insert(second);
        stores.runs.insertRouted(second, new RcaRunRouting(
                com.objwww.pr.control.alert.domain.model.RcaEngine.NATIVE,
                Digest.sha256Of("b"), "k2", 2, "BUCKETED_NATIVE"));

        assertThat(sampler(true, 1, 100).tryEnqueueAfterNativeSuccess(second))
                .isEqualTo(HolmesShadowSampler.Outcome.BUDGET_EXHAUSTED);
        assertThat(stores.shadowWorks.all()).hasSize(1);
    }
}
