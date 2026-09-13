package com.objwww.pr.control.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.Objects;

/**
 * Micrometer 最小指标（M3-28；禁高基数 ID label——incident/run/task/attempt 一律
 * 不做 tag，只允许封闭枚举值标签）。
 *
 * <p>label allowlist：{@code decision}（FinishOutcome 封闭集）、
 * {@code validation}（ValidationStatus 封闭集）、{@code engine}
 * （RcaEngine 封闭集 + unknown）、{@code disagree}（引擎对照是否含差异）。
 * 新指标加标签必须先扩本类并过 {@code AlertMetricsLabelAllowlistTest}——防
 * "顺手把 UUID 放进 tag"的高基数爆炸。engine 维度（M6-02 观察面成账）：
 * 双引擎同指标分桶，对照期 dashboards 可直接并排看。
 */
public final class AlertMetrics {

    /** 测试/无注册表面（独立 throwaway registry，永不暴露） */
    public static final AlertMetrics NOOP = new AlertMetrics(new SimpleMeterRegistry());

    /** engine 标签缺省值（路由面缺失的历史 run 等） */
    public static final String ENGINE_UNKNOWN = "unknown";

    /** 对账扫描通道（封闭集，label allowlist 面） */
    public static final String CHANNEL_ACTIVE = "active";
    public static final String CHANNEL_CLEANUP = "cleanup";

    private final MeterRegistry registry;
    /** WC-5：看门狗观测面 gauge 状态（强引用防 GC 回收，每拍覆盖写） */
    private final java.util.concurrent.atomic.AtomicLong reconcileLastSuccessEpochMs =
            new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong reconcileOldestUnseenAgeMs =
            new java.util.concurrent.atomic.AtomicLong(-1);
    private final java.util.concurrent.atomic.AtomicLong reconcileTerminalOpenTasks =
            new java.util.concurrent.atomic.AtomicLong(0);

    public AlertMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
        registry.gauge("rca_reconcile_last_success_epoch_ms",
                reconcileLastSuccessEpochMs, java.util.concurrent.atomic.AtomicLong::get);
        registry.gauge("rca_reconcile_oldest_unseen_age_ms",
                reconcileOldestUnseenAgeMs, java.util.concurrent.atomic.AtomicLong::get);
        registry.gauge("rca_reconcile_terminal_run_open_tasks",
                reconcileTerminalOpenTasks, java.util.concurrent.atomic.AtomicLong::get);
    }

    public void taskDecision(String decision, String engine) {
        registry.counter("rca_task_decision_total",
                "decision", safe(decision),
                "engine", safe(engine)).increment();
    }

    public void attemptFinished(String validationStatus, String engine) {
        registry.counter("rca_attempt_finished_total",
                "validation", safe(validationStatus),
                "engine", safe(engine)).increment();
    }

    public void attemptLatency(long millis, String engine) {
        if (millis >= 0) {
            registry.timer("rca_attempt_latency", "engine", safe(engine))
                    .record(Duration.ofMillis(millis));
        }
    }

    /** 引擎对照落账（M6-02）：disagree=true 含差异标记 / false 双侧一致 */
    public void engineComparison(boolean hasDisagreement) {
        registry.counter("rca_engine_comparison_total",
                "disagree", Boolean.toString(hasDisagreement)).increment();
    }

    /** run 级 fallback 裁定（M6-04）：outcome = FallbackService.CastOutcome 封闭集 */
    public void fallbackDecision(String outcome) {
        registry.counter("rca_fallback_decision_total",
                "outcome", safe(outcome)).increment();
    }

    /** Holmes shadow 抽样裁定（M6-05）：outcome = HolmesShadowSampler.Outcome 封闭集 */
    public void holmesShadowSample(String outcome) {
        registry.counter("rca_holmes_shadow_sample_total",
                "outcome", safe(outcome)).increment();
    }

    /** Holmes shadow 工作行处理裁定（M6-05）：outcome = HolmesShadowWorker.Outcome 封闭集 */
    public void holmesShadowWork(String outcome) {
        registry.counter("rca_holmes_shadow_work_total",
                "outcome", safe(outcome)).increment();
    }

    // ------------------------------------------------- WC-5 看门狗/取消闭环观测面
    // 纪律不变：runId/actionKey 不进 label（§6.4）；身份放结构化日志/事件。

    /** 对账扫描单通道结局：时长恒记；失败另计 failure 计数（连续失败告警的数据面） */
    public void reconcileScan(String channel, boolean success, long durationMillis) {
        registry.timer("rca_reconcile_scan_duration", "channel", safe(channel))
                .record(Duration.ofMillis(Math.max(0, durationMillis)));
        if (!success) {
            registry.counter("rca_reconcile_scan_failure_total",
                    "channel", safe(channel)).increment();
        }
    }

    /** 整轮两通道全成功即记（连续多轮不增长 = 看门狗停摆，需外部告警，WC-T29 数据面） */
    public void reconcileScanSucceeded(long epochMillis) {
        reconcileLastSuccessEpochMs.set(epochMillis);
    }

    /**
     * 每拍覆盖写 gauge：最老活跃 Run 年龄（keyset 全覆盖下即"最长未扫描时长"的
     * 上界代理）+ 终态 Run 名下未决任务存量（清理通道积压）。任一传 -1 = 该项
     * 读取失败，保持上一拍值（不假造 0）。
     */
    public void reconcileScanObserved(long oldestUnseenAgeMillis, long terminalOpenTasks) {
        if (oldestUnseenAgeMillis >= 0) {
            reconcileOldestUnseenAgeMs.set(oldestUnseenAgeMillis);
        }
        if (terminalOpenTasks >= 0) {
            reconcileTerminalOpenTasks.set(terminalOpenTasks);
        }
    }

    /** 对账决策计数（Decision 封闭集） */
    public void reconcileDecision(String decision) {
        registry.counter("rca_reconcile_decision_total",
                "decision", safe(decision)).increment();
    }

    /** 取消/终态落地 → 本地执行静默的收敛时长（清理通道收敛时观测） */
    public void cancelToQuiesce(long millis) {
        if (millis >= 0) {
            registry.timer("rca_cancel_to_quiesce").record(Duration.ofMillis(millis));
        }
    }

    /** 迟到提交被围栏拒绝（checkpoint STALE 族 / finishTask 旧代旧租约栅栏） */
    public void lateCommitRejected() {
        registry.counter("rca_late_commit_rejected_total").increment();
    }

    /** 结果未知动作（UNKNOWN 诚实归档）计数——WC-T20 分组观测的数据面 */
    public void unknownAction(long count) {
        if (count > 0) {
            registry.counter("rca_reconcile_unknown_action_total").increment(count);
        }
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
