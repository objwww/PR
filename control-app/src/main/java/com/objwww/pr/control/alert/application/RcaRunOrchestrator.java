package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.ExecutionStatus;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.InvestigationResult;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.RcaAttemptStatus;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository;
import com.objwww.pr.control.alert.domain.repository.RcaAttemptRepository;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.RcaToolCallRepository;
import com.objwww.pr.control.alert.domain.repository.SchedulerSlotRepository;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.domain.statemachine.RcaRunStateMachine;
import com.objwww.pr.control.alert.domain.statemachine.RcaTaskStateMachine;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.infrastructure.observability.StructuredLog;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * run 生命周期与 {@code finishTask} 收尾单事务（§6.7 固定算法，评审 #5）：
 *
 * <pre>
 * requireCurrentLease(owner, epoch)        // 旧 epoch 晚到提交被拒（ST-A08，0 行栅栏）
 * persistAttemptResult; markTaskTerminal; releaseSlot
 * lock incident
 * if incident.status == RESOLVED:            clearRerun; finishRun; return
 * if rerun 条件成立(pending != run.investigationHash 材料变化):
 *     clearRerun; finishRun(SUCCEEDED); castRun(RERUN, pending); castTask
 * else: finishRun(SUCCEEDED); anchor pending → lastInvestigationHash
 * </pre>
 *
 * <p>失败路径：attempt 终态 FAILED_*；task 未耗尽 → RETRY_WAIT（ready_since 刷新，退避结束不插队 §6.2），
 * 耗尽/终态 → DEAD 且 run FAILED。run.generation 为 episode 代快照——RERUN 同代
 * （RESOLVED→FIRING 再现才 +1，IncidentStateMachine 语义；与 §6.7 伪代码 generation+1 的差异
 * 为伪代码笔误修正，锚定见 V7 注释"铸造时的 incident.generation 快照"）。
 *
 * <p>状态机接线清单（BA-11①/G0-06/G0-07，迁移矩阵唯一权威）：
 * <ul>
 *   <li>RcaRunStateMachine：markRunRunning（QUEUED→RUNNING）、finishTask 失败收尾
 *       （RUNNING/QUEUED→FAILED）与成功收尾（→SUCCEEDED）；</li>
 *   <li>RcaTaskStateMachine：finishTask 三分支（LEASED→DONE/RETRY_WAIT/DEAD）；</li>
 *   <li>仓储契约侧（SQL 条件迁移，不经本类）：claimNext（READY/RETRY_WAIT→LEASED，
 *       PostgresRcaTaskRepository）、heartbeat/requireCurrentLease（LEASED 租约原地续期，无状态迁移）。</li>
 * </ul>
 */
