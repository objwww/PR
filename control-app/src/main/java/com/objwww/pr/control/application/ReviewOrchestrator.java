package com.objwww.pr.control.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.domain.model.PRRevision;
import com.objwww.pr.control.domain.model.PRSubject;
import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.model.ReviewFinding;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.model.RunMode;
import com.objwww.pr.control.domain.model.RunStep;
import com.objwww.pr.control.domain.model.StepAttempt;
import com.objwww.pr.control.domain.model.WorkItem;
import com.objwww.pr.control.domain.repository.PRRevisionRepository;
import com.objwww.pr.control.domain.repository.PRSubjectRepository;
import com.objwww.pr.control.domain.repository.ReviewFindingRepository;
import com.objwww.pr.control.domain.repository.ReviewRunRepository;
import com.objwww.pr.control.domain.repository.RunStepRepository;
import com.objwww.pr.control.domain.repository.StepAttemptRepository;
import com.objwww.pr.control.domain.repository.WorkItemRepository;
import com.objwww.pr.control.domain.review.ReviewFindingDraft;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.control.domain.service.RevisionService;
import com.objwww.pr.control.domain.statemachine.RunStateMachine;
import com.objwww.pr.control.domain.statemachine.StepStateMachine;
import com.objwww.pr.shared.AttemptStatus;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.FindingState;
import com.objwww.pr.shared.OperationId;
import com.objwww.pr.shared.OutboxCommand;
import com.objwww.pr.shared.RevisionFingerprint;
import com.objwww.pr.shared.RunState;
import com.objwww.pr.shared.StepState;
import com.objwww.pr.shared.WorkItemState;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * T1/T2 事务脚本的编排者（application，§3 ReviewOrchestrator）。不写 GitHub。
 *
 * <p>T1 建 Run（{@link #runIntake}）：upsert PRSubject → fingerprint 复用/插入 PRRevision（I12）
 * → 换届（current_revision 切换 + publication_epoch+1 + 旧 Run SUPERSEDED + 取消未完成 WorkItem，
 * 同事务）→ insert Run + 首个 Step + WorkItem → append 事件。任一步失败整笔回滚。
 * run_key 唯一冲突（webhook 重投，B-3）以 DuplicateKeyException 上抛，由 IntakeService 捕获幂等返回。
 *
 * <p>T2 完成 Step（{@link #completeStep}）：租约栅栏（I11）→ Attempt/Step 状态推进
 * → REVIEW 成功时登记 findings + OutboxWriter 插 CREATE_CHECK/PUBLISH_REVIEW（带依赖边，
 * 每条命令各领一次 sequence/epoch）→ append STEP_RESULT/PUBLICATION_REQUESTED → COMMIT。
 * <p>刻意不加组件注解：bean 由 infrastructure/config/ReviewFlowConfig（docker profile）装配，
 * 默认 profile 空跑不装（无 DataSource 时 repository 依赖无从满足）。
 * @Transactional 经配置类 @Bean 注册后由代理生效，与组件扫描等价。
 */
public class ReviewOrchestrator {

    public static final String STEP_TYPE_REVIEW = "REVIEW";
    public static final String WORK_TYPE_REVIEW = "REVIEW";
    public static final String STEP_KEY_REVIEW = "review";
    public static final int DEFAULT_STEP_MAX_ATTEMPTS = 3;
    public static final int DEFAULT_STEP_TIMEOUT_SECONDS = 1800;
    /** RETRY_WAIT 线性退避基数：available_at = now + BASE * attemptCount */
    public static final long RETRY_BACKOFF_BASE_SECONDS = 30;
    private static final String PRODUCER = "control";

    private final PRSubjectRepository subjectRepository;
    private final PRRevisionRepository revisionRepository;
    private final ReviewRunRepository runRepository;
    private final RunStepRepository stepRepository;
    private final WorkItemRepository workItemRepository;
    private final StepAttemptRepository attemptRepository;
    private final ReviewFindingRepository findingRepository;
    private final RevisionService revisionService;
    private final ExecutionLedger ledger;
    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    public ReviewOrchestrator(PRSubjectRepository subjectRepository,
                              PRRevisionRepository revisionRepository,
                              ReviewRunRepository runRepository,
                              RunStepRepository stepRepository,
                              WorkItemRepository workItemRepository,
                              StepAttemptRepository attemptRepository,
                              ReviewFindingRepository findingRepository,
                              RevisionService revisionService,
                              ExecutionLedger ledger,
                              OutboxWriter outboxWriter,
                              ObjectMapper objectMapper) {
        this.subjectRepository = Objects.requireNonNull(subjectRepository);
        this.revisionRepository = Objects.requireNonNull(revisionRepository);
        this.runRepository = Objects.requireNonNull(runRepository);
        this.stepRepository = Objects.requireNonNull(stepRepository);
        this.workItemRepository = Objects.requireNonNull(workItemRepository);
        this.attemptRepository = Objects.requireNonNull(attemptRepository);
        this.findingRepository = Objects.requireNonNull(findingRepository);
        this.revisionService = Objects.requireNonNull(revisionService);
        this.ledger = Objects.requireNonNull(ledger);
        this.outboxWriter = Objects.requireNonNull(outboxWriter);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    // ------------------------------------------------------------------ T1

    /**
     * T1 建 Run（整笔事务，任一步失败整体回滚）。
     * run_key 唯一约束冲突 = webhook 重投（B-3）：DuplicateKeyException 上抛，调用方幂等处理。
     */
    @Transactional
    public ReviewRun runIntake(IntakeCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        Instant now = Instant.now();

        // 1) upsert PRSubject（投影字段刷新；epoch/sequence 不经 save 改写）
        PRSubject subject = subjectRepository
                .findByRepositoryAndPrNumber(cmd.repositoryId(), cmd.prNumber())
                .map(existing -> {
                    existing.refreshPrState(cmd.prState(), cmd.draft(), cmd.merged(), now);
                    subjectRepository.save(existing);
                    return existing;
                })
                .orElseGet(() -> {
                    PRSubject created = new PRSubject(UUID.randomUUID(),
                            cmd.installationId(), cmd.repositoryId(), cmd.repositoryFullName(),
                            cmd.prNumber(), cmd.prState(), cmd.draft(), cmd.merged(),
                            null, cmd.policyVersion(), 0, 1, 0, null, now, 0, 0, now, now);
                    subjectRepository.save(created);
                    return created;
                });

        // 2) fingerprint 复用/插入 PRRevision（I12：同 fingerprint 复用行，digest 已就绪）
        RevisionFingerprint fingerprint = revisionService.revisionFingerprint(
                cmd.repositoryId(), cmd.prNumber(), cmd.headSha(), cmd.baseSha(),
                cmd.mergeBaseSha(), cmd.diffDigest());
        PRRevision revision = revisionRepository.findByFingerprint(subject.getId(), fingerprint)
                .orElseGet(() -> {
                    PRRevision created = new PRRevision(UUID.randomUUID(), subject.getId(),
                            cmd.headSha(), cmd.baseRef(), cmd.baseSha(), cmd.mergeBaseSha(),
                            cmd.diffDigest(), cmd.sourceSnapshotDigest(), fingerprint, now, now);
                    revisionRepository.insert(created);
                    return created;
                });

        // 3) 换届判定：revision 或 policy 变化 → 同事务 epoch+1 + 旧世代作废
        boolean revisionChanged = !revision.getId().equals(subject.getCurrentRevisionId());
        boolean policyChanged = !subject.getCurrentPolicyVersion().equals(cmd.policyVersion());
        List<ReviewRun> supersededRuns = List.of();
        if (revisionChanged || policyChanged) {
            subjectRepository.switchRevisionAndBumpEpoch(
                    subject.getId(), revision.getId(), cmd.policyVersion(), now);
            List<ReviewRun> active = runRepository.findActiveByPrSubjectId(subject.getId());
            supersededRuns = new ArrayList<>(active.size());
            for (ReviewRun old : active) {
                old.transitionTo(RunState.SUPERSEDED, now);
                runRepository.save(old);
                workItemRepository.cancelActiveByRunId(old.getId());
                supersededRuns.add(old);
            }
            // Control 不动 outbox：旧世代 PENDING 命令由 Publisher 兜底扫描级联（v2.1 修订三）
        }

        // 4) insert Run（run_key = hash(revision+policy+prompt+toolset+trigger)，B-3 幂等兜底）
        Digest runKey = revisionService.runKey(revision.getId(), cmd.policyVersion(),
                cmd.promptVersion(), cmd.toolsetVersion(), cmd.triggerKey());
        UUID runId = UUID.randomUUID();
        ReviewRun run = new ReviewRun(runId, revision.getId(), null, runId,
                runKey, cmd.triggerKey(), RunMode.NORMAL,
                cmd.policyVersion(), cmd.promptVersion(), cmd.toolsetVersion(),
                null, RunState.CREATED, false,
                null, null, null, 0, now, now, null);
        runRepository.save(run);

        // 5) 首个 RunStep（REVIEW）+ WorkItem（READY）
        RunStep step = new RunStep(UUID.randomUUID(), runId, null,
                STEP_KEY_REVIEW, OperationId.random(), "root",
                STEP_TYPE_REVIEW, StepState.READY, 1,
                cmd.diffDigest(), null,
                DEFAULT_STEP_MAX_ATTEMPTS, DEFAULT_STEP_TIMEOUT_SECONDS, 0, now, now, null);
        stepRepository.save(step);
        WorkItem workItem = new WorkItem(UUID.randomUUID(), runId, step.getId(),
                WORK_TYPE_REVIEW, WorkItemState.READY, 0, now,
                null, null, 0, 0, DEFAULT_STEP_MAX_ATTEMPTS, now, now);
        workItemRepository.save(workItem);

        // 6) append 事件（换届：REVISION_INVALIDATED 挂在每个被作废旧 Run 自己的流上，
        //    correlation 指回新 run——Projector 对非终态 Run 将其投影为 SUPERSEDED；
        //    T17 实证：挂新 run 流会让 fold 新 run 时先撞"缺 RUN_CREATED 前置"，
        //    即使挪到 RUN_CREATED 之后也会把新 run 投影成 SUPERSEDED 再撞终态迁移）
        if (!supersededRuns.isEmpty()) {
            for (ReviewRun old : supersededRuns) {
                // payload 预序列化为 JSON 友好类型（UUID → String），保证账本 jsonb 往返保真
                ledger.append(ledger.newEvent(old.getId(), old.getPrRevisionId(), null, null,
                        ExecutionEventType.REVISION_INVALIDATED, null, runId, PRODUCER,
                        Map.of("new_run_id", runId.toString(),
                                "new_revision_id", revision.getId().toString(),
                                "policy_version", cmd.policyVersion())));
            }
        }
        ledger.append(ledger.newEvent(runId, revision.getId(), null, null,
                ExecutionEventType.RUN_CREATED, null, runId, PRODUCER,
                Map.of("run_key", runKey.value(),
                        "trigger_key", cmd.triggerKey(),
                        "head_sha", cmd.headSha(),
                        "revision_fingerprint", fingerprint.value())));

        // 7) LWW 水印推进（M1-T05，I10/CT-14）：GREATEST 条件更新防并发回退；
        //    远端缺 updated_at 时不覆盖（EX-18）
        advanceWatermark(subject.getId(), cmd.eventUpdatedAt(), now);
        return run;
    }

    /** B-3 幂等回读：subject → revision（fingerprint）→ run_key → Run。T1 冲突捕获后用 */
    @Transactional(readOnly = true)
    public Optional<ReviewRun> findExistingRun(IntakeCommand cmd) {
        return subjectRepository.findByRepositoryAndPrNumber(cmd.repositoryId(), cmd.prNumber())
                .flatMap(subject -> revisionRepository.findByFingerprint(subject.getId(),
                        revisionService.revisionFingerprint(cmd.repositoryId(), cmd.prNumber(),
                                cmd.headSha(), cmd.baseSha(), cmd.mergeBaseSha(), cmd.diffDigest())))
                .flatMap(revision -> runRepository.findByRunKey(
                        revisionService.runKey(revision.getId(), cmd.policyVersion(),
                                cmd.promptVersion(), cmd.toolsetVersion(), cmd.triggerKey())));
    }

    // ------------------------------------------------------------------ T06：draft 廉价预检 / T-close / T-draft

    /**
     * Draft 廉价预检（M1-T06，方案 §4.4 决策表第一行，I11/ST-12）：
     * 远端确认 open+draft 且无需换届时——**只刷投影（state/draft/merged）+ 推进水印**，
     * 不 T0、不建 Run、不插 Outbox、不调模型（每次 draft push 的成本 = 一次权威读 GET）。
     *
     * <p>诚实边界（账本事件缺口）：方案 §4.4 写"刷投影 + 事件"，但 V1 schema 的
     * execution_event.review_run_id / pr_revision_id 为 NOT NULL + FK，draft 预检
     * 恰恰没有 Run 可挂——落账本需要 schema 变更（V4 决策）。本版本以 inbox 行
     * （PROCESSED）+ 投影字段充当审计（INC-16 已由 inbox 闭环），不改 V3。
     */
    @Transactional
    public void applyDraftPrecheck(ProjectionSyncCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        Instant now = Instant.now();
        PRSubject subject = upsertSubjectProjection(cmd, now);
        advanceWatermark(subject.getId(), cmd.eventUpdatedAt(), now);
    }

    /**
     * T-close（M1-T06，方案 §4.4，修正 #5 / I15）：远端确认 closed/merged（或 404 经
     * sanity 读确认 repo 可读）时，同事务内：投影 CLOSED(+merged) + publication_epoch+1
     * + 在途 active Run → SUPERSEDED（取消其未完成 WorkItem）+ 账本事件。
     *
     * <p>epoch+1 是防"在已关闭 PR 上发出评论"的唯一闸门：Publisher 兜底扫描
     * （sweepStaleEpoch）只废弃 epoch 落后的 PENDING/RETRY_WAIT 命令（ST-19）；
     * IN_FLIGHT 不级联，继续走 M0 reconcile（v2.1 修订三，B-R5）。
     *
     * <p>幂等：投影已是目标态且无在途 Run（崩溃重放/重投）→ 不再 bump，只刷投影+水印。
     */
    @Transactional
    public void closeGeneration(ProjectionSyncCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        Instant now = Instant.now();
        Optional<PRSubject> existing = subjectRepository.findByRepositoryAndPrNumber(
                cmd.repositoryId(), cmd.prNumber());
        if (existing.isPresent() && existing.get().getState() == PrSubjectState.CLOSED
                && existing.get().isMerged() == cmd.merged()
                && runRepository.findActiveByPrSubjectId(existing.get().getId()).isEmpty()) {
            // 重放幂等：已 CLOSED 且无在途 Run → 不重复 bump epoch（否则重投会多次换届）
            PRSubject subject = existing.get();
            subject.refreshPrState(PrSubjectState.CLOSED, cmd.draft(), cmd.merged(), now);
            subjectRepository.save(subject);
            advanceWatermark(subject.getId(), cmd.eventUpdatedAt(), now);
            return;
        }
        PRSubject subject = upsertSubjectProjection(cmd, now);
        bumpEpochAndSupersedeActiveRuns(subject, cmd, now, "PR_CLOSED");
    }

    /**
     * T-draft（M1-T06，方案 §4.4）：远端确认 draft=true 且需换届（converted_to_draft 事件，
     * 或远端已 draft 但仍有在途 Run——webhook 丢失场景由 Reader 升级而来）时，
     * 同事务内：投影 draft=true + publication_epoch+1 + 在途 Run SUPERSEDED + 账本事件。
     * 幂等语义同 {@link #closeGeneration}。
     */
    @Transactional
    public void convertToDraftGeneration(ProjectionSyncCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        Instant now = Instant.now();
        Optional<PRSubject> existing = subjectRepository.findByRepositoryAndPrNumber(
                cmd.repositoryId(), cmd.prNumber());
        if (existing.isPresent() && existing.get().isDraft()
                && existing.get().getState() == PrSubjectState.OPEN
                && runRepository.findActiveByPrSubjectId(existing.get().getId()).isEmpty()) {
            PRSubject subject = existing.get();
            subject.refreshPrState(PrSubjectState.OPEN, true, false, now);
            subjectRepository.save(subject);
            advanceWatermark(subject.getId(), cmd.eventUpdatedAt(), now);
            return;
        }
        PRSubject subject = upsertSubjectProjection(cmd, now);
        bumpEpochAndSupersedeActiveRuns(subject, cmd, now, "CONVERTED_TO_DRAFT");
    }

    /**
     * T-reopen（INC-26，方案 §4.4/I15/ST-20）：reopened 是状态语义换届——同事务内
     * 投影 OPEN + publication_epoch+1 + 在途 Run SUPERSEDED（防御：close 漏网的旧世代
     * 在途 Run），随后由调用方走全量 T0/T1 建新 Run。
     *
     * <p>为什么 revision 未变也要 bump：Publisher 的 fence 只认 epoch；若 reopen 不换届，
     * close 时代与 reopen 时代共享同一 epoch，close 前滞留的同 epoch 命令就可能在新世代
     * 被当作合法命令发出（ST-20：旧世代命令 fence 拦截）。
     *
     * <p>幂等：投影已是 OPEN 非 draft（崩溃重放/重投/duplicate reopened）→ 不重复 bump，
     * 只刷投影+水印；新 Run 的幂等由 T1 的收敛判定与 uq_review_run_active_gen 兜底。
     */
    @Transactional
    public void reopenGeneration(ProjectionSyncCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        Instant now = Instant.now();
        Optional<PRSubject> existing = subjectRepository.findByRepositoryAndPrNumber(
                cmd.repositoryId(), cmd.prNumber());
        if (existing.isPresent() && existing.get().getState() == PrSubjectState.OPEN
                && !existing.get().isDraft()) {
            // 重放幂等：已 OPEN 非 draft → 不重复 bump epoch（否则重投会多次换届）
            PRSubject subject = existing.get();
            subject.refreshPrState(PrSubjectState.OPEN, false, false, now);
            subjectRepository.save(subject);
            advanceWatermark(subject.getId(), cmd.eventUpdatedAt(), now);
            return;
        }
        PRSubject subject = upsertSubjectProjection(cmd, now);
        bumpEpochAndSupersedeActiveRuns(subject, cmd, now, "PR_REOPENED");
    }

    /** 投影 upsert（T06 三路径共用）：存在则 refreshPrState，不存在则建最小投影行（无 revision/Run） */    private PRSubject upsertSubjectProjection(ProjectionSyncCommand cmd, Instant now) {
        return subjectRepository.findByRepositoryAndPrNumber(cmd.repositoryId(), cmd.prNumber())
                .map(s -> {
                    s.refreshPrState(cmd.prState(), cmd.draft(), cmd.merged(), now);
                    subjectRepository.save(s);
                    return s;
                })
                .orElseGet(() -> {
                    PRSubject created = new PRSubject(UUID.randomUUID(),
                            cmd.installationId(), cmd.repositoryId(), cmd.repositoryFullName(),
                            cmd.prNumber(), cmd.prState(), cmd.draft(), cmd.merged(),
                            null, cmd.policyVersion(), 0, 1, 0, null, now, 0, 0, now, now);
                    subjectRepository.save(created);
                    return created;
                });
    }

    /**
     * T-close/T-draft 换届核心（照 T1 风格，同事务）：投影 + epoch+1（一句 UPDATE 原子，
     * I15）→ 在途 Run 逐个 SUPERSEDED + 取消未完成 WorkItem → REVISION_INVALIDATED 落账
     * （挂被作废旧 Run 自己的流，correlation 自指——T-close/T-draft 没有新 Run 可指；
     * Projector 对非终态 Run fold 出 SUPERSEDED，投影一致性保持）。
     * Control 不动 outbox：旧世代 PENDING 命令由 Publisher sweepStaleEpoch 级联（ST-19）。
     */
    private void bumpEpochAndSupersedeActiveRuns(PRSubject subject, ProjectionSyncCommand cmd,
                                                 Instant now, String reason) {
        subjectRepository.refreshStateAndBumpEpoch(subject.getId(),
                cmd.prState(), cmd.draft(), cmd.merged(), now);
        long newEpoch = subject.getPublicationEpoch() + 1;
        List<ReviewRun> active = runRepository.findActiveByPrSubjectId(subject.getId());
        for (ReviewRun old : active) {
            old.transitionTo(RunState.SUPERSEDED, now);
            runRepository.save(old);
            workItemRepository.cancelActiveByRunId(old.getId());
            ledger.append(ledger.newEvent(old.getId(), old.getPrRevisionId(), null, null,
                    ExecutionEventType.REVISION_INVALIDATED, null, old.getId(), PRODUCER,
                    Map.of("reason", reason,
                            "publication_epoch", newEpoch,
                            "pr_number", subject.getPrNumber())));
        }
        advanceWatermark(subject.getId(), cmd.eventUpdatedAt(), now);
    }

    /** 水印推进（I10/CT-14）：null 不覆盖（EX-18）；GREATEST 语义在仓储实现侧 */
    private void advanceWatermark(UUID subjectId, Instant eventUpdatedAt, Instant now) {
        if (eventUpdatedAt != null) {
            subjectRepository.advanceWatermarkIfNewer(subjectId, eventUpdatedAt, now);
        }
    }



    // ------------------------------------------------------------------ T2

    /**
     * T2 完成 Step（整笔事务）。对照 §6.1 T2：租约校验 → Attempt/Step 状态推进
     * → （REVIEW 成功）findings + 领 sequence/epoch + 插 outbox 命令与依赖 → append 事件 → COMMIT。
     */
    @Transactional
    public T2Outcome completeStep(StepCompletion completion) {
        Objects.requireNonNull(completion, "completion");
        Instant now = Instant.now();

        WorkItem workItem = workItemRepository.findById(completion.workItemId())
                .orElseThrow(() -> new IllegalArgumentException("work_item 不存在: " + completion.workItemId()));
        RunStep step = stepRepository.findById(completion.stepId())
                .orElseThrow(() -> new IllegalArgumentException("run_step 不存在: " + completion.stepId()));
        StepAttempt attempt = attemptRepository.findById(completion.attemptId())
                .orElseThrow(() -> new IllegalArgumentException("step_attempt 不存在: " + completion.attemptId()));
        ReviewRun run = runRepository.findById(step.getReviewRunId())
                .orElseThrow(() -> new IllegalStateException("review_run 不存在: " + step.getReviewRunId()));

        boolean success = completion.outcome() instanceof StepOutcome.Succeeded;
        StepOutcome.Failed failure = completion.outcome() instanceof StepOutcome.Failed f ? f : null;
        boolean retryWithBudgetLeft = failure != null && failure.retryable()
                && workItem.getAttemptCount() < workItem.getMaxAttempts();

        // 1) 租约栅栏（I11）：晚到结果 UPDATE 0 行 → 记 STALE，不推进 Step/Run
        WorkItemState workItemTarget = success ? WorkItemState.DONE
                : retryWithBudgetLeft ? WorkItemState.RETRY_WAIT : WorkItemState.DEAD;

        // M3（v1.3 冻结公式）：retryAt = max(线性退避, failure.notBefore)——
        // Retry-After 只抬高下限，不得取消 attempt 层退避节奏（防 Provider 短头值热循环）
        Instant retryAt = null;
        if (workItemTarget == WorkItemState.RETRY_WAIT) {
            Instant linear = now.plusSeconds(RETRY_BACKOFF_BASE_SECONDS * Math.max(1, workItem.getAttemptCount()));
            retryAt = failure.notBefore() != null && failure.notBefore().isAfter(linear)
                    ? failure.notBefore() : linear;
        }

        boolean leaseCurrent = workItemRepository.transitionIfLeaseCurrent(completion.workItemId(),
                completion.leaseOwner(), completion.leaseEpoch(), workItemTarget, retryAt);
        if (!leaseCurrent) {
            attempt.transitionTo(AttemptStatus.STALE, now);
            attemptRepository.save(attempt);
            return T2Outcome.STALE_IGNORED;
        }

        // 2) Attempt 终态
        if (success) {
            StepOutcome.Succeeded s = (StepOutcome.Succeeded) completion.outcome();
            attempt.succeedWith(s.outputArtifactDigest(), now);
        } else {
            attempt.failWith(failure.retryable() ? AttemptStatus.FAILED_RETRYABLE
                            : AttemptStatus.FAILED_TERMINAL,
                    failure.errorClass(), failure.errorCode(), failure.errorDetail(), now);
        }
        attemptRepository.save(attempt);

        // 3) Step 状态推进（防御：worker 尚未置 RUNNING 时先补 READY→RUNNING）
        if (step.getState() == StepState.READY) {
            step.transitionTo(StepState.RUNNING, now);
        }
        if (success) {
            StepOutcome.Succeeded s = (StepOutcome.Succeeded) completion.outcome();
            step.completeWithOutput(s.outputArtifactDigest(), now);
        } else if (retryWithBudgetLeft) {
            step.transitionTo(StepState.WAITING, now); // RETRY_WAIT 到期后重领（T10 回流 READY→RUNNING）
        } else {
            step.transitionTo(StepState.FAILED, now);
        }
        stepRepository.save(step);

        // 4) append STEP_RESULT（attempt start 不入账，E10；终态结果入账；失败带 error 取证字段）
        java.util.Map<String, Object> stepResult = new LinkedHashMap<>();
        stepResult.put("step_key", step.getStepKey());
        stepResult.put("step_state", step.getState().name());
        stepResult.put("attempt_status", attempt.getStatus().name());
        if (failure != null) {
            stepResult.put("error_class", failure.errorClass());
            if (failure.errorCode() != null) {
                stepResult.put("error_code", failure.errorCode());
            }
        }
        ledger.append(ledger.newEvent(run.getId(), run.getPrRevisionId(), step.getId(), attempt.getId(),
                ExecutionEventType.STEP_RESULT, null, run.getId(), PRODUCER, stepResult));

        // 4b) 领域级告警事件落账（EX-06 预算硬上限 / EX-10 安全拒绝：不只埋在 attempt 行里）
        if (failure != null && "MODEL_BUDGET_EXCEEDED".equals(failure.errorCode())) {
            ledger.append(ledger.newEvent(run.getId(), run.getPrRevisionId(), step.getId(), attempt.getId(),
                    ExecutionEventType.BUDGET_EXCEEDED, null, run.getId(), PRODUCER,
                    Map.of("step_key", step.getStepKey(),
                            "error_code", failure.errorCode())));
        }
        if (failure != null && "SECURITY_REJECTION".equals(failure.errorCode())) {
            ledger.append(ledger.newEvent(run.getId(), run.getPrRevisionId(), step.getId(), attempt.getId(),
                    ExecutionEventType.SAFETY_REJECTED, null, run.getId(), PRODUCER,
                    Map.of("step_key", step.getStepKey(),
                            "error_code", failure.errorCode(),
                            "reason", "safe_tar_extraction_rejected")));
        }

        // 5) REVIEW 成功：findings 落库 + outbox 命令（CREATE_CHECK → PUBLISH_REVIEW 依赖链）
        if (success && STEP_TYPE_REVIEW.equals(step.getStepType())) {
            StepOutcome.Succeeded s = (StepOutcome.Succeeded) completion.outcome();
            publishReviewArtifacts(run, step, s, now);
        }

        // 6) Run 推进：REVIEW 成功 → REVIEW_COMPLETE；失败耗尽 → FAILED（EX-06 可恢复态）
        if (success && STEP_TYPE_REVIEW.equals(step.getStepType())) {
            List<RunState> path = advanceRun(run, RunState.REVIEW_COMPLETE, now);
            runRepository.save(run);
            appendRunStateEvents(run, step, path, now);
            return T2Outcome.STEP_SUCCEEDED;
        }
        if (!success && !retryWithBudgetLeft) {
            run.transitionTo(RunState.FAILED, now); // 任何非终态 → FAILED 合法
            runRepository.save(run);
            appendRunStateEvents(run, step, List.of(RunState.FAILED), now);
            return T2Outcome.STEP_FAILED;
        }
        return success ? T2Outcome.STEP_SUCCEEDED : T2Outcome.RETRY_SCHEDULED;
    }

    /** T2 第 5 步：findings 登记 + 两条类型化命令（每条各领一次 sequence/epoch）+ 依赖边 + 事件 */
    private void publishReviewArtifacts(ReviewRun run, RunStep step, StepOutcome.Succeeded outcome,
                                        Instant now) {
        PRRevision revision = revisionRepository.findById(run.getPrRevisionId())
                .orElseThrow(() -> new IllegalStateException("pr_revision 不存在: " + run.getPrRevisionId()));
        PRSubject subject = subjectRepository.findById(revision.getPrSubjectId())
                .orElseThrow(() -> new IllegalStateException("pr_subject 不存在: " + revision.getPrSubjectId()));
        var reviewOutcome = Objects.requireNonNull(outcome.reviewOutcome(), "REVIEW step 需要 reviewOutcome");

        // findings 落库（fingerprint 唯一约束幂等：T2 重放不产生重复行）
        for (ReviewFindingDraft draft : reviewOutcome.findings()) {
            findingRepository.insert(new ReviewFinding(UUID.randomUUID(), run.getId(), revision.getId(),
                    draft.fingerprint(), draft.ruleId(), draft.severity(), draft.filePath(),
                    draft.lineStart(), draft.lineEnd(), null, FindingState.PENDING, now));
        }

        // 命令 operation_id 先行生成并嵌入 payload（external_id / review marker 与命令主键同源，§6.3）
        OperationId createCheckOp = OperationId.random();
        OperationId publishReviewOp = OperationId.random();
        String aggregateKey = "pr:" + subject.getGithubRepositoryId() + "#" + subject.getPrNumber();

        OutboxCommand createCheck = outboxWriter.requestPublication(new PublicationRequest(
                createCheckOp, subject.getId(), run.getId(), revision.getId(), aggregateKey,
                CommandType.CREATE_CHECK, run.getPolicyVersion(),
                buildCheckPayload(createCheckOp, subject, revision, run, reviewOutcome), List.of()));
        OutboxCommand publishReview = outboxWriter.requestPublication(new PublicationRequest(
                publishReviewOp, subject.getId(), run.getId(), revision.getId(), aggregateKey,
                CommandType.PUBLISH_REVIEW, run.getPolicyVersion(),
                buildReviewPayload(publishReviewOp, subject, revision, run, reviewOutcome),
                List.of(PublicationRequest.DependencyEdge.requireConfirmed(createCheckOp))));

        // PUBLICATION_REQUESTED 每条命令一条（sequence/epoch 落账，I8 可查）
        for (OutboxCommand cmd : List.of(createCheck, publishReview)) {
            ledger.append(ledger.newEvent(run.getId(), revision.getId(), step.getId(), null,
                    ExecutionEventType.PUBLICATION_REQUESTED, null, run.getId(), PRODUCER,
                    Map.of("operation_id", cmd.operationId().toString(),
                            "command_type", cmd.commandType().name(),
                            "aggregate_sequence", cmd.aggregateSequence(),
                            "publication_epoch", cmd.publicationEpoch(),
                            "fence_mode", cmd.fenceMode().name())));
        }
    }

    // ------------------------------------------------------------------ 崩溃恢复（T10）

    /**
     * 回收过期租约（WorkItemWorker 恢复扫描的收尾，CT-02/ST-08 机制，整笔事务）。
     * 条件 UPDATE 抢回收权（0 行 = 他人已处理，幂等返回 false）；
     * attempt 预算未尽 → 回 READY 立即可重领；耗尽 → DEAD + 僵尸 attempt ABANDONED
     * + Step/Run FAILED + STEP_RESULT 落账（不产 outbox 命令）。
     */
    @Transactional
    public boolean reclaimExpiredLease(UUID workItemId) {
        Instant now = Instant.now();
        WorkItem workItem = workItemRepository.findById(workItemId)
                .orElseThrow(() -> new IllegalArgumentException("work_item 不存在: " + workItemId));
        if (workItem.getState() != WorkItemState.LEASED || workItem.getLeaseUntil() == null
                || !workItem.getLeaseUntil().isBefore(now)) {
            return false; // 扫描快照已失效（被他人收走/续租），跳过
        }
        boolean exhausted = workItem.getAttemptCount() >= workItem.getMaxAttempts();
        boolean reclaimed = workItemRepository.reclaimExpiredLease(workItem.getId(),
                workItem.getLeaseEpoch(), exhausted ? WorkItemState.DEAD : WorkItemState.READY);
        if (!reclaimed || !exhausted) {
            return reclaimed;
        }

        // 耗尽收尾： zombie attempt ABANDONED，Step/Run 推进 FAILED
        RunStep step = stepRepository.findById(workItem.getStepId())
                .orElseThrow(() -> new IllegalStateException("run_step 不存在: " + workItem.getStepId()));
        StepAttempt zombie = null;
        for (StepAttempt a : attemptRepository.findByStepId(step.getId())) {
            if (a.getStatus() == AttemptStatus.STARTED) {
                a.transitionTo(AttemptStatus.ABANDONED, now);
                attemptRepository.save(a);
                zombie = a;
            }
        }
        if (step.getState() == StepState.READY) {
            step.transitionTo(StepState.RUNNING, now); // 防御：worker 死在置 RUNNING 前
        }
        if (!StepStateMachine.isTerminal(step.getState())) {
            step.transitionTo(StepState.FAILED, now);
            stepRepository.save(step);
        }
        ReviewRun run = runRepository.findById(step.getReviewRunId())
                .orElseThrow(() -> new IllegalStateException("review_run 不存在: " + step.getReviewRunId()));
        if (!RunStateMachine.isTerminal(run.getState())) {
            run.transitionTo(RunState.FAILED, now);
            runRepository.save(run);
            // 账本必须能 fold 回实体行状态（I-投影一致性，ST-01）：Run 终态推进落 RUN_STATE_CHANGED
            ledger.append(ledger.newEvent(run.getId(), run.getPrRevisionId(), step.getId(),
                    zombie == null ? null : zombie.getId(),
                    ExecutionEventType.RUN_STATE_CHANGED, null, run.getId(), PRODUCER,
                    Map.of("run_state", RunState.FAILED.name(), "reason", "ATTEMPT_BUDGET_EXHAUSTED")));
        }
        ledger.append(ledger.newEvent(run.getId(), run.getPrRevisionId(), step.getId(),
                zombie == null ? null : zombie.getId(),
                ExecutionEventType.STEP_RESULT, null, run.getId(), PRODUCER,
                Map.of("step_key", step.getStepKey(),
                        "step_state", step.getState().name(),
                        "attempt_status", zombie == null ? "NONE" : AttemptStatus.ABANDONED.name(),
                        "reason", "ATTEMPT_BUDGET_EXHAUSTED")));
        return true;
    }

    /**
     * 防御性推进 Run 状态机到目标态（CREATED→REVIEWING→REVIEW_COMPLETE 逐级走合法迁移）。
     * 返回实际走过的目标态序列（不含起点），调用方据此逐条落 RUN_STATE_CHANGED 事件——
     * 账本 fold 必须能还原实体行的 Run 状态（ST-01 投影一致性断言的前提）。
     */
    private static List<RunState> advanceRun(ReviewRun run, RunState target, Instant now) {
        List<RunState> path = new ArrayList<>();
        while (run.getState() != target) {
            if (run.getState() == RunState.CREATED || run.getState() == RunState.SNAPSHOTTING) {
                run.transitionTo(RunState.REVIEWING, now);
            } else if (run.getState() == RunState.REVIEWING) {
                run.transitionTo(RunState.REVIEW_COMPLETE, now);
            } else {
                throw new IllegalStateException(
                        "Run 无法推进到 " + target + "，当前 " + run.getState());
            }
            path.add(run.getState());
        }
        return path;
    }

    /** 按迁移路径逐条 append RUN_STATE_CHANGED（与状态推进同事务，fold 可还原） */
    private void appendRunStateEvents(ReviewRun run, RunStep step, List<RunState> path, Instant now) {
        for (RunState state : path) {
            ledger.append(ledger.newEvent(run.getId(), run.getPrRevisionId(), step.getId(), null,
                    ExecutionEventType.RUN_STATE_CHANGED, null, run.getId(), PRODUCER,
                    Map.of("run_state", state.name())));
        }
    }

    private byte[] buildCheckPayload(OperationId operationId, PRSubject subject, PRRevision revision,
                                     ReviewRun run, com.objwww.pr.control.domain.review.ReviewOutcome outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation_id", operationId.toString()); // external_id 幂等探针（§6.3）
        payload.put("installation_id", subject.getGithubInstallationId()); // publisher 写前预检（SEC 加固）
        payload.put("repo", subject.getRepositoryFullName());
        payload.put("head_sha", revision.getHeadSha());
        payload.put("name", "ai-code-review");
        payload.put("run_id", run.getId().toString());
        payload.put("revision_id", revision.getId().toString());
        payload.put("finding_count", outcome.findings().size());
        return toJson(payload);
    }

    private byte[] buildReviewPayload(OperationId operationId, PRSubject subject, PRRevision revision,
                                      ReviewRun run, com.objwww.pr.control.domain.review.ReviewOutcome outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation_id", operationId.toString());
        payload.put("installation_id", subject.getGithubInstallationId()); // publisher 写前预检（SEC 加固）
        payload.put("repo", subject.getRepositoryFullName());
        payload.put("pr_number", subject.getPrNumber());
        payload.put("commit_id", revision.getHeadSha()); // Reviews API 绑 commit_id（B-1 缓解）
        payload.put("marker", "<!-- ai-review:" + operationId + " -->"); // reconcile 探针（§6.3）
        payload.put("run_id", run.getId().toString());
        List<Map<String, Object>> findings = new ArrayList<>();
        for (ReviewFindingDraft draft : outcome.findings()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("file", draft.filePath());
            f.put("line_start", draft.lineStart());
            f.put("line_end", draft.lineEnd());
            f.put("rule", draft.ruleId());
            f.put("severity", draft.severity());
            f.put("message", draft.message());
            f.put("fingerprint", draft.fingerprint().value());
            findings.add(f);
        }
        payload.put("findings", findings);
        payload.put("stats", Map.of(
                "findings", outcome.findings().size(),
                "dropped", outcome.droppedFindings(),
                "malformed", outcome.malformedFindings(),
                "selected_files", outcome.selectedFiles(),
                "truncated_files", outcome.truncatedFiles()));
        return toJson(payload);
    }

    private byte[] toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("outbox payload 序列化失败", e);
        }
    }
}
