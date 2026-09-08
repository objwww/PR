package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaAttemptStatus;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository;
import com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository.ShadowWorkRow;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.RcaAttemptRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.infrastructure.observability.StructuredLog;
import com.objwww.pr.control.release.application.EngineComparisonRecorder;
import com.objwww.pr.control.release.domain.repository.EngineComparisonRepository;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Holmes Shadow 工作执行器（M6-05，C-65）：消费 V34 holmes_shadow_work 一行，
 * 在同 snapshot digest 上补一轮 <b>只读</b> Holmes 执行并落 V32 engine_comparison。
 *
 * <p>零发布纪律（INV-AM6-5 的执行面）：本类不铸生产 run、不调 finishTask、
 * 零 reports/publications/outbox/winner 写面。执行锚（executor 的账本 FK 要求
 * external_invocation_ledger → run/task/attempt NOT NULL）= 三条影子行：
 * run 直接 RUNNING 终态自收（{@code finished_at} 由本类回写，不走 §6.7 算法）、
 * task 直接 DONE 终态（零 worker 认领面）、attempt STARTED → 终态。run 撞
 * uq_rca_run_active_incident（同 incident 活跃 HOLMES 行，如 fallback 铸造中）
 * = 诚实 FAILED + 有界重试。
 *
 * <p>恰一次语义分层：V32 append 以 uq_ec_pair 幂等（先落账后收口工作行——租约
 * 窗口内重复执行只多一次幂等 append，不丢账）；工作行收口 (owner, leaseEpoch,
 * LEASED) 三元 CAS（败者结果作废，metrics 记 CAS_LOST）；校准工作在对照落账后
 * 撞 {@code holmes-calib:<native_run_id>} 幂等入队。
 *
 * <p>租约宽限：工作行租约必须 &gt; Holmes 最长在途窗（read-timeout 8min），默认
 * PT15M；过期被回收重领时原持有者的 complete/markFailed 全部 0 行（epoch 栅栏）。
 */
public class HolmesShadowWorker {

    private static final Logger log = LoggerFactory.getLogger(HolmesShadowWorker.class);

    /** V32 shadow_exec_ref 审计引用（M6-06 差异台账按此取数） */
    public static final String SHADOW_EXEC_REF = "holmes-shadow-worker";

    /** shadow_key 前缀：底噪校准工作（同 native run 家族第二条） */
    public static final String CALIBRATION_KEY_PREFIX = "holmes-calib:";

    /** 工作行处理裁定（metrics outcome 封闭集） */
    public enum Outcome {
        COMPARISON_LANDED, CALIBRATION_LANDED, FAILED, CAS_LOST, MISSING_ANCHOR,
        INCIDENT_BUSY
    }

    private static final int ERROR_MAX = 512;

    private final HolmesShadowWorkRepository works;
    private final RcaRunRepository runs;
    private final IncidentRepository incidents;
    private final RcaTaskRepository tasks;
    private final RcaAttemptRepository attempts;
    private final RcaTaskExecutor holmesExecutor;
    private final EngineComparisonRepository comparisons;
    private final EngineComparisonRecorder recorder;
    private final SlaPolicy sla;
    private final AlertClock clock;
    private final AlertMetrics metrics;
    private final String owner;

