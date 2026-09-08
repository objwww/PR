package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository;
import com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository.ShadowWorkRow;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.infrastructure.observability.StructuredLog;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Holmes Shadow 抽样入队面（M6-05，V34 holmes_shadow_work；C-65 持久工作面）。
 *
 * <p>NATIVE 生产 run 终态 SUCCEEDED 后按确定性抽样规则入队一条 COMPARISON 影子工作
 * （shadow_key = {@code holmes-shadow:<native_run_id>}）：由 {@link HolmesShadowWorker}
 * 在同 snapshot digest 上补一轮 Holmes 只读对照执行。本类是纯入队面——
 * <b>零报告/零发布/零 outbox 引用</b>（INV-AM6-5，ArchUnit 钉死）。
 *
 * <p>裁定封闭集（同时是 metrics outcome 标签）：
 * DISABLED / NOT_NATIVE / NOT_TERMINAL / NOT_SUCCEEDED / RATE_NOT_SELECTED /
 * BUDGET_EXHAUSTED / ALREADY_ENQUEUED / ENQUEUED。
 *
 * <p>确定性抽样：sha256({@value #SAMPLE_SALT}:run_id) 前 16 hex 对 100 取模落在
 * sample-rate 内才入选——同 run 重放（进程重启后再触发）裁定恒一致；重入队撞
 * shadow_key 唯一 = ALREADY_ENQUEUED（幂等，非错误）。预算 = 入队时点
 * countCreatedSince(滚动 24h) 与 daily-budget 对账（真实花费在执行面，入队即预留）。
 */
public class HolmesShadowSampler {

    private static final Logger log = LoggerFactory.getLogger(HolmesShadowSampler.class);

    /** 确定性抽样盐（采样裁定只依赖 run 身份，不随时间漂移） */
    public static final String SAMPLE_SALT = "holmes-shadow-sample";

    /** shadow_key 前缀：对照工作（Worker 依此区分对照/校准分支） */
    public static final String COMPARISON_KEY_PREFIX = "holmes-shadow:";

    /** 独立预算计数窗（与 FallbackService 同律：滚动 24h，与 canary/fallback 预算分账） */
    public static final Duration BUDGET_WINDOW = Duration.ofHours(24);

    /** 抽样裁定（观测/测试断言面） */
    public enum Outcome {
        DISABLED, NOT_NATIVE, NOT_TERMINAL, NOT_SUCCEEDED, RATE_NOT_SELECTED,
        BUDGET_EXHAUSTED, ALREADY_ENQUEUED, ENQUEUED
    }

    private final RcaRunRepository runs;
    private final HolmesShadowWorkRepository works;
    private final AlertClock clock;
    private final AlertMetrics metrics;
    private final boolean enabled;
    private final int dailyBudget;
    private final int sampleRate;
    private final int maxAttempts;

    public HolmesShadowSampler(RcaRunRepository runs, HolmesShadowWorkRepository works,
            AlertClock clock, AlertMetrics metrics, boolean enabled, int dailyBudget,
            int sampleRate, int maxAttempts) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.works = Objects.requireNonNull(works, "works");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (dailyBudget < 1) {
            throw new IllegalArgumentException("shadow 预算从 1 起");
        }
        if (sampleRate < 0 || sampleRate > 100) {
            throw new IllegalArgumentException("sample-rate 取值 0-100");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("max-attempts 从 1 起");
        }
        this.enabled = enabled;
        this.dailyBudget = dailyBudget;
        this.sampleRate = sampleRate;
        this.maxAttempts = maxAttempts;
    }

    /**
     * NATIVE run 终态收尾点调用（finishTask SUCCEEDED 分支）。不抛异常、不落
     * reports/publication/outbox——裁定只影响 V34 工作行一条。
     */
    public Outcome tryEnqueueAfterNativeSuccess(RcaRun finishedRun) {
        Outcome outcome = decide(finishedRun);
        metrics.holmesShadowSample(outcome.name());
        StructuredLog.event(log, "rca_holmes_shadow_sample", Map.ofEntries(
                Map.entry("run_id", finishedRun.id().toString()),
                Map.entry("incident_id", finishedRun.incidentId().toString()),
                Map.entry("outcome", outcome.name())));
        return outcome;
    }

    private Outcome decide(RcaRun finishedRun) {
        if (!enabled) {
            return Outcome.DISABLED;
        }
        // 引擎裁定：反向影子只对照 NATIVE 生产 run（routing 面唯一权威；无路由行 =
        // 存量 HOLMES 语义，不入选）
        RcaEngine engine = runs.findRoutingById(finishedRun.id())
                .map(view -> view.engine())
                .orElse(RcaEngine.HOLMES);
        if (engine != RcaEngine.NATIVE) {
            return Outcome.NOT_NATIVE;
        }
        if (finishedRun.state().isActive()) {
            return Outcome.NOT_TERMINAL;
        }
        if (finishedRun.state() != com.objwww.pr.control.alert.domain.model.RcaRunState.SUCCEEDED) {
            // 成功才对照（落码方案 M6-05："成功才恰一次落 V31/V32"）
            return Outcome.NOT_SUCCEEDED;
        }
        if (!selected(finishedRun.id())) {
            return Outcome.RATE_NOT_SELECTED;
        }
        if (works.countCreatedSince(clock.now().minus(BUDGET_WINDOW)) >= dailyBudget) {
            return Outcome.BUDGET_EXHAUSTED;
        }
        boolean enqueued = works.enqueue(ShadowWorkRow.forEnqueue(
                COMPARISON_KEY_PREFIX + finishedRun.id(), "COMPARISON",
                finishedRun.id(), finishedRun.incidentId(), finishedRun.generation(),
                finishedRun.investigationHash().hex(), maxAttempts));
        return enqueued ? Outcome.ENQUEUED : Outcome.ALREADY_ENQUEUED;
    }

    /** 确定性抽样：sha256(salt:runId) 前 16 hex → mod 100 < sampleRate */
    private boolean selected(java.util.UUID runId) {
        if (sampleRate >= 100) {
            return true;
        }
        if (sampleRate <= 0) {
            return false;
        }
        String hex = Digest.sha256Of(SAMPLE_SALT + ":" + runId).hex();
        long bucket = Long.parseUnsignedLong(hex.substring(0, 16), 16) % 100;
        return bucket < sampleRate;
    }
}
