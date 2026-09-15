package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.InvestigationResult;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunPurpose;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.domain.statemachine.RcaRunStateMachine;
import com.objwww.pr.control.alert.domain.statemachine.RcaTaskStateMachine;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.infrastructure.observability.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SR §4 Run 停滞对账（独立看门狗循环，零注解虚拟线程——A2 裁定无 @Scheduled）：
 * 检测、恢复、终止三分离，决策表见方案 §4.2。
 *
 * <pre>
 * 观察                                    | 动作
 * ----------------------------------------|--------------------------------------------
 * 有效 driver 租约 + 未过硬期限            | 等待（慢调用不是孤儿），记录等待原因
 * driver 租约过期                          | 等待既有回收（RcaWorker.recoverExpired），不造第二 driver
 * REPORTING 无可恢复 driver + 材料完整     | 铸唯一 REPORT_FINALIZE 恢复 task（走同一发布赢家/事务）
 * 活跃 Run 无 driver / driver 终态 + 材料不全 | 结构不一致告警，人工接管（不猜结果）
 * 到达可信硬期限                           | AUTO_EXPIRE 才终止：RUNNING/REPORTING→EXPIRED、
 *                                         | QUEUED→FAILED+QUEUE_DEADLINE（状态机合法边）
 * 影子 Run（新协议）活跃                   | 自身期限兜底告警；影子恢复不发正式报告
 * </pre>
 *
 * <p>围栏纪律（§4.4）：对账与正常收尾/取消共用同一套栅栏——过期事务行锁重读复验
 * purpose/state/活跃集，仅对观察版本仍成立的 run 过期；finishTask/Cancel/Reconciler
 * 竞争仅一方成功。禁止 {@code UPDATE ... WHERE updated_at < now()-N} 批量过期
 * （updated_at 可能被心跳刷新——单字段既漏报也误杀）。旧 Run 无可信 deadline
 * （列 NULL）只警示，不追溯制造过期证据。
 *
 * <p>WC-4（方案 v2 §6）：公平分页——活跃批 keyset 游标 (created_at,id)，每 tick 一批，
 * 批内单条失败也推进游标（异常条不再阻塞后续条），读到尾部下轮从头；SQL 读失败
 * 游标不动（同页重试）。finalizer 状态分类（§6.2，不再"存在即等待"）：在飞（READY/
 * BLOCKED/RETRY_WAIT/有效租约）→等待，租约失效→交回收，终态→RECOVERY_EXHAUSTED/
 * DONE 开口不一致（不再无限退避、不换 key 重铸绕预算）。终态 Run 清理通道（§5.3）：
 * 独立游标扫终态 Run 名下非终态任务 → CANCELLED + 单审计事件 + 指针条件清零
 * （不误清新 Run 指针，WC-T22）；迟到结果由 finishTask STALE/LEASE 栅栏只审计。
 *
 * <p>模式（§5，灰度顺序）：ALERT_ONLY（默认，只输出分类/预计动作/误报样本）→
 * SAFE_RECOVER（+恢复收尾铸造）→ AUTO_EXPIRE（+硬期限终止）。模式固定在配置版本。
 * 清理通道不属于铸造/过期灰度面（收敛已定终态事实，全模式运行，幂等可重入）。
 */
public class RunReconciler {

    private static final Logger log = LoggerFactory.getLogger(RunReconciler.class);

    /** 灰度模式（§5）：先只告警，验证误报后再开恢复/自动过期 */
    public enum Mode {ALERT_ONLY, SAFE_RECOVER, AUTO_EXPIRE}

    /** 决策表分类（§4.2；封闭枚举供结构化事件与测试断言） */
    public enum Decision {
        /** 行1：有效租约未超期——可能慢，不是孤儿 */
        WAIT_ACTIVE_LEASE,
        /** 行2：租约已过期——既有恢复职责在跑（worker 回收），不另造第二 driver */
        WAIT_RECLAIM,
        /** 行3 前置：退避/排队/恢复 task 在飞——调度机制持有 */
        WAIT_BACKOFF,
        /** 行3：REPORTING 无可恢复 driver、持久材料完整——可重入收尾候选 */
        FINALIZE_CANDIDATE,
        /** 行5：driver 缺失/终态且材料不全——人工接管，不猜结果 */
        ORPHAN_MATERIALS_INCOMPLETE,
        /** 行5：活跃 Run 无任何任务——结构不一致告警 */
        ORPHAN_NO_DRIVER,
        /** 行6：到达可信硬期限（无 deadline 的旧 Run 不入此分类） */
        HARD_DEADLINE_EXPIRED,
        /** 行8：影子新协议 Run 活跃（触发器中断）——自身期限兜底告警，不发正式报告 */
        SHADOW_STALLED,
        /** WC-4 §6.2：finalizer 已终态耗尽（DEAD/FAILED_TERMINAL/CANCELLED/STALE）——
         *  不再退避不再重铸（不换 key 绕预算），显式待人工 */
        RECOVERY_EXHAUSTED,
        /** WC-4 §6.2：finalizer DONE 但 Run 仍活跃——finishTask 原子面之外的结构性
         *  不一致（任务 DONE 与 Run 收口同事务，正常路径不可能分离）：核材料事实、
         *  告警人工确定性收尾，不冒认成功不自动补写 */
        FINALIZER_DONE_RUN_OPEN,
        /** PA-A1：LIVE_BUT_STUCK——driver 租约活（心跳在续）但 STARTED attempt 的
         *  有效进展（last_meaningful_progress_at）滞后超过阈值。与 WAIT_ACTIVE_LEASE
         *  的分界：慢不是 stuck，无有效进展才是（LLM 持续吐 token 只算 activity）。
         *  只读域取消零副作用；AM8 解锁 mutation 后本档必须先过 operation 状态闸
         *  （有 DISPATCHED/UNKNOWN 先 reconcile，设计 §3.3/R13） */
        LIVE_BUT_STUCK
    }