public class RcaRunOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RcaRunOrchestrator.class);

    /** finishTask 结果（worker 观测/测试断言） */
    public enum FinishOutcome {COMPLETED, RERUN_CAST, RESOLVED_SHORT_CIRCUIT, RETRY_SCHEDULED, DEAD, LEASE_REJECTED, STALE_GENERATION}

    private final RcaTaskRepository tasks;
    private final RcaRunRepository runs;
    private final RcaAttemptRepository attempts;
    private final RcaReportRepository reports;
    private final IncidentRepository incidents;
    private final SchedulerSlotRepository slots;
    private final InvestigationResultRepository investigationResults;
    private final RcaToolCallRepository toolCalls;
    private final ReportCompletedNotifier notifier;
    private final ArtifactStore artifacts;
    private final SlaPolicy sla;
    private final AlertClock clock;
    private final String slotScope;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AlertMetrics metrics;

    public RcaRunOrchestrator(RcaTaskRepository tasks,
                              RcaRunRepository runs,
                              RcaAttemptRepository attempts,
                              RcaReportRepository reports,
                              IncidentRepository incidents,
                              SchedulerSlotRepository slots,
                              InvestigationResultRepository investigationResults,
                              RcaToolCallRepository toolCalls,
                              ReportCompletedNotifier notifier,
                              ArtifactStore artifacts,
                              SlaPolicy sla,
                              AlertClock clock,
                              String slotScope,
                              AlertMetrics metrics) {
        this.tasks = Objects.requireNonNull(tasks);
        this.runs = Objects.requireNonNull(runs);
        this.attempts = Objects.requireNonNull(attempts);
        this.reports = Objects.requireNonNull(reports);
        this.incidents = Objects.requireNonNull(incidents);
        this.slots = Objects.requireNonNull(slots);
        this.investigationResults = Objects.requireNonNull(investigationResults);
        this.toolCalls = Objects.requireNonNull(toolCalls);
        this.notifier = Objects.requireNonNull(notifier);
        this.artifacts = Objects.requireNonNull(artifacts);
        this.sla = Objects.requireNonNull(sla);
        this.clock = Objects.requireNonNull(clock);
        this.slotScope = Objects.requireNonNull(slotScope);
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * 收尾（调用方以 TransactionOperations 包裹成单事务）。
     *
     * @param slotEpoch 领取 slot 时返回的槽 epoch；-1 表示未持槽（测试/无槽路径）
     */
    public FinishOutcome finishTask(RcaTask task, String owner, int slotNo, long slotEpoch,
                                    RcaTaskExecutor.ExecutionResult result,
                                    RcaAttempt startedAttempt) {
        Instant now = clock.now();

        // ST-A08：epoch 栅栏——旧 worker 晚到提交被拒，一行不写
        if (!tasks.requireCurrentLease(task.id(), owner, task.leaseEpoch())) {
            log.warn("task {} 旧租约提交被拒 owner={} epoch={}", task.id(), owner, task.leaseEpoch());
            return FinishOutcome.LEASE_REJECTED;
        }
        RcaTask fresh = tasks.findById(task.id()).orElseThrow();
        RcaRun run = runs.findByIdForUpdate(fresh.runId()).orElseThrow();

        // M4-07 generation fence（INV-AM4-4）：run 已出活跃集（被新代际取代/终止）＝
        // 旧 generation 结果——task 收敛 STALE，attempt 照实落终态，但报告/publication/
        // 通知零落档（merge 面栅栏），run 收尾跳过，不污染新 run。
        if (!run.state().isActive()) {
            attempts.update(finishAttempt(startedAttempt, result, now));
            RcaTaskStateMachine.requireTransition(fresh.state(), RcaTaskState.STALE);
            tasks.update(withTaskState(fresh, RcaTaskState.STALE, null, now));
            if (slotEpoch >= 0) {
                slots.release(slotScope, slotNo, owner, slotEpoch);
            }
            StructuredLog.event(log, "rca_task_decision", Map.ofEntries(
                    Map.entry("run_id", run.id().toString()),
                    Map.entry("task_id", task.id().toString()),
                    Map.entry("attempt_id", startedAttempt.id().toString()),
                    Map.entry("decision", FinishOutcome.STALE_GENERATION.name()),
                    Map.entry("run_state", run.state().name())));
            metrics.taskDecision(FinishOutcome.STALE_GENERATION.name());
            log.warn("task {} 旧代结果作废（run {} state={}），task→STALE 零落档",
                    task.id(), run.id(), run.state());
            return FinishOutcome.STALE_GENERATION;
        }

        // 1) attempt 终态
        attempts.update(finishAttempt(startedAttempt, result, now));

        // 1.5) M3-08 调查落档：Result 终态 CAS + tool_calls +（验证通过时）报告 +
        //      publication + outbox——同一收尾事务，验证失败同权落档（INV-AM3-7）
        result.artifact().ifPresent(a -> archiveArtifact(fresh, run, startedAttempt, result, a, now));

        // 2) task 终态 + slot 归还（INV-AM1-7 同收尾周期）
        boolean success = result.outcome() == RcaTaskExecutor.ExecutionResult.Outcome.SUCCEEDED;
        FinishOutcome outcome;
        if (success) {
            RcaTaskStateMachine.requireTransition(fresh.state(), RcaTaskState.DONE);
            tasks.update(withTaskState(fresh, RcaTaskState.DONE, null, now));
            outcome = FinishOutcome.COMPLETED;
        } else if (result.outcome() == RcaTaskExecutor.ExecutionResult.Outcome.FAILED_RETRYABLE
                && fresh.attemptCount() < fresh.maxAttempts()) {
            RcaTaskStateMachine.requireTransition(fresh.state(), RcaTaskState.RETRY_WAIT);
            Instant readyAt = now.plus(retryBackoff(fresh));
            // 重试时 deadline 必须重算（§6.2 退避后不插队：新 deadline = readyAt + sla）
            Instant newDeadline = sla.deadline(readyAt, fresh.priority());
            tasks.update(new RcaTask(fresh.id(), fresh.runId(), fresh.taskKey(), RcaTaskState.RETRY_WAIT,
                    fresh.priority(), readyAt, readyAt,
                    newDeadline, null, null, fresh.leaseEpoch(),
                    fresh.attemptCount(), fresh.maxAttempts(), fresh.createdAt(), now));
            outcome = FinishOutcome.RETRY_SCHEDULED;
        } else {
            RcaTaskStateMachine.requireTransition(fresh.state(), RcaTaskState.DEAD);
            tasks.update(withTaskState(fresh, RcaTaskState.DEAD, null, now));
            outcome = FinishOutcome.DEAD;
        }

        // M3-27/28：task 决策结构化事件 + 指标——decision 标签为 FinishOutcome 封闭枚举，无 UUID 标签
        StructuredLog.event(log, "rca_task_decision", Map.ofEntries(
                Map.entry("run_id", run.id().toString()),
                Map.entry("task_id", task.id().toString()),
                Map.entry("attempt_id", startedAttempt.id().toString()),
                Map.entry("decision", outcome.name()),
                Map.entry("latency_ms", Duration.between(startedAttempt.startedAt(), now).toMillis())));
        metrics.taskDecision(outcome.name());

        if (slotEpoch >= 0) {
            slots.release(slotScope, slotNo, owner, slotEpoch);
        }

        // 3) run 收尾 + rerun 判定（§6.7）——run 行已在收尾事务开头锁定
        if (run.state() != RcaRunState.QUEUED && run.state() != RcaRunState.RUNNING) {
            log.warn("run {} 已非活跃，跳过收尾 state={}", run.id(), run.state());
            return outcome;
        }

        if (!success) {
            boolean runFailed = outcome == FinishOutcome.DEAD;
            if (runFailed) {
                RcaRunStateMachine.requireTransition(run.state(), RcaRunState.FAILED);
            }
            runs.update(withRunState(run, runFailed ? RcaRunState.FAILED : run.state(), now,
                    result.errorClass()));
            if (runFailed) {
                clearIncidentRunPointer(run, now);
            }
            return outcome;
        }

        RcaRunStateMachine.requireTransition(run.state(), RcaRunState.SUCCEEDED);
        runs.update(withRunState(run, RcaRunState.SUCCEEDED, now, null));
        Incident incident = incidents.findByIdForUpdate(run.incidentId()).orElseThrow();

        // 分支 1：告警已恢复——清 rerun 线索，不再调查（调查报告已留存）
        if (incident.status() == com.objwww.pr.control.alert.domain.model.IncidentStatus.RESOLVED) {
            incidents.update(withIncidentRunEnd(incident, incident.lastInvestigationHash(),
                    null, null, now));
            return FinishOutcome.RESOLVED_SHORT_CIRCUIT;
        }

        // 分支 2：调查期间材料变化（pending != 本轮快照）→ 清 pending、铸 RERUN
        Digest pending = incident.pendingInvestigationHash();
        boolean materialChanged = pending != null && !pending.equals(run.investigationHash());
        if (materialChanged) {
            incidents.update(withIncidentRunEnd(incident, incident.lastInvestigationHash(),
                    null, null, now));
            castRunAndTask(incident, pending, fresh.priority(), now);
            log.info("incident {} 材料变化，铸 RERUN run", incident.id());
            return FinishOutcome.RERUN_CAST;
        }

        // 分支 3：材料未变——锚定本轮材料，结束
        Digest anchor = pending != null ? pending : run.investigationHash();
        incidents.update(withIncidentRunEnd(incident, anchor, null, null, now));
        return outcome;
    }

    /** 铸下一轮 RERUN（generation 同 episode 代；priority 继承刚完成的 task；uq 兜底并发双铸） */
    private void castRunAndTask(Incident incident, Digest materialHash, int priority, Instant now) {
        RcaRun run = new RcaRun(UUID.randomUUID(), incident.id(), incident.generation(),
                RunTrigger.RERUN, RcaRunState.QUEUED, materialHash, now, now, null, null, null);
        runs.insert(run);
        RcaTask task = new RcaTask(UUID.randomUUID(), run.id(), RcaTask.HOLMES_INVESTIGATE,
                RcaTaskState.READY, priority, now, now, sla.deadline(now, priority),
                null, null, 0, 0, 3, now, now);
        tasks.insert(task);
        incidents.update(withIncidentRunEnd(incident, incident.lastInvestigationHash(),
                materialHash, run.id(), now));
    }

    // ------------------------------------------------------------------ 行构造辅助

    /**
     * 调查落档（M3-08，收尾事务内）：Result 终态 CAS + tool_calls 落表（栅栏直挂）+
     * 结构验证通过时报告 + publication(READY) + outbox 原子写入。
     * 执行失败与结构失败同权落档——验证失败不再是"零落档误判超时"（INV-AM3-7）。
     */
    private void archiveArtifact(RcaTask task, RcaRun run, RcaAttempt attempt,
                                 RcaTaskExecutor.ExecutionResult result,
                                 RcaTaskExecutor.AttemptArtifact artifact, Instant now) {
        ExecutionStatus execution = result.outcome() == RcaTaskExecutor.ExecutionResult.Outcome.SUCCEEDED
                ? ExecutionStatus.SUCCEEDED
                : "TIMEOUT".equals(result.errorClass()) ? ExecutionStatus.TIMEOUT
                : ExecutionStatus.FAILED;
        boolean validated = artifact.validationStatus() == ValidationStatus.STRUCTURE_VALIDATED;

        // CAS 落档脱敏原文（内容寻址以脱敏文本自身 digest；失败不阻断——digest 已在行上）
        String rawRef = null;
        if (artifact.rawText() != null && !artifact.rawText().isEmpty()) {
            Digest redactedDigest = Digest.sha256Of(artifact.rawText());
            try {
                rawRef = artifacts.putIfAbsent(redactedDigest,
                        artifact.rawText().getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                log.warn("raw 落 CAS 失败（digest 仍可对账）attempt={}", attempt.id(), e);
            }
        }

        investigationResults.finishTerminal(new InvestigationResult(
                attempt.id(), attempt.id(), run.id(), run.generation(), artifact.schemaVersion(),
                execution, artifact.validationStatus(),
                artifact.validationErrors().isEmpty() ? null : artifact.validationErrors(),
                validated ? artifact.packageJson() : null,
                rawRef, artifact.rawDigest(), artifact.payloadDigest(), artifact.model(),
                usageJson(artifact), null, now));

        if (!artifact.toolCalls().isEmpty()) {
            toolCalls.insertAll(artifact.toolCalls());
        }

        if (validated) {
            UUID reportId = UUID.randomUUID();
            reports.insert(new RcaReport(reportId, run.id(), attempt.id(), artifact.schemaVersion(),
                    artifact.validationStatus(), artifact.validationErrors(),
                    artifact.packageJson(), artifact.rawText(), artifact.model(),
                    artifact.promptTokens(), artifact.completionTokens(), artifact.totalTokens(),
                    artifact.usageMissing(), now));
            PayloadFields fields = payloadFields(artifact);
            notifier.onReportValidated(reportId, run.id(), attempt.id(),
                    fields.summary(), fields.component(), fields.faultType(), fields.reasonCode(),
                    fields.impact(), fields.remediation(), now);
        }
    }

    /** usage → jsonb 文本（usage_missing 时为空，§6.2 冻结语义） */
    private static String usageJson(RcaTaskExecutor.AttemptArtifact artifact) {
        if (artifact.usageMissing()) {
            return null;
        }
        return "{\"prompt_tokens\":" + artifact.promptTokens()
                + ",\"completion_tokens\":" + artifact.completionTokens()
                + ",\"total_tokens\":" + artifact.totalTokens() + "}";
    }

    /** 通知白名单字段提取：v2 取类型化三元组；v1 自由文本根因并入 summary（legacy 路径） */
    private PayloadFields payloadFields(RcaTaskExecutor.AttemptArtifact artifact) {
        try {
            JsonNode pkg = mapper.readTree(artifact.packageJson());
            String summary = pkg.path("summary").asText("");
            JsonNode rootCause = pkg.path("root_cause");
            if (rootCause.isObject()) {
                return new PayloadFields(summary,
                        rootCause.path("component").asText(""),
                        rootCause.path("fault_type").asText(""),
                        rootCause.path("reason_code").asText(""),
                        pkg.path("impact").asText(""), pkg.path("remediation").asText(""));
            }
            return new PayloadFields(summary + " 根因: " + rootCause.asText(""),
                    "", "", "", pkg.path("impact").asText(""), pkg.path("remediation").asText(""));
        } catch (Exception e) {
            return new PayloadFields("", "", "", "", "", "");
        }
    }

    private record PayloadFields(String summary, String component, String faultType,
                                 String reasonCode, String impact, String remediation) {
    }

    private RcaAttempt finishAttempt(RcaAttempt started, RcaTaskExecutor.ExecutionResult result,
                                     Instant now) {
        RcaAttemptStatus status = switch (result.outcome()) {
            case SUCCEEDED -> RcaAttemptStatus.SUCCEEDED;
            case FAILED_RETRYABLE -> RcaAttemptStatus.FAILED_RETRYABLE;
            case FAILED_TERMINAL -> RcaAttemptStatus.FAILED_TERMINAL;
        };
        return new RcaAttempt(started.id(), started.taskId(), started.attemptNo(),
                started.leaseEpoch(), started.workerId(), status,
                result.errorClass(), result.errorCode(), result.errorDetail(),
                started.startedAt(), now);
    }

    private static RcaTask withTaskState(RcaTask t, RcaTaskState state,
                                         Instant availableAt, Instant now) {
        return new RcaTask(t.id(), t.runId(), t.taskKey(), state, t.priority(),
                availableAt != null ? availableAt : t.availableAt(), t.readySince(),
                t.deadlineAt(), t.leaseOwner(), t.leaseUntil(), t.leaseEpoch(),
                t.attemptCount(), t.maxAttempts(), t.createdAt(), now);
    }

    private static RcaRun withRunState(RcaRun r, RcaRunState state, Instant now, String error) {
        boolean starting = state == RcaRunState.RUNNING;
        return new RcaRun(r.id(), r.incidentId(), r.generation(), r.trigger(), state,
                r.investigationHash(), r.createdAt(), now,
                starting ? now : r.startedAt(),
                state.isActive() ? null : now,
                error);
    }

    private static Incident withIncidentRunEnd(Incident i, Digest lastInvestigation,
                                               Digest pending, UUID currentRunId, Instant now) {
        return new Incident(i.id(), i.incidentKey(), i.status(), i.generation(),
                i.episodeStartedAt(), i.lastFiringStartsAt(), i.resolvedAt(),
                lastInvestigation, pending,
                i.receivedCount(), i.distinctEventCount(), i.notificationCount(),
                currentRunId, i.firstSeenAt(), i.lastEventAt(), i.createdAt(), now);
    }

    private void clearIncidentRunPointer(RcaRun run, Instant now) {
        incidents.findByIdForUpdate(run.incidentId()).ifPresent(incident ->
                incidents.update(withIncidentRunEnd(incident, incident.lastInvestigationHash(),
                        null, null, now)));
    }

    private static Duration retryBackoff(RcaTask task) {
        // 朴素固定退避：attempt 越多退避越长（1min/2min/4min 封顶 5min，DP 观测修正）
        return Duration.ofMinutes(Math.min(1L << Math.max(0, task.attemptCount() - 1), 5));
    }

    /** task 开跑前置：run QUEUED→RUNNING（幂等；首个领取者置位；经状态机校验，G0-06） */
    public boolean markRunRunning(RcaRun run, Instant now) {
        if (run.state() == RcaRunState.QUEUED) {
            RcaRunStateMachine.requireTransition(run.state(), RcaRunState.RUNNING);
            runs.update(withRunState(run, RcaRunState.RUNNING, now, null));
            return true;
        }
        return false;
    }

    /** 观测辅助（自检/测试） */
    public Optional<RcaRun> activeRunOf(UUID incidentId) {
        return runs.findActiveByIncidentId(incidentId);
    }
}