    public HolmesShadowWorker(HolmesShadowWorkRepository works, RcaRunRepository runs,
            IncidentRepository incidents, RcaTaskRepository tasks,
            RcaAttemptRepository attempts, RcaTaskExecutor holmesExecutor,
            EngineComparisonRepository comparisons, EngineComparisonRecorder recorder,
            SlaPolicy sla, AlertClock clock, AlertMetrics metrics, String owner) {
        this.works = Objects.requireNonNull(works, "works");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.incidents = Objects.requireNonNull(incidents, "incidents");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.holmesExecutor = Objects.requireNonNull(holmesExecutor, "holmesExecutor");
        this.comparisons = Objects.requireNonNull(comparisons, "comparisons");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.sla = Objects.requireNonNull(sla, "sla");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /** 处理一条已认领工作行（Scheduler claimBatch 的委托面）。不抛——失败进有界重试。 */
    public Outcome process(ShadowWorkRow row) {
        Instant now = clock.now();
        Incident incident = incidents.findById(row.incidentId()).orElse(null);
        if (incident == null || runs.findById(row.nativeRunId()).isEmpty()) {
            return failWork(row, "MISSING_ANCHOR", "incident 或 native run 不存在", now);
        }
        UUID shadowRunId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        RcaRun shadowRun = new RcaRun(shadowRunId, row.incidentId(), row.generation(),
                RunTrigger.RERUN, RcaRunState.RUNNING,
                new Digest(row.snapshotDigest()), now, now, now, null, null);
        RcaTask task = new RcaTask(taskId, shadowRunId, RcaTask.HOLMES_INVESTIGATE,
                RcaTaskState.DONE, 0, now, now, sla.deadline(now, 0),
                null, null, 0, 0, 1, now, now);
        RcaAttempt attempt = new RcaAttempt(attemptId, taskId, 1, 1L, owner,
                RcaAttemptStatus.STARTED, null, null, null, now, null, null);
        try {
            runs.insert(shadowRun);
            tasks.insert(task);
            attempts.insert(attempt);
        } catch (DuplicateKeyException busy) {
            // 同 incident 已有活跃 HOLMES run（生产调查/fallback 在途）：诚实失败待重试
            works.markFailed(row.id(), row.leaseOwner(), row.leaseEpoch(),
                    "INCIDENT_ACTIVE_HOLMES_RUN", now);
            metrics.holmesShadowWork(Outcome.INCIDENT_BUSY.name());
            return Outcome.INCIDENT_BUSY;
        }

        RcaTaskExecutor.ExecutionResult result;
        try {
            result = holmesExecutor.execute(task, shadowRun, incident, attempt, () -> {
                // 影子执行无 task 租约可续（task 已 DONE）；工作行租约宽限 > 最长在途窗
            });
        } catch (RuntimeException e) {
            markExecutionFailed(shadowRun, attempt, RcaAttemptStatus.FAILED_TERMINAL,
                    "EXECUTOR_EXCEPTION", String.valueOf(e.getMessage()));
            return failWork(row, "EXECUTOR_EXCEPTION", String.valueOf(e.getMessage()), now);
        }

        if (result.outcome() != RcaTaskExecutor.ExecutionResult.Outcome.SUCCEEDED) {
            RcaAttemptStatus status = result.outcome()
                    == RcaTaskExecutor.ExecutionResult.Outcome.FAILED_RETRYABLE
                    ? RcaAttemptStatus.FAILED_RETRYABLE : RcaAttemptStatus.FAILED_TERMINAL;
            String errorClass = result.errorClass() == null ? "UNKNOWN" : result.errorClass();
            markExecutionFailed(shadowRun, attempt, status, errorClass, result.errorDetail());
            return failWork(row, errorClass, result.errorDetail(), now);
        }

        RcaTaskExecutor.AttemptArtifact artifact =
                result.artifact().orElseThrow(() -> new IllegalStateException(
                        "SUCCEEDED 结果缺 artifact（执行器契约违约）"));
        attempts.update(new RcaAttempt(attemptId, taskId, 1, 1L, owner,
                RcaAttemptStatus.SUCCEEDED, null, null, null, now, clock.now(),
                artifact.samplingFingerprint()));
        runs.update(withTerminal(shadowRun, RcaRunState.SUCCEEDED, null, clock.now()));

        Map<String, Object> conclusion = EngineComparisonRecorder.conclusionFromPackage(
                artifact.validationStatus(), artifact.totalTokens(), artifact.typedPackage());
        Outcome outcome = row.comparison()
                ? landComparison(row, shadowRunId, conclusion)
                : landCalibration(row, shadowRunId, conclusion);
        if (outcome == Outcome.CAS_LOST || outcome == Outcome.FAILED) {
            return outcome;
        }
        if (works.complete(row.id(), row.leaseOwner(), row.leaseEpoch(),
                artifact.totalTokens(), clock.now()) == 0) {
            // 租约过期被回收：本结果作废（V32 幂等面已兜重复账），重领者重新执行
            metrics.holmesShadowWork(Outcome.CAS_LOST.name());
            StructuredLog.event(log, "rca_holmes_shadow_work", Map.ofEntries(
                    Map.entry("shadow_key", row.shadowKey()),
                    Map.entry("outcome", Outcome.CAS_LOST.name())));
            return Outcome.CAS_LOST;
        }
        metrics.holmesShadowWork(outcome.name());
        StructuredLog.event(log, "rca_holmes_shadow_work", Map.ofEntries(
                Map.entry("shadow_key", row.shadowKey()),
                Map.entry("shadow_run_id", shadowRunId.toString()),
                Map.entry("outcome", outcome.name())));
        return outcome;
    }

    // ------------------------------------------------------------------ 分支

    /** 对照分支：影子 Holmes 结论 vs NATIVE 生产结论落 V32，随后幂等排队校准 */
    private Outcome landComparison(ShadowWorkRow row, UUID shadowRunId,
            Map<String, Object> holmesConclusion) {
        recorder.compareShadowHolmesNative(shadowRunId, row.nativeRunId(),
                SHADOW_EXEC_REF, holmesConclusion);
        works.enqueue(ShadowWorkRow.forEnqueue(
                CALIBRATION_KEY_PREFIX + row.nativeRunId(), "CALIBRATION",
                row.nativeRunId(), row.incidentId(), row.generation(),
                row.snapshotDigest(), row.maxAttempts()));
        return Outcome.COMPARISON_LANDED;
    }

    /** 校准分支：同 snapshot 双 Holmes（基线=对照行的影子结论）→ noise_baseline 落 V32 */
    private Outcome landCalibration(ShadowWorkRow row, UUID shadowRunId,
            Map<String, Object> calibrationConclusion) {
        Map<String, Object> baseline = baselineConclusion(row.nativeRunId());
        if (baseline == null) {
            return failWork(row, "BASELINE_MISSING", "缺基线对照行（对照分支未落账？）",
                    clock.now());
        }
        recorder.compareHolmesCalibration(row.nativeRunId(), shadowRunId,
                SHADOW_EXEC_REF, baseline, calibrationConclusion);
        return Outcome.CALIBRATION_LANDED;
    }

    /** 基线结论 = 对照期 V32 行（native 侧为真 NATIVE 生产结论的那行）的 holmesOutcome */
    private Map<String, Object> baselineConclusion(UUID nativeRunId) {
        List<EngineComparisonRepository.ComparisonRow> rows = comparisons.findByNativeRunId(nativeRunId);
        for (int i = rows.size() - 1; i >= 0; i--) {
            EngineComparisonRepository.ComparisonRow row = rows.get(i);
            Map<String, Object> nativeOutcome = row.nativeOutcome();
            if (SHADOW_EXEC_REF.equals(row.shadowExecRef()) && nativeOutcome != null
                    && "NATIVE".equals(nativeOutcome.get("engine"))) {
                return row.holmesOutcome();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 失败面

    private void markExecutionFailed(RcaRun shadowRun, RcaAttempt attempt,
            RcaAttemptStatus status, String errorClass, String errorDetail) {
        attempts.update(new RcaAttempt(attempt.id(), attempt.taskId(), attempt.attemptNo(),
                attempt.leaseEpoch(), owner, status, errorClass, null, truncate(errorDetail),
                attempt.startedAt(), clock.now(), null));
        runs.update(withTerminal(shadowRun, RcaRunState.FAILED, errorClass, clock.now()));
    }

    private Outcome failWork(ShadowWorkRow row, String errorClass, String detail, Instant now) {
        works.markFailed(row.id(), row.leaseOwner(), row.leaseEpoch(),
                truncate(errorClass + (detail == null ? "" : ": " + detail)), now);
        metrics.holmesShadowWork("FAILED");
        StructuredLog.event(log, "rca_holmes_shadow_work", Map.ofEntries(
                Map.entry("shadow_key", row.shadowKey()),
                Map.entry("error_class", String.valueOf(errorClass)),
                Map.entry("outcome", "FAILED")));
        return Outcome.FAILED;
    }

    private static RcaRun withTerminal(RcaRun run, RcaRunState state, String lastError,
            Instant now) {
        return new RcaRun(run.id(), run.incidentId(), run.generation(), run.trigger(),
                state, run.investigationHash(), run.createdAt(), now, run.startedAt(),
                now, lastError);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= ERROR_MAX ? value : value.substring(0, ERROR_MAX);
    }
}