    private final RcaRunRepository runs;
    private final RcaTaskRepository tasks;
    private final RcaReportRepository reports;
    private final InvestigationResultRepository investigationResults;
    private final IncidentRepository incidents;
    private final RcaEventAppender events;
    private final TransactionOperations tx;
    private final SlaPolicy sla;
    private final AlertClock clock;
    private final Mode mode;
    private final Duration pollInterval;
    private final int batchLimit;
    /** WC-5 观测面（NOOP = 无观测语义环境，旧装配不变） */
    private final AlertMetrics metrics;
    /** PA-A1：attempt 进度读面（null = LIVE_BUT_STUCK 检测关闭——旧装配零行为差） */
    private final com.objwww.pr.control.alert.domain.repository.RcaAttemptRepository attempts;
    /** PA-A1：有效进展滞后阈值（null = 检测关闭；有效值必须为正） */
    private final Duration stuckThreshold;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread workerThread;
    /** WC-4 §6.1 进程内 keyset 游标（活跃批/清理批各一）：null = 从头；读到尾部归零重启 */
    private volatile Instant activeCursorCreatedAt;
    private volatile UUID activeCursorId;
    private volatile Instant cleanupCursorCreatedAt;
    private volatile UUID cleanupCursorId;

    public RunReconciler(RcaRunRepository runs,
                         RcaTaskRepository tasks,
                         RcaReportRepository reports,
                         InvestigationResultRepository investigationResults,
                         IncidentRepository incidents,
                         RcaEventAppender events,
                         TransactionOperations tx,
                         SlaPolicy sla,
                         AlertClock clock,
                         Mode mode,
                         Duration pollInterval,
                         int batchLimit) {
        this(runs, tasks, reports, investigationResults, incidents, events, tx, sla, clock,
                mode, pollInterval, batchLimit, AlertMetrics.NOOP);
    }

    /** WC-5 全参形态：接观测面（扫描时长/失败/决策计数/gauge 族） */
    public RunReconciler(RcaRunRepository runs,
                         RcaTaskRepository tasks,
                         RcaReportRepository reports,
                         InvestigationResultRepository investigationResults,
                         IncidentRepository incidents,
                         RcaEventAppender events,
                         TransactionOperations tx,
                         SlaPolicy sla,
                         AlertClock clock,
                         Mode mode,
                         Duration pollInterval,
                         int batchLimit,
                         AlertMetrics metrics) {
        this(runs, tasks, reports, investigationResults, incidents, events, tx, sla, clock,
                mode, pollInterval, batchLimit, metrics, null, null);
    }

