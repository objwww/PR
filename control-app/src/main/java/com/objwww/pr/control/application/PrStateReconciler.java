package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.PRRevision;
import com.objwww.pr.control.domain.model.PRSubject;
import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort.FetchResult;
import com.objwww.pr.control.domain.repository.PRRevisionRepository;
import com.objwww.pr.control.domain.repository.PRSubjectRepository;
import com.objwww.pr.control.domain.repository.ReviewRunRepository;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.control.interfaces.webhook.PullRequestEvent;
import com.objwww.pr.shared.ExecutionEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PrStateReconciler（M1-T07，方案 §4.5）：投影与远端 PR 状态分叉的周期对账器，
 * 三 Reconciler 体系的第二格（M0 Publication Reconciler 管写命令收敛，本类管 PR 状态，
 * M1 DriftReconciler 管已发布资源存在性——分工轴见方案图 3-4）。
 *
 * <p>每轮（{@link #runOnce()} 单轮可测）：
 * <ol>
 *   <li>公平扫描：{@code WHERE state='OPEN' AND next_pr_reconcile_at<=now()
 *       ORDER BY next_pr_reconcile_at LIMIT :apiBudgetPerRound}——最久未查的先查，
 *       LIMIT 即 API 预算（默认 20/轮），不饿死尾部（修正 #7，E2E-14）；</li>
 *   <li>逐投影走 {@link PrEventAuthoritativeReader} 的同一棵判定树（§4.3，I12 不开新路）：
 *       探针事件 updatedAt=null → LWW 快筛必放行，直接权威读；漂移比对是
 *       <b>(head, base) 二元组</b>（E2E-10 修正：base 变更无专用事件接入，
 *       靠本对账在周期内收敛，压力点 M1-P8）；</li>
 *   <li>分支执行全部复用 webhook 路径的同一组方法：head/base 漂移 → 合成
 *       IntakeCommand 调 {@link IntakeService#dispatch} 走全量 T0/T1；
 *       closed/merged（含 404+sanity 通过）→ {@link ReviewOrchestrator#closeGeneration}；
 *       draft 化 → applyDraftPrecheck / convertToDraftGeneration；
 *       幂等收敛（IdempotentDone）→ 只 markReconciled；</li>
 *   <li>成功：{@code next=now()+interval（默认 30min）, error_count=0}；
 *       429/5xx/403/sanity 失败/派发异常：error_count+1 + 指数退避，
 *       429 的 retryAfter 与退避取大且<b>下一轮全局不早于该时刻</b>（EX-16）；
 *       error_count>=阈值（默认 3）→ ReconcilerDegraded 告警（措辞修正 #3：必须告警，
 *       否则对账覆盖率的盲区不可观测）。</li>
 * </ol>
 *
 * <p><b>幂等收敛（ST-21：webhook 与本对账器并发发现同 SHA 只产出一个 active Run）</b>：
 * 合成 intake 的 trigger_key = {@code reconciler:pr-state:{repo}#{pr}:{headSha}}（UT-16
 * 确定性），与 webhook 的 delivery_id 不同 → run_key 不同 → run_key 唯一约束拦不住
 * 双源并发（E2E-20 机制校正）。真正的收敛点有两道：
 * <ol>
 *   <li>T1 在 pr_subject 行锁（switchRevisionAndBumpEpoch 的 UPDATE）下做
 *       check-then-insert：后到事务持锁重读，先把先到方的 active Run SUPERSEDED
 *       再插自己的——最终恒只有一个 active Run，且此时双方都还没写 Outbox
 *       （Outbox 在 T2 才产生，输家 WorkItem 已被取消），无重复发布；</li>
 *   <li>DB 级兜底：V3 部分唯一索引 uq_review_run_active_gen（同 revision 同策略代
 *       active Run 唯一）——极端交错洞穿时后到方 INSERT 冲突，IntakeService 幂等
 *       回读/重试后上抛；本对账器按失败退避（markReconcileError），webhook 侧按
 *       RETRY_WAIT 退避，下一轮权威读命中 IdempotentDone 自愈。</li>
 * </ol>
 *
 * <p>对远端只读（I12）：所有 GitHub 交互收敛在权威读 port；本类不写 GitHub、
 * 不插 Outbox、不开新事务类型。一切过期/退避比较走 DB now()（I17），应用时钟只
 * 驱动循环节奏与 EX-16 的全局暂停（属"循环节奏"范畴）。
 *
 * <p>零 Spring 注解（同 InboxProcessor 范式），唯一装配点在
 * infrastructure/config/ReviewFlowConfig（docker profile）。
 */
public class PrStateReconciler {

    private static final Logger log = LoggerFactory.getLogger(PrStateReconciler.class);

    /** 合成 intake 的 trigger_key 前缀（UT-16）：与 webhook delivery_id 命名空间隔离 */
    public static final String TRIGGER_PREFIX = "reconciler:pr-state:";
    /** ReconcilerDegraded 默认阈值：连续失败 >= 3 次必须告警（措辞修正 #3，EX-12） */
    public static final int DEFAULT_DEGRADED_THRESHOLD = 3;
    private static final String PRODUCER = "control";

    private final PRSubjectRepository subjectRepository;
    private final PRRevisionRepository revisionRepository;
    private final ReviewRunRepository runRepository;
    private final PrEventAuthoritativeReader reader;
    private final ReviewOrchestrator orchestrator;
    private final IntakeService intakeService;
    private final ExecutionLedger ledger;
    /** 当前部署策略代（投影同步命令的占位 policy，与 InboxProcessor 同源语义） */
    private final String policyVersion;
    private final int apiBudgetPerRound;
    private final Duration normalInterval;
    private final Duration backoffBase;
    private final int degradedThreshold;
    private final long scanIntervalMs;
    private final long errorSleepMs;

    /**
     * EX-16 全局暂停游标：429 带 retryAfter 时，下一轮不早于 now+retryAfter。
     * 应用时钟只驱动循环节奏（I17 许可范围）；volatile 保证 worker 线程与测试线程间可见。
     */
    private volatile Instant globalPausedUntil;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public PrStateReconciler(PRSubjectRepository subjectRepository,
                             PRRevisionRepository revisionRepository,
                             ReviewRunRepository runRepository,
                             PrEventAuthoritativeReader reader,
                             ReviewOrchestrator orchestrator,
                             IntakeService intakeService,
                             ExecutionLedger ledger,
                             String policyVersion,
                             int apiBudgetPerRound, Duration normalInterval, Duration backoffBase,
                             int degradedThreshold, long scanIntervalMs, long errorSleepMs) {
        this.subjectRepository = Objects.requireNonNull(subjectRepository);
        this.revisionRepository = Objects.requireNonNull(revisionRepository);
        this.runRepository = Objects.requireNonNull(runRepository);
        this.reader = Objects.requireNonNull(reader);
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.intakeService = Objects.requireNonNull(intakeService);
        this.ledger = Objects.requireNonNull(ledger);
        this.policyVersion = Objects.requireNonNull(policyVersion);
        if (apiBudgetPerRound < 1) {
            throw new IllegalArgumentException("apiBudgetPerRound 必须 >= 1: " + apiBudgetPerRound);
        }
        this.apiBudgetPerRound = apiBudgetPerRound;
        this.normalInterval = Objects.requireNonNull(normalInterval);
        this.backoffBase = Objects.requireNonNull(backoffBase);
        this.degradedThreshold = degradedThreshold;
        this.scanIntervalMs = scanIntervalMs;
        this.errorSleepMs = errorSleepMs;
    }

    /** 单轮：公平扫描一批并逐投影对账；返回处理条数（测试与循环共用入口） */
    public int runOnce() {
        Instant pausedUntil = globalPausedUntil;
        if (pausedUntil != null && Instant.now().isBefore(pausedUntil)) {
            // EX-16：429 的 retryAfter 未到——全局暂停，本轮零 API 调用（无重试风暴）
            log.info("PrStateReconciler 全局暂停中（429 retryAfter 未到期 {}），本轮跳过", pausedUntil);
            return 0;
        }
        List<PRSubject> due = subjectRepository.findDueForReconcile(apiBudgetPerRound);
        for (PRSubject subject : due) {
            reconcileOne(subject);
        }
        return due.size();
    }

    /** 单投影对账：判定树决策（§4.3 同源）→ 分支执行（§4.4 同组方法）→ 调度回写 */
    private void reconcileOne(PRSubject subject) {
        try {
            switch (reader.decide(probeEvent(subject))) {
                case PrRouteDecision.IgnoredStale ignored ->
                        // 理论不可达：探针 updatedAt=null，LWW 快筛必放行（EX-18 语义）；防御性按成功处理
                        markOk(subject);
                case PrRouteDecision.IdempotentDone done ->
                        // ST-21 收敛点：远端与投影已一致且同策略代 active Run 在——零动作
                        markOk(subject);
                case PrRouteDecision.FullReview full -> {
                    dispatchSynthesized(subject, full.remote());
                    markOk(subject);
                }
                case PrRouteDecision.Reopen reopen -> {
                    // 理论不可达：探针事件 action 固定为 synchronize（probeEvent），
                    // Reopen 只由真实 reopened webhook 触发。防御性按 FullReview 处理——
                    // 对账发现"closed→open"漂移时 close 的 epoch bump 从未发生过，
                    // 当前 epoch 仍有效，无需补换届（INC-26 评审结论）。
                    dispatchSynthesized(subject, reopen.remote());
                    markOk(subject);
                }
                case PrRouteDecision.DraftPrecheck precheck -> {
                    // I11：draft 廉价预检，只刷投影，零 Run 零 Outbox
                    orchestrator.applyDraftPrecheck(toSyncCommand(subject, precheck.remote()));
                    markOk(subject);
                }
                case PrRouteDecision.ConvertToDraft draft -> {
                    // T-draft（I15）：epoch+1 + 在途 Run SUPERSEDED（webhook 丢失的 draft 化在此收敛）
                    orchestrator.convertToDraftGeneration(toSyncCommand(subject, draft.remote()));
                    markOk(subject);
                }
                case PrRouteDecision.Close close -> {
                    // T-close（I15）：含 404+sanity 通过路径（remote=null，投影用投影值兜底）
                    orchestrator.closeGeneration(toSyncCommand(subject, close.remote()));
                    markOk(subject);
                }
                case PrRouteDecision.Retry retry ->
                        // 403/429/5xx/404-sanity 失败：不动作 + error 计数 + 退避（EX-12/EX-16/EX-17 精神）
                        handleRetry(subject, retry);
            }
        } catch (Exception e) {
            // 派发/T-close 自身失败（如快照下载失败）：同权威读失败的退避语义（E2E-12 精神）
            handleFailure(subject, "dispatch_exception:" + e.getClass().getSimpleName(), null);
            log.warn("PrStateReconciler 对账异常 repo={} pr={}", subject.getRepositoryFullName(),
                    subject.getPrNumber(), e);
        }
    }

    /**
     * 探针事件（判定树的输入）：action=synthesize 语义上的 synchronize，updatedAt=null
     * ——对账没有"事件快照"，LWW 快筛不适用，必须权威读（EX-18：缺值不猜）。
     * (head, base) 二元组单独查 current revision 带入（§4.5/E2E-10）——仅作占位，
     * 判定树内一律以权威读远端值为准（修正 #6）；无 current revision（draft 期从未建过
     * Run）时填 "unknown"，该值不会进入任何决策（FullReview 分支以远端值重建事件，
     * Close 的 404 路径只用 state/draft/merged）。
     */
    private PullRequestEvent probeEvent(PRSubject subject) {
        String headSha = "unknown";
        String baseRef = "unknown";
        String baseSha = "unknown";
        if (subject.getCurrentRevisionId() != null) {
            Optional<PRRevision> revision = revisionRepository.findById(subject.getCurrentRevisionId());
            if (revision.isPresent()) {
                headSha = revision.get().getHeadSha();
                baseRef = revision.get().getBaseRef();
                baseSha = revision.get().getBaseSha();
            }
        }
        return new PullRequestEvent(
                TRIGGER_PREFIX + subject.getRepositoryFullName() + "#" + subject.getPrNumber() + ":probe",
                "synchronize",
                subject.getGithubInstallationId(), subject.getGithubRepositoryId(),
                subject.getRepositoryFullName(), subject.getPrNumber(),
                subject.getState() == PrSubjectState.OPEN ? "open" : "closed",
                subject.isDraft(), subject.isMerged(),
                headSha, baseRef, baseSha, null);
    }

    /**
     * head/base 漂移 → 合成 intake 走全量 T0/T1（I12：与 webhook 同一条 dispatch 路径）。
     * 合成的 raw payload 是审计性最小 JSON（dispatch 会落 CAS + WEBHOOK_PAYLOAD 登记，
     * 入口留痕不断链，INC-16 精神）；deliveryId 即 trigger_key（确定性，UT-16）。
     */
    private void dispatchSynthesized(PRSubject subject, FetchResult.Found remote) {
        String triggerKey = syntheticTriggerKey(
                subject.getRepositoryFullName(), subject.getPrNumber(), remote.headSha());
        PullRequestEvent synthetic = new PullRequestEvent(triggerKey, "synchronize",
                subject.getGithubInstallationId(), subject.getGithubRepositoryId(),
                subject.getRepositoryFullName(), subject.getPrNumber(),
                remote.state(), remote.draft(), remote.merged(),
                remote.headSha(), remote.baseRef(), remote.baseSha(), remote.updatedAt());
        intakeService.dispatch(synthetic, syntheticPayload(subject, remote, triggerKey));
        log.info("PrStateReconciler 检测到 (head,base) 漂移，合成 intake 补建 Run repo={} pr={} head={} trigger={}",
                subject.getRepositoryFullName(), subject.getPrNumber(), remote.headSha(), triggerKey);
    }

    /** UT-16：trigger_key 确定性合成——同 (repo, pr, headSha) 恒同 key，重放/并发可由幂等点收敛 */
    public static String syntheticTriggerKey(String repoFullName, int prNumber, String headSha) {
        return TRIGGER_PREFIX + repoFullName + "#" + prNumber + ":" + headSha;
    }

    /** 合成接收记录（落 CAS 的 raw）：sha/repo 名均为安全字符集，手工拼 JSON 足够 */
    private static byte[] syntheticPayload(PRSubject subject, FetchResult.Found remote, String triggerKey) {
        return ("{\"synthesized_by\":\"pr-state-reconciler\""
                + ",\"trigger_key\":\"" + triggerKey + "\""
                + ",\"repo\":\"" + subject.getRepositoryFullName() + "\""
                + ",\"pr_number\":" + subject.getPrNumber()
                + ",\"head_sha\":\"" + remote.headSha() + "\""
                + ",\"base_ref\":\"" + remote.baseRef() + "\""
                + ",\"base_sha\":\"" + remote.baseSha() + "\""
                + "}").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 决策载荷 → 投影同步命令：远端值优先。remote=null 只会出现在 Close 的 404 路径
     * （sanity 通过 = PR 真没了按关处理）——state 必须按 CLOSED 兜底（不能用投影原值，
     * 否则 404 反而把投影刷成 OPEN）；draft/merged 无远端事实可造，保留投影值（EX-18 精神）。
     */
    private ProjectionSyncCommand toSyncCommand(PRSubject subject, FetchResult.Found remote) {
        if (remote != null) {
            return new ProjectionSyncCommand(subject.getGithubInstallationId(),
                    subject.getGithubRepositoryId(), subject.getRepositoryFullName(), subject.getPrNumber(),
                    remote.isOpen() ? PrSubjectState.OPEN : PrSubjectState.CLOSED,
                    remote.draft(), remote.merged(), policyVersion, remote.updatedAt());
        }
        return new ProjectionSyncCommand(subject.getGithubInstallationId(),
                subject.getGithubRepositoryId(), subject.getRepositoryFullName(), subject.getPrNumber(),
                PrSubjectState.CLOSED, subject.isDraft(), subject.isMerged(), policyVersion, null);
    }

    /** 对账成功（含幂等收敛/零动作）：排下一轮 + 失败计数清零 */
    private void markOk(PRSubject subject) {
        subjectRepository.markReconciled(subject.getId(), normalInterval);
    }

    /** 权威读类失败：429 的 retryAfter 先挂全局暂停（EX-16），再走统一的计数+退避 */
    private void handleRetry(PRSubject subject, PrRouteDecision.Retry retry) {
        if (retry.retryAfter() != null) {
            Instant until = Instant.now().plus(retry.retryAfter());
            Instant current = globalPausedUntil;
            if (current == null || current.isBefore(until)) {
                globalPausedUntil = until; // 下一轮全局不早于该时刻（§4.5 原文，EX-16）
            }
        }
        handleFailure(subject, retry.reason(), retry.retryAfter());
    }

    /**
     * 失败统一落点（§4.5）：error_count+1 + 指数退避（与 inbox 同一曲线，
     * base * 2^(n-1)）；retryAfter 与退避取大——既不早于 GitHub 要求的时刻，
     * 也不低于自身退避曲线（EX-16）。新计数 >= 阈值 → ReconcilerDegraded（措辞修正 #3）。
     */
    private void handleFailure(PRSubject subject, String reason, Duration retryAfter) {
        int failedCount = subject.getPrReconcileErrorCount() + 1;
        Duration backoff = InboxProcessor.backoffForAttempt(failedCount, backoffBase);
        if (retryAfter != null && retryAfter.compareTo(backoff) > 0) {
            backoff = retryAfter;
        }
        int newCount = subjectRepository.markReconcileError(subject.getId(), backoff);
        log.warn("PrStateReconciler 对账失败 repo={} pr={} reason={} errorCount={} backoff={}s",
                subject.getRepositoryFullName(), subject.getPrNumber(), reason, newCount,
                backoff.toSeconds());
        if (newCount >= degradedThreshold) {
            emitDegraded(subject, reason, newCount);
        }
    }

    /**
     * ReconcilerDegraded 告警（措辞修正 #3：探测失败不冒充事实，但必须告警——M0 EX-04
     * MANUAL 熔断精神的延伸）。挂载规则（execution_event.review_run_id/pr_revision_id
     * 为 NOT NULL + FK，V1 schema 未给"无 Run 事件"留形状）：优先挂该 PR 的 active Run，
     * 无则挂最近 Run；该 PR 从未有过 Run（如纯 draft 期）则无法合法落库——
     * 以结构化 WARN 日志代账（已知偏差，交付报告明示；schema 决策留 V4）。
     */
    private void emitDegraded(PRSubject subject, String reason, int errorCount) {
        Map<String, Object> payload = Map.of(
                "reconciler", "pr-state",
                "repo", subject.getRepositoryFullName(),
                "pr_number", subject.getPrNumber(),
                "reason", reason == null ? "unknown" : reason,
                "error_count", errorCount,
                "threshold", degradedThreshold);
        Optional<ReviewRun> attach = runRepository.findActiveByPrSubjectId(subject.getId())
                .stream().findFirst()
                .or(() -> runRepository.findLatestByPrSubjectId(subject.getId()));
        if (attach.isPresent()) {
            ReviewRun run = attach.get();
            ledger.append(ledger.newEvent(run.getId(), run.getPrRevisionId(), null, null,
                    ExecutionEventType.RECONCILER_DEGRADED, null, run.getId(), PRODUCER, payload));
            log.error("ReconcilerDegraded 已落账 repo={} pr={} run={} errorCount={} reason={}",
                    subject.getRepositoryFullName(), subject.getPrNumber(), run.getId(),
                    errorCount, reason);
        } else {
            log.error("ReconcilerDegraded（无 Run 可挂，账本 schema 不允许无 Run 事件，日志代账） "
                    + "repo={} pr={} errorCount={} reason={} payload={}",
                    subject.getRepositoryFullName(), subject.getPrNumber(), errorCount, reason, payload);
        }
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = Thread.ofVirtual().name("pr-state-reconciler").start(this::loop);
            log.info("PrStateReconciler 启动 scanIntervalMs={} apiBudgetPerRound={} interval={}s",
                    scanIntervalMs, apiBudgetPerRound, normalInterval.toSeconds());
        }
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void loop() {
        while (running.get()) {
            try {
                runOnce();
                Thread.sleep(scanIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("PrStateReconciler 轮询异常", e);
                sleepQuietly(errorSleepMs);
            }
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