    /** PA-A1 全参形态：接 attempt 进度读面 + LIVE_BUT_STUCK 阈值（null = 检测关闭） */
    public RunReconciler(RcaRunRepository runs,
                         RcaTaskRepository tasks,
                         RcaReportRepository reports,
                         InvestigationResultRepository investigationResults,
                         IncidentRepository incidents,
                         RcaEventAppender events,
                         TransactionOperations tx,
                         SlaPolicy sla,
                         AlertClock clock,
                         Mode mode,
                         Duration pollInterval,
                         int batchLimit,
                         AlertMetrics metrics,
                         com.objwww.pr.control.alert.domain.repository.RcaAttemptRepository attempts,
                         Duration stuckThreshold) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.reports = Objects.requireNonNull(reports, "reports");
        this.investigationResults = Objects.requireNonNull(investigationResults,
                "investigationResults");
        this.incidents = Objects.requireNonNull(incidents, "incidents");
        this.events = Objects.requireNonNull(events, "events");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.sla = Objects.requireNonNull(sla, "sla");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mode = Objects.requireNonNull(mode, "mode");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval 必须为正");
        }
        this.pollInterval = pollInterval;
        if (batchLimit < 1) {
            throw new IllegalArgumentException("batchLimit 从 1 起");
        }
        this.batchLimit = batchLimit;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (stuckThreshold != null && (stuckThreshold.isNegative() || stuckThreshold.isZero())) {
            throw new IllegalArgumentException("stuckThreshold 必须为正（null=检测关闭）");
        }
        this.attempts = attempts;
        this.stuckThreshold = stuckThreshold;
    }

    public Mode mode() {
        return mode;
    }

    // ------------------------------------------------------------------ 常驻循环（IncidentWaitingRedriver 同型）

    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            workerThread = Thread.ofVirtual().name("run-reconciler").start(() -> {
                while (running.get()) {
                    try {
                        scanOnce();
                        // 固定拍巡逻：常驻候选（如 ALERT_ONLY 下的历史孤立 Run）每轮
                        // 都会"被决策"，按决策数快转 = 热旋转（195 实证 27k 事件/10min）
                        Thread.sleep(pollInterval.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (RuntimeException e) {
                        // SR12：扫描失败外部可发现（错误日志），不能无声死亡
                        log.error("对账扫描整轮失败，{} 后重试", pollInterval, e);
                        try {
                            Thread.sleep(pollInterval.toMillis());
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            });
            log.info("RunReconciler 启动 mode={} interval={} batch={}",
                    mode, pollInterval, batchLimit);
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    // ------------------------------------------------------------------ 对账

    /**
     * 单轮对账扫描（测试直调面）：每 tick 一个受限活跃批（§6.1——大循环不霸占
     * 线程，全体覆盖跨多 tick 达成）+ 一个受限清理批（§5.3），两通道互不拖死。
     * 返回活跃通道分类条数；SQL 读失败游标不动（同页重试），整通道失败只记日志。
     */
    public int scanOnce() {
        Instant now = clock.now();
        int decided = 0;
        boolean activeOk = true;
        long beginActive = System.nanoTime();
        try {
            decided = scanActiveBatch(now);
        } catch (RuntimeException e) {
            // §6.1：SQL 读取失败不得假装推进——游标原样，下一 tick 重试同页
            activeOk = false;
            log.error("对账活跃批扫描失败（游标不推进，同页重试）", e);
        }
        metrics.reconcileScan(AlertMetrics.CHANNEL_ACTIVE, activeOk,
                (System.nanoTime() - beginActive) / 1_000_000);
        boolean cleanupOk = true;
        long beginCleanup = System.nanoTime();
        try {
            cleanupTerminalRunTasks(now);
        } catch (RuntimeException e) {
            cleanupOk = false;
            log.error("终态 Run 清理批扫描失败（游标不推进，同页重试）", e);
        }
        metrics.reconcileScan(AlertMetrics.CHANNEL_CLEANUP, cleanupOk,
                (System.nanoTime() - beginCleanup) / 1_000_000);
        if (activeOk && cleanupOk) {
            // WC-5：整轮两通道全成功才推 last_success（连续不增长 = 看门狗停摆告警面）
            metrics.reconcileScanSucceeded(now.toEpochMilli());
        }
        refreshReconcileGauges(now);
        return decided;
    }

    /** WC-5：每拍 gauge 覆盖（单项读失败保持上一拍值——假造 0 比缺数更危险） */
    private void refreshReconcileGauges(Instant now) {
        long oldestAgeMs = -1;
        long terminalOpen = -1;
        try {
            oldestAgeMs = runs.oldestActiveCreatedAt()
                    .map(c -> Duration.between(c, now).toMillis()).orElse(0L);
        } catch (RuntimeException e) {
            log.warn("oldestActiveCreatedAt 读取失败（gauge 保持上一拍值）", e);
        }
        try {
            terminalOpen = tasks.countOpenTasksUnderTerminalRuns();
        } catch (RuntimeException e) {
            log.warn("countOpenTasksUnderTerminalRuns 读取失败（gauge 保持上一拍值）", e);
        }
        metrics.reconcileScanObserved(oldestAgeMs, terminalOpen);
    }

    /** 活跃批：keyset 取批 → 逐条分类（单条失败越过但游标仍推进）→ 游标落批末 */
    private int scanActiveBatch(Instant now) {
        List<RcaRunRepository.ReconcileCandidate> batch = runs.findActiveForReconcileAfter(
                activeCursorCreatedAt, activeCursorId, batchLimit);
        if (batch.isEmpty()) {
            activeCursorCreatedAt = null;
            activeCursorId = null;
            return 0;
        }
        int decided = 0;
        for (RcaRunRepository.ReconcileCandidate candidate : batch) {
            try {
                decide(candidate, now);
                decided++;
            } catch (RuntimeException e) {
                // 单 run 失败不炸整轮（对账自身可恢复，SR10/SR12）；游标照推（§6.1
                // 异常条不阻塞后续条，下一完整轮还会再见到它）
                log.error("run {} 对账失败（越过，游标照推）", candidate.id(), e);
            }
        }
        RcaRunRepository.ReconcileCandidate last = batch.get(batch.size() - 1);
        activeCursorCreatedAt = last.createdAt();
        activeCursorId = last.id();
        if (batch.size() < batchLimit) {
            // 已到尾部：下轮从头（§6.1——覆盖周期 ≈ ceil(N/B)×P，全体必被检查）
            activeCursorCreatedAt = null;
            activeCursorId = null;
        }
        return decided;
    }

    private void decide(RcaRunRepository.ReconcileCandidate candidate, Instant now) {
        List<RcaTask> runTasks = tasks.findByRunId(candidate.id());
        boolean pastDeadline = candidate.reconcileDeadlineAt() != null
                && now.isAfter(candidate.reconcileDeadlineAt());

        Decision decision;
        if (candidate.purpose() == RunPurpose.SHADOW) {
            decision = pastDeadline ? Decision.HARD_DEADLINE_EXPIRED : Decision.SHADOW_STALLED;
        } else if (pastDeadline) {
            decision = Decision.HARD_DEADLINE_EXPIRED;
        } else if (hasLiveLease(runTasks, now)) {
            // PA-A1：租约活≠有进展——心跳在续但有效进展滞后超阈值 = LIVE_BUT_STUCK。
            // 慢调用（progress 新鲜）仍是 WAIT_ACTIVE_LEASE；REPORT_FINALIZE 不参与
            // （收尾任务不产检查点进展，其 bounded 性由自身 deadline_at 把关）
            decision = isLiveButStuck(candidate, runTasks, now)
                    ? Decision.LIVE_BUT_STUCK : Decision.WAIT_ACTIVE_LEASE;
        } else {
            // WC-4 §6.2：finalizer 看状态不是看存在——在飞/租约失效/终态分类，
            // 无 finalizer 才继续走材料/退避/孤立链
            Optional<Decision> byFinalizer = classifyFinalizer(runTasks, now);
            if (byFinalizer.isPresent()) {
                decision = byFinalizer.get();
            } else if ((candidate.state() == RcaRunState.REPORTING
                        || candidate.state() == RcaRunState.RUNNING)
                    && candidate.purpose() == RunPurpose.PRODUCTION
                    && materialsComplete(candidate)) {
                // §4.3：持久材料完整（预提交终态行/已有报告）优先重入收尾，不重跑调查——
                // 身份闸：仅可信 PRODUCTION（LEGACY_UNKNOWN 历史行不自动产正式报告，§3.3）
                decision = Decision.FINALIZE_CANDIDATE;
            } else if (hasPendingWork(runTasks, now)) {
                // 退避中/排队中/租约刚过期未回收——调度机制持有（worker 回收职责），不抢
                decision = hasExpiredLease(runTasks, now)
                        ? Decision.WAIT_RECLAIM : Decision.WAIT_BACKOFF;
            } else if (candidate.state() == RcaRunState.REPORTING) {
                decision = Decision.ORPHAN_MATERIALS_INCOMPLETE;
            } else {
                // QUEUED/RUNNING：无有效租约、无排队/退避——driver 缺失或已终态而 run 仍活跃
                decision = runTasks.isEmpty() ? Decision.ORPHAN_NO_DRIVER
                        : Decision.ORPHAN_MATERIALS_INCOMPLETE;
            }
        }
        act(candidate, decision, now);
    }

    /**
     * WC-4 §6.2 finalizer 状态分类（决策表）：任一在飞（非终态）finalizer = 调度机制
     * 持有——READY/BLOCKED/RETRY_WAIT 等正常调度，LEASED/RUNNING 按租约有效性分
     * 等待/回收（有效租约通常已被 hasLiveLease 先行截走，此处兜底 RUNNING 态）；
     * 全终态 = 恢复预算已耗尽（不换 key 重铸绕预算）或 DONE 开口结构性不一致——
     * 都不再退避。无 finalizer → empty（继续走材料/孤立链）。
     * 包内可见供决策表单测直断（RunReconcilerFairnessTest）。
     */
    static Optional<Decision> classifyFinalizer(List<RcaTask> runTasks, Instant now) {
        List<RcaTask> finalizers = runTasks.stream()
                .filter(t -> RcaTask.REPORT_FINALIZE.equals(t.taskKey())).toList();
        if (finalizers.isEmpty()) {
            return Optional.empty();
        }
        Optional<RcaTask> inflight = finalizers.stream()
                .filter(t -> !isTerminal(t.state()))
                .max(java.util.Comparator.comparing(RcaTask::updatedAt)
                        .thenComparing(RcaTask::id));
        if (inflight.isPresent()) {
            RcaTask f = inflight.get();
            if (f.state() == RcaTaskState.LEASED || f.state() == RcaTaskState.RUNNING) {
                return Optional.of(f.leaseUntil() != null && f.leaseUntil().isAfter(now)
                        ? Decision.WAIT_ACTIVE_LEASE : Decision.WAIT_RECLAIM);
            }
            return Optional.of(Decision.WAIT_BACKOFF);
        }
        // 全终态：最新一条定收敛事实（多轮铸造时按 updatedAt 取最新）
        RcaTask latest = finalizers.stream()
                .max(java.util.Comparator.comparing(RcaTask::updatedAt)
                        .thenComparing(RcaTask::id))
                .orElseThrow();
        return Optional.of(latest.state() == RcaTaskState.DONE
                ? Decision.FINALIZER_DONE_RUN_OPEN : Decision.RECOVERY_EXHAUSTED);
    }

    private void act(RcaRunRepository.ReconcileCandidate candidate, Decision decision,
                     Instant now) {
        metrics.reconcileDecision(decision.name());
        switch (decision) {
            case FINALIZE_CANDIDATE -> {
                if (mode == Mode.ALERT_ONLY) {
                    emit(candidate, decision, "SAFE_RECOVER 将铸 REPORT_FINALIZE 收尾任务");
                } else {
                    castFinalizeTask(candidate, now);
                }
            }
            case HARD_DEADLINE_EXPIRED -> {
                if (candidate.reconcileDeadlineAt() == null) {
                    // 不可达（分类守卫已排除）——防御分支
                    emit(candidate, decision, "无可信 deadline，仅警示");
                } else if (mode == Mode.AUTO_EXPIRE) {
                    expireRun(candidate, now);
                } else {
                    emit(candidate, decision, "AUTO_EXPIRE 将按状态机终止（"
                            + (candidate.state() == RcaRunState.QUEUED
                            ? "QUEUED→FAILED+QUEUE_DEADLINE" : "→EXPIRED") + "）");
                }
            }
            case ORPHAN_MATERIALS_INCOMPLETE, ORPHAN_NO_DRIVER ->
                    emit(candidate, decision, "结构不一致：告警人工接管，不猜结果不自动恢复");
            case LIVE_BUT_STUCK -> {
                // 灰度纪律与 HARD_DEADLINE_EXPIRED 同线：终止只在 AUTO_EXPIRE 生效——
                // SAFE_RECOVER（195 现行）只分类告警，观测误报后再开终止
                if (mode != Mode.AUTO_EXPIRE) {
                    emit(candidate, decision, "AUTO_EXPIRE 将按 LIVE_BUT_STUCK 终止"
                            + "（心跳活但有效进展滞后 > " + stuckThreshold + "）");
                } else {
                    cancelStuckRun(candidate, now);
                }
            }
            case RECOVERY_EXHAUSTED ->
                    // §6.2：恢复预算已耗尽——不退避不重铸（不换 key 绕 maxAttempts）
                    emit(candidate, decision, "finalizer 已终态（预算耗尽）：不再退避不再重铸，人工接管");
            case FINALIZER_DONE_RUN_OPEN ->
                    // §6.2：DONE 与 Run 收口同事务（finishTask 原子面），分离 = 结构
                    // 不一致——核材料事实，人工确定性收尾，不冒认成功
                    emit(candidate, decision, materialsComplete(candidate)
                            ? "finalizer DONE 且材料完整但 Run 未收口：发布事实核查，人工确定性收尾"
                            : "finalizer DONE 但材料不完整：结构不一致，人工接管");
            default -> emit(candidate, decision, "等待（记录等待原因，不抢租约不代跑）");
        }
    }

    /** SR12：每个决策一条结构化事件（分类/模式/期限/恢复次数——runId 只进日志与详情） */
    private void emit(RcaRunRepository.ReconcileCandidate candidate, Decision decision,
                      String proposed) {
        StructuredLog.event(log, "run_reconcile_decision", Map.ofEntries(
                Map.entry("run_id", candidate.id().toString()),
                Map.entry("decision", decision.name()),
                Map.entry("mode", mode.name()),
                Map.entry("state", candidate.state().name()),
                Map.entry("purpose", candidate.purpose().name()),
                Map.entry("deadline", String.valueOf(candidate.reconcileDeadlineAt())),
                Map.entry("recovery_attempts", candidate.recoveryAttempts()),
                Map.entry("proposed", proposed)));
        if (decision == Decision.HARD_DEADLINE_EXPIRED
                || decision == Decision.ORPHAN_MATERIALS_INCOMPLETE
                || decision == Decision.ORPHAN_NO_DRIVER
                || decision == Decision.RECOVERY_EXHAUSTED
                || decision == Decision.LIVE_BUT_STUCK
                || decision == Decision.FINALIZER_DONE_RUN_OPEN) {
            log.warn("run {} 对账决策 {}（mode={} deadline={}）：{}",
                    candidate.id(), decision, mode, candidate.reconcileDeadlineAt(), proposed);
        } else {
            log.info("run {} 对账决策 {}（mode={} deadline={}）：{}",
                    candidate.id(), decision, mode, candidate.reconcileDeadlineAt(), proposed);
        }
    }

    // ------------------------------------------------------------------ 恢复动作（SAFE_RECOVER 起）

    /**
     * 铸唯一 REPORT_FINALIZE 收尾 task（§4.3；WC-4 §6.3 锁内复验全套）：重锁复验
     * 活跃态 + 身份（仅 PRODUCTION——比"非 SHADOW"更严）+ 材料（候选快照不可信，
     * 锁内重读）+ finalizer 唯一性（在场检查 + uq ON CONFLICT 双闸，§4.1——PG 事务
     * 不被唯一冲突置 aborted）。finalizer 期限 = min(Run 硬期限, 当前时间+收尾许可)，
     * Run 已到期不铸造（终止路径接管）。恢复次数持久 +1（有界判据）。
     */
    private void castFinalizeTask(RcaRunRepository.ReconcileCandidate candidate, Instant now) {
        Boolean cast = tx.execute(status -> {
            RcaRun locked = runs.findByIdForUpdate(candidate.id()).orElse(null);
            if (locked == null || !locked.state().isActive()
                    || locked.purpose() != RunPurpose.PRODUCTION) {
                return false;
            }
            // §6.3 锁内复验材料：终态调查行/报告面重读（不用候选快照）
            if (!materialsComplete(candidate)) {
                return false;
            }
            // §6.3 锁内重读现行硬期限：已到期不铸造；finalizer 期限取双限最小
            Instant runDeadline = runs.reconcileDeadlineById(candidate.id()).orElse(null);
            if (runDeadline != null && !runDeadline.isAfter(now)) {
                return false;
            }
            Instant taskDeadline = sla.deadline(now, 0);
            if (runDeadline != null && runDeadline.isBefore(taskDeadline)) {
                taskDeadline = runDeadline;
            }
            boolean exists = tasks.findByRunId(candidate.id()).stream()
                    .anyMatch(t -> RcaTask.REPORT_FINALIZE.equals(t.taskKey()));
            if (exists) {
                return false;
            }
            // priority=9：收尾恢复优先于 driver 重跑（§4.3 不重新乱跑调查——
            // 材料已验证完整时，重入收尾优于再调查的 SLA 晋升排序）
            boolean inserted = tasks.insertIfAbsent(new RcaTask(UUID.randomUUID(),
                    candidate.id(), RcaTask.REPORT_FINALIZE, RcaTaskState.READY,
                    9, now, now, taskDeadline, null, null, 0, 0, 2, now, now, 0));
            if (!inserted) {
                return false;
            }
            runs.incrementRecoveryAttempts(candidate.id());
            events.append(candidate.id(), new RcaEventAppender.EventDraft(
                    UUID.randomUUID(), "RUN_RECOVERY_CAST",
                    "{\"taskKey\":\"REPORT_FINALIZE\"}"));
            return true;
        });
        if (Boolean.TRUE.equals(cast)) {
            log.warn("run {} 对账恢复：铸 REPORT_FINALIZE 收尾任务（只组既有材料）",
                    candidate.id());
        }
    }

    // ------------------------------------------------------------------ 过期动作（AUTO_EXPIRE 起）

    /**
     * 硬期限过期（§4.4 过期事务；WC-4 §6.3 锁内复验）：行锁重读复验（活跃集 + 身份
     * + <b>现行 deadline</b>——候选快照可能已被并发修宽/清除，仅凭快照终止=误杀），
     * 复验成立才按状态机终止——RUNNING/REPORTING→EXPIRED+DEADLINE_EXPIRED、
     * QUEUED→FAILED+QUEUE_DEADLINE（QUEUED 无 EXPIRED 出边）。子任务资格撤销
     * （非终态→CANCELLED，迟到结果由 finishTask STALE 栅栏只审计）；incident 当前
     * Run 指针仅当仍指向本 run 时清理。竞争（finishTask/Cancel 先落地）= 复验败者
     * 零副作用。
     */
    private void expireRun(RcaRunRepository.ReconcileCandidate candidate, Instant now) {
        Boolean expired = tx.execute(status -> {
            // SR08 真锁序：先按 id 序锁非终态 task，再锁 run——与 finishTask 的
            // task→run 序一致（AB-BA 死锁防线，195 真 PG 竞态实证）；锁后以库内行为准
            List<RcaTask> lockedTasks = tasks.lockNonTerminalByRunIdForUpdate(candidate.id());
            RcaRun locked = runs.findByIdForUpdate(candidate.id()).orElse(null);
            if (locked == null || !locked.state().isActive()) {
                return false;
            }
            // WC-4 §6.3：锁内重读现行 deadline——null（legacy 无可信期限）只观察，
            // 未到期不终止（无进展告警与硬期限终止是两个概念，心跳不延长硬期限）
            Instant deadline = runs.reconcileDeadlineById(candidate.id()).orElse(null);
            if (deadline == null || deadline.isAfter(now)) {
                return false;
            }
            boolean queued = locked.state() == RcaRunState.QUEUED;
            RcaRunState target = queued ? RcaRunState.FAILED : RcaRunState.EXPIRED;
            String completionKind = queued ? RcaRun.COMPLETION_QUEUE_DEADLINE
                    : RcaRun.COMPLETION_DEADLINE_EXPIRED;
            RcaRunStateMachine.requireTransition(locked.state(), target);
            runs.update(new RcaRun(locked.id(), locked.incidentId(), locked.generation(),
                    locked.trigger(), target, locked.investigationHash(), locked.createdAt(),
                    now, locked.startedAt(), now, "RECONCILE_DEADLINE", locked.purpose(),
                    locked.purposeSource(), completionKind));
            events.append(candidate.id(), new RcaEventAppender.EventDraft(
                    UUID.randomUUID(), "RUN_EXPIRED",
                    "{\"by\":\"run-reconciler\",\"completionKind\":\"" + completionKind
                            + "\",\"deadline\":\"" + deadline + "\"}"));
            for (RcaTask task : lockedTasks) {
                if (!isTerminal(task.state())) {
                    RcaTaskStateMachine.requireTransition(task.state(),
                            RcaTaskState.CANCELLED);
                    tasks.update(task.withState(RcaTaskState.CANCELLED, now));
                }
            }
            incidents.findByIdForUpdate(candidate.incidentId()).ifPresent(incident -> {
                if (candidate.id().equals(incident.currentRcaRunId())) {
                    incidents.update(incident.withCurrentRunPointerCleared(now));
                }
            });
            runs.incrementRecoveryAttempts(candidate.id());
            return true;
        });
        if (Boolean.TRUE.equals(expired)) {
            log.warn("run {} 到达可信硬期限，对账终止（{}）", candidate.id(),
                    candidate.state());
        } else {
            log.info("run {} 过期竞争败者（正常收尾/取消已先落地），零副作用", candidate.id());
        }
    }

    // ------------------------------------------------------------------ 进度档动作（PA-A1）

    /**
     * LIVE_BUT_STUCK 判定（分类面）：仅 PRODUCTION + RUNNING/REPORTING；存在 driver
     * 任务（非 REPORT_FINALIZE）租约活且其 STARTED attempt 的有效进展基线
     * （last_meaningful_progress_at，NULL 回退 startedAt——V111 存量行语义）滞后超过
     * stuckThreshold。attempts/阈值未装配 = 永不判定（检测关闭）。
     */
    private boolean isLiveButStuck(RcaRunRepository.ReconcileCandidate candidate,
            List<RcaTask> runTasks, Instant now) {
        if (attempts == null || stuckThreshold == null
                || candidate.purpose() != RunPurpose.PRODUCTION
                || (candidate.state() != RcaRunState.RUNNING
                        && candidate.state() != RcaRunState.REPORTING)) {
            return false;
        }
        Instant staleBefore = now.minus(stuckThreshold);
        return runTasks.stream()
                .filter(t -> t.state() == RcaTaskState.LEASED)
                .filter(t -> t.leaseUntil() != null && t.leaseUntil().isAfter(now))
                .filter(t -> !RcaTask.REPORT_FINALIZE.equals(t.taskKey()))
                .anyMatch(t -> attempts.findStartedProgressByTaskId(t.id())
                        .map(p -> p.effectiveProgressAt().isBefore(staleBefore))
                        .orElse(false));
    }

    /**
     * LIVE_BUT_STUCK 终止（PA-A1，expireRun 同锁序/同栅栏纪律）：锁内复验「purpose
     * + 活跃态 + 仍存在进展滞后的活租约 driver」——候选快照不可信（心跳可能刚推进/
     * 检查点可能刚提交），复验败者零副作用。单事务直落终态（无 CANCEL_REQUESTED
     * 中间窗）：RUNNING/REPORTING→EXPIRED + completionKind=LIVE_BUT_STUCK，非终态
     * 任务→CANCELLED（迟到结果由 finishTask STALE 栅栏只审计），指针条件清零。
     * 只读域（R2/R3 VALIDATE_ONLY）零外部副作用，取消即安全；AM8 解锁 mutation 后
     * 本事务前必须加 operation 状态闸（DISPATCHED/UNKNOWN → 先 reconcile，R13）。
     */
    private void cancelStuckRun(RcaRunRepository.ReconcileCandidate candidate, Instant now) {
        Boolean cancelled = tx.execute(status -> {
            List<RcaTask> lockedTasks = tasks.lockNonTerminalByRunIdForUpdate(candidate.id());
            RcaRun locked = runs.findByIdForUpdate(candidate.id()).orElse(null);
            if (locked == null || !locked.state().isActive()
                    || locked.purpose() != RunPurpose.PRODUCTION
                    || (locked.state() != RcaRunState.RUNNING
                            && locked.state() != RcaRunState.REPORTING)) {
                return false;
            }
            boolean stillStuck = lockedTasks.stream()
                    .filter(t -> t.state() == RcaTaskState.LEASED)
                    .filter(t -> t.leaseUntil() != null && t.leaseUntil().isAfter(now))
                    .filter(t -> !RcaTask.REPORT_FINALIZE.equals(t.taskKey()))
                    .anyMatch(t -> attempts.findStartedProgressByTaskId(t.id())
                            .map(p -> p.effectiveProgressAt().isBefore(now.minus(stuckThreshold)))
                            .orElse(false));
            if (!stillStuck) {
                return false;
            }
            RcaRunStateMachine.requireTransition(locked.state(), RcaRunState.EXPIRED);
            runs.update(new RcaRun(locked.id(), locked.incidentId(), locked.generation(),
                    locked.trigger(), RcaRunState.EXPIRED, locked.investigationHash(),
                    locked.createdAt(), now, locked.startedAt(), now,
                    "RECONCILE_LIVE_BUT_STUCK", locked.purpose(), locked.purposeSource(),
                    RcaRun.COMPLETION_LIVE_BUT_STUCK));
            events.append(candidate.id(), new RcaEventAppender.EventDraft(
                    UUID.randomUUID(), "RUN_LIVE_BUT_STUCK",
                    "{\"by\":\"run-reconciler\",\"thresholdMs\":" + stuckThreshold.toMillis()
                            + ",\"completionKind\":\"" + RcaRun.COMPLETION_LIVE_BUT_STUCK
                            + "\"}"));
            for (RcaTask task : lockedTasks) {
                if (!isTerminal(task.state())) {
                    RcaTaskStateMachine.requireTransition(task.state(),
                            RcaTaskState.CANCELLED);
                    tasks.update(task.withState(RcaTaskState.CANCELLED, now));
                }
            }
            incidents.findByIdForUpdate(candidate.incidentId()).ifPresent(incident -> {
                if (candidate.id().equals(incident.currentRcaRunId())) {
                    incidents.update(incident.withCurrentRunPointerCleared(now));
                }
            });
            runs.incrementRecoveryAttempts(candidate.id());
            return true;
        });
        if (Boolean.TRUE.equals(cancelled)) {
            log.warn("run {} LIVE_BUT_STUCK 终止（心跳活但有效进展滞后 > {}）",
                    candidate.id(), stuckThreshold);
        } else {
            log.info("run {} LIVE_BUT_STUCK 复验败者（检查点刚推进/租约刚更新已先落地），零副作用",
                    candidate.id());
        }
    }

    // ------------------------------------------------------------------ 终态 Run 清理通道（§5.3）

    /**
     * WC-4 §5.3：终态 Run 名下非终态任务清理（独立 keyset 批，与活跃对账不争用）。
     * 取消运行（operator CANCEL 只翻 Run 状态）与过期后遗留的 READY/BLOCKED/
     * RETRY_WAIT/LEASED/RUNNING 任务在此收敛为 CANCELLED——撤销执行资格，迟到结果
     * 由 finishTask 租约/STALE 栅栏只审计（execution/attempt 账本保留事实）。
     * 幂等：任务全终态后不再入批，事件只在真取消 ≥1 条的那一事务里落一次。
     * 指针条件清零独立于清理事务（单条件 UPDATE，不叠 Run→Incident 锁环）。
     * 本通道不属铸造/过期灰度面：收敛<b>已定终态</b>事实，全模式运行。
     * 局限（有意）：指针悬挂但任务已全终态的 Run 不入驱动面（该形状由铸 run/收尾
     * 事务自身的指针语义覆盖），不为此扩全量终态扫描。
     */
    private void cleanupTerminalRunTasks(Instant now) {
        List<RcaTaskRepository.OpenTaskRef> batch = tasks.findOpenTasksUnderTerminalRunsAfter(
                cleanupCursorCreatedAt, cleanupCursorId, batchLimit);
        if (batch.isEmpty()) {
            cleanupCursorCreatedAt = null;
            cleanupCursorId = null;
            return;
        }
        for (UUID runId : batch.stream().map(RcaTaskRepository.OpenTaskRef::runId)
                .distinct().toList()) {
            try {
                cleanupRun(runId, now);
            } catch (RuntimeException e) {
                log.error("run {} 终态任务清理失败（越过，下轮再会）", runId, e);
            }
        }
        RcaTaskRepository.OpenTaskRef last = batch.get(batch.size() - 1);
        cleanupCursorCreatedAt = last.createdAt();
        cleanupCursorId = last.taskId();
        if (batch.size() < batchLimit) {
            cleanupCursorCreatedAt = null;
            cleanupCursorId = null;
        }
    }

    /** 单 run 清理（锁序 task→run，与 expireRun/finishTask 同序）；指针独立条件清 */
    private void cleanupRun(UUID runId, Instant now) {
        RcaRun snapshot = runs.findById(runId).orElse(null);
        if (snapshot == null || snapshot.state().isActive()) {
            return;
        }
        Integer cancelledCount = tx.execute(status -> {
            List<RcaTask> open = tasks.lockNonTerminalByRunIdForUpdate(runId);
            RcaRun locked = runs.findByIdForUpdate(runId).orElse(null);
            if (locked == null || locked.state().isActive()) {
                return 0;
            }
            int cancelled = 0;
            for (RcaTask task : open) {
                if (isTerminal(task.state())) {
                    continue;
                }
                RcaTaskStateMachine.requireTransition(task.state(), RcaTaskState.CANCELLED);
                tasks.update(task.withState(RcaTaskState.CANCELLED, now));
                cancelled++;
            }
            if (cancelled == 0) {
                return 0;
            }
            events.append(runId, new RcaEventAppender.EventDraft(
                    UUID.randomUUID(), "RUN_TERMINAL_CLEANUP",
                    "{\"by\":\"run-reconciler\",\"cancelledTasks\":" + cancelled + "}"));
            return cancelled;
        });
        if (cancelledCount != null && cancelledCount > 0) {
            log.warn("run {} 终态任务清理：{} 条非终态任务 → CANCELLED（资格撤销，账本保留）",
                    runId, cancelledCount);
            // WC-5：终态落地 → 本地静默的收敛时长（观测近似 = Run 终态行时刻 → 清理收敛时刻）
            metrics.cancelToQuiesce(Duration.between(snapshot.updatedAt(), now).toMillis());
        }
        // §5.3/WC-T22：指针条件清零——仅当仍指向本 run（不误清新 Run 指针）；
        // 幂等（已清/指他 = 0 行）
        incidents.clearCurrentRunPointerIfEquals(snapshot.incidentId(), runId, now);
    }

    // ------------------------------------------------------------------ 分类辅助

    private static boolean hasLiveLease(List<RcaTask> runTasks, Instant now) {
        return runTasks.stream().anyMatch(t -> t.state() == RcaTaskState.LEASED
                && t.leaseUntil() != null && t.leaseUntil().isAfter(now));
    }

    private static boolean hasExpiredLease(List<RcaTask> runTasks, Instant now) {
        return runTasks.stream().anyMatch(t -> t.state() == RcaTaskState.LEASED
                && (t.leaseUntil() == null || !t.leaseUntil().isAfter(now)));
    }

    /** 排队/退避/悬挂租约 = 调度机制仍持有后续动作（worker 领取/回收职责在跑） */
    private static boolean hasPendingWork(List<RcaTask> runTasks, Instant now) {
        return runTasks.stream().anyMatch(t -> t.state() == RcaTaskState.READY
                || t.state() == RcaTaskState.BLOCKED
                || t.state() == RcaTaskState.RETRY_WAIT
                || t.state() == RcaTaskState.LEASED
                || t.state() == RcaTaskState.RUNNING);
    }

    /** §4.3：材料完整 = 已有正式报告（收敛侧）或验证通过的终态调查行（预提交持久面） */
    private boolean materialsComplete(RcaRunRepository.ReconcileCandidate candidate) {
        if (!reports.findByRunId(candidate.id()).isEmpty()) {
            return true;
        }
        return investigationResults.findByRunId(candidate.id()).stream().anyMatch(row ->
                row.finishedAt() != null
                        && row.executionStatus() == com.objwww.pr.control.alert.domain.model
                                .ExecutionStatus.SUCCEEDED
                        && row.validationStatus() == ValidationStatus.STRUCTURE_VALIDATED
                        && row.packageJson() != null);
    }

    private static boolean isTerminal(RcaTaskState state) {
        return state == RcaTaskState.DONE || state == RcaTaskState.CANCELLED
                || state == RcaTaskState.DEAD || state == RcaTaskState.SKIPPED
                || state == RcaTaskState.FAILED_TERMINAL || state == RcaTaskState.STALE;
    }
}
