package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.model.WebhookInbox;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort.FetchResult;
import com.objwww.pr.control.domain.repository.WebhookInboxRepository;
import com.objwww.pr.control.interfaces.webhook.GitHubWebhookParser;
import com.objwww.pr.control.interfaces.webhook.MalformedPayloadException;
import com.objwww.pr.control.interfaces.webhook.PullRequestEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * InboxProcessor（M1-T04 worker 段 + T05/T06 真路由，方案 §4.2/§4.3/§4.4）：
 * webhook 事件的真正处理者。HTTP 线程（{@code WebhookController}）只验签+落 inbox；
 * 本 worker 循环：租约领取（SKIP LOCKED）→ 解析 → LWW 快筛 + 权威读路由 → 租约匹配回写终态。
 *
 * <p>恰好一次的构成（方案 §4.2，三段各防一个窗口，ST-17 逐一验证）：
 * <ol>
 *   <li>窗口①：领取后、T1 前 kill——inbox 行停在 PROCESSING，租约过期后被崩溃回收
 *       重领（lease_epoch+1 栅栏旧主），重放整条 dispatch；Run 尚未建，重放即首建；</li>
 *   <li>窗口②：T1 已提交、inbox 未回写 kill——回收重放会再走 T1，靠 T1 内部幂等
 *       （run_key 唯一约束 + findExistingRun 幂等返回，B-3）保证恰好一个 Run；
 *       T05 起权威读会先命中 IdempotentDone 收敛点（ST-21），连 T1 都不进；</li>
 *   <li>窗口③：inbox 回写 PROCESSED 后 kill——行已终态，重投由入口主键去重
 *       按原结果应答（200 duplicate），零重放。</li>
 * </ol>
 *
 * <p>路由（T05/T06，方案 §4.3 判定树 + §4.4 决策表）：决策由
 * {@link PrEventAuthoritativeReader} 纯产出（不写库），本类按决策驱动执行：
 * IgnoredStale→IGNORED；IdempotentDone→PROCESSED；Retry→RETRY_WAIT（尊重 retryAfter，
 * EX-16）；FullReview→IntakeService.dispatch（T0/T1）；DraftPrecheck→只刷投影（I11）；
 * ConvertToDraft/Close→T-draft/T-close（epoch+1 + Run SUPERSEDED，I15）。
 *
 * <p>回写一律租约匹配（lease_owner + lease_epoch，I14）：返回 0 行 = 本 Processor 已被
 * 崩溃回收接管，晚到结果不生效，仅记日志。一切时间比较走 DB now()（I17），
 * 应用侧只传 Duration 时长。
 *
 * <p>零 Spring 注解（同 OutboxRecoveryScanner 范式），唯一装配点在
 * infrastructure/config/ReviewFlowConfig（docker profile）。
 */
public class InboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboxProcessor.class);

    private final WebhookInboxRepository inbox;
    private final IntakeService intakeService;
    private final PrEventAuthoritativeReader reader;
    private final ReviewOrchestrator orchestrator;
    /** 当前部署策略代（draft/close 路径新建投影行的占位 policy，见 ProjectionSyncCommand） */
    private final String policyVersion;
    private final GitHubWebhookParser parser = new GitHubWebhookParser();
    private final String workerId;
    private final Duration leaseTtl;
    private final int claimLimit;
    private final Duration backoffBase;
    /** 配置上限；与行内 max_attempts 列取小（行内 CHECK ck_inbox_attempts 是硬边界） */
    private final int maxAttempts;
    private final long idleSleepMs;
    private final long errorSleepMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public InboxProcessor(WebhookInboxRepository inbox, IntakeService intakeService,
                          PrEventAuthoritativeReader reader, ReviewOrchestrator orchestrator,
                          String policyVersion,
                          String workerId, Duration leaseTtl, int claimLimit,
                          Duration backoffBase, int maxAttempts,
                          long idleSleepMs, long errorSleepMs) {
        this.inbox = Objects.requireNonNull(inbox);
        this.intakeService = Objects.requireNonNull(intakeService);
        this.reader = Objects.requireNonNull(reader);
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.policyVersion = Objects.requireNonNull(policyVersion);
        this.workerId = Objects.requireNonNull(workerId);
        this.leaseTtl = Objects.requireNonNull(leaseTtl);
        this.claimLimit = claimLimit;
        this.backoffBase = Objects.requireNonNull(backoffBase);
        this.maxAttempts = maxAttempts;
        this.idleSleepMs = idleSleepMs;
        this.errorSleepMs = errorSleepMs;
    }

    /** 单轮：领取一批并逐条处理；返回处理条数（测试与循环共用入口） */
    public int runOnce() {
        List<WebhookInbox> claimed = inbox.claim(claimLimit, workerId, leaseTtl);
        for (WebhookInbox row : claimed) {
            processOne(row);
        }
        return claimed.size();
    }

    private void processOne(WebhookInbox row) {
        String deliveryId = row.getDeliveryId();
        long epoch = row.getLeaseEpoch();

        byte[] raw = inbox.payloadRaw(deliveryId);
        if (raw == null) {
            // 防御：领取到却读不到 raw（理论不可达）——不处理，租约到期自然回收
            log.warn("inbox 行已领取但读不到 payload_raw delivery={}", deliveryId);
            return;
        }

        // 1) 路由前轻解析：畸形 JSON（即落库时 payload_json=NULL 的行，E2E-22）
        //    → 直接 DEAD_LETTER(malformed)，不建 Run、不重试（重放不会变合法），raw 留档可审计
        final String action;
        try {
            action = parser.readEntryMeta(raw).action();
        } catch (MalformedPayloadException e) {
            int updated = inbox.completeDeadLetter(deliveryId, workerId, epoch,
                    errorJson("malformed_json", "payload 不是合法 JSON（E2E-22）"));
            logLeaseLostIfZero(updated, row, "DEAD_LETTER");
            return;
        }

        // 2) 非 pull_request 或 action 不在六 action → IGNORED 终态留痕
        //    （ST-16；INC-16 关闭：入口不再无声拒绝，一切签名合法事件有审计行）
        if (!parser.isHandled(row.getGithubEvent(), action)) {
            int updated = inbox.completeIgnored(deliveryId, workerId, epoch);
            logLeaseLostIfZero(updated, row, "IGNORED");
            return;
        }

        // 3) 六 action → 全量解析 → LWW 快筛 + 权威读路由（T05/T06，方案 §4.3/§4.4）
        try {
            PullRequestEvent event = parser.parsePullRequest(raw, deliveryId, action);
            route(row, event, raw);
        } catch (MalformedPayloadException e) {
            // 合法 JSON 但缺必需字段：载荷不可变，重试永远不会成功 → 同 E2E-22 裁决进死信
            int updated = inbox.completeDeadLetter(deliveryId, workerId, epoch,
                    errorJson("malformed_payload", e.getMessage()));
            logLeaseLostIfZero(updated, row, "DEAD_LETTER");
        } catch (Exception e) {
            failRetryable(row, e);
        }
    }

    /**
     * 真路由（T05/T06）：决策由 {@link PrEventAuthoritativeReader} 产出，本方法按分支执行
     * 并回写终态（决策与执行分离，方案 §3.1 Reader 行"不写库不构造命令"）。
     * 各分支抛出的异常上抛给 processOne 的 failRetryable（EX-11 退避/耗尽语义不变）。
     */
    private void route(WebhookInbox row, PullRequestEvent event, byte[] raw) {
        String deliveryId = row.getDeliveryId();
        long epoch = row.getLeaseEpoch();
        switch (reader.decide(event)) {
            case PrRouteDecision.IgnoredStale ignored -> {
                // ST-11：明显陈旧的乱序事件，零 API 拦截
                int updated = inbox.completeIgnored(deliveryId, workerId, epoch);
                logLeaseLostIfZero(updated, row, "IGNORED");
            }
            case PrRouteDecision.IdempotentDone done -> {
                // ST-21 收敛点：远端与投影已一致且同策略代 active Run 存在，零动作
                int updated = inbox.completeProcessed(deliveryId, workerId, epoch);
                logLeaseLostIfZero(updated, row, "PROCESSED");
            }
            case PrRouteDecision.Retry retry ->
                    // EX-16：429 尊重 Retry-After；403/5xx/404-sanity 失败走退避
                    failDecisionRetry(row, retry.reason(), retry.retryAfter());
            case PrRouteDecision.FullReview full -> {
                // 以远端值为准构造事件（图 3-2：权威读拦截乱序，payload 里的 head 只是快照）
                FetchResult.Found remote = full.remote();
                PullRequestEvent authoritative = event.withRemoteState(remote.state(), remote.draft(),
                        remote.merged(), remote.headSha(), remote.baseRef(), remote.baseSha(),
                        remote.updatedAt());
                intakeService.dispatch(authoritative, raw);
                int updated = inbox.completeProcessed(deliveryId, workerId, epoch);
                logLeaseLostIfZero(updated, row, "PROCESSED");
            }
            case PrRouteDecision.Reopen reopen -> {
                // T-reopen（I15/ST-20，INC-26）：先换届（投影 OPEN + epoch+1 + 在途 Run
                // SUPERSEDED，重放幂等不重复 bump），再以远端值为准走全量 T0/T1。
                // 换届与 T1 分两笔事务：崩溃重放时换届幂等跳过，T1 由唯一索引兜底幂等。
                FetchResult.Found remote = reopen.remote();
                orchestrator.reopenGeneration(toSyncCommand(event, remote));
                PullRequestEvent authoritative = event.withRemoteState(remote.state(), remote.draft(),
                        remote.merged(), remote.headSha(), remote.baseRef(), remote.baseSha(),
                        remote.updatedAt());
                intakeService.dispatch(authoritative, raw);
                int updated = inbox.completeProcessed(deliveryId, workerId, epoch);
                logLeaseLostIfZero(updated, row, "PROCESSED");
            }
            case PrRouteDecision.DraftPrecheck precheck -> {
                // I11/ST-12：只刷投影 + 水印，零 T0/Run/Outbox/模型
                orchestrator.applyDraftPrecheck(toSyncCommand(event, precheck.remote()));
                int updated = inbox.completeProcessed(deliveryId, workerId, epoch);
                logLeaseLostIfZero(updated, row, "PROCESSED");
            }
            case PrRouteDecision.ConvertToDraft draft -> {
                // T-draft（I15）：同事务 投影 draft=true + epoch+1 + 在途 Run SUPERSEDED
                orchestrator.convertToDraftGeneration(toSyncCommand(event, draft.remote()));
                int updated = inbox.completeProcessed(deliveryId, workerId, epoch);
                logLeaseLostIfZero(updated, row, "PROCESSED");
            }
            case PrRouteDecision.Close close -> {
                // T-close（I15）：同事务 投影 CLOSED + epoch+1 + 在途 Run SUPERSEDED；
                // 404 路径 remote=null → 事件载荷兜底（PR 真没了按关处理，EX-17 精神）
                orchestrator.closeGeneration(toSyncCommand(event, close.remote()));
                int updated = inbox.completeProcessed(deliveryId, workerId, epoch);
                logLeaseLostIfZero(updated, row, "PROCESSED");
            }
        }
    }

    /** 决策载荷 → 投影同步命令：远端值优先；404（remote=null）时用事件载荷兜底 */
    private ProjectionSyncCommand toSyncCommand(PullRequestEvent event, FetchResult.Found remote) {
        if (remote != null) {
            return new ProjectionSyncCommand(event.installationId(), event.repositoryId(),
                    event.repositoryFullName(), event.prNumber(),
                    remote.isOpen() ? PrSubjectState.OPEN : PrSubjectState.CLOSED,
                    remote.draft(), remote.merged(), policyVersion, remote.updatedAt());
        }
        return new ProjectionSyncCommand(event.installationId(), event.repositoryId(),
                event.repositoryFullName(), event.prNumber(),
                "closed".equalsIgnoreCase(event.prState()) ? PrSubjectState.CLOSED : PrSubjectState.OPEN,
                event.draft(), event.merged(), policyVersion, event.updatedAt());
    }

    /** 权威读类失败（403/429/5xx/sanity 失败）：与 dispatch 失败同语义，但尊重 retryAfter（EX-16） */
    private void failDecisionRetry(WebhookInbox row, String reason, Duration retryAfter) {
        String deliveryId = row.getDeliveryId();
        long epoch = row.getLeaseEpoch();
        int failedAttempt = row.getAttemptCount() + 1;
        int effectiveMax = Math.min(row.getMaxAttempts(), maxAttempts);
        if (failedAttempt >= effectiveMax) {
            int updated = inbox.completeDeadLetter(deliveryId, workerId, epoch,
                    errorJson("authoritative_read_exhausted", reason));
            if (updated > 0) {
                log.error("inbox 权威读重试耗尽转 DEAD_LETTER delivery={} reason={} attempts={}/{}",
                        deliveryId, reason, failedAttempt, effectiveMax);
            } else {
                logLeaseLostIfZero(0, row, "DEAD_LETTER");
            }
            return;
        }
        // retryAfter 与指数退避取大：既不早于 GitHub 要求的时刻，也不低于自身退避曲线（EX-16）
        Duration backoff = backoffForAttempt(failedAttempt, backoffBase);
        if (retryAfter != null && retryAfter.compareTo(backoff) > 0) {
            backoff = retryAfter;
        }
        int updated = inbox.completeRetryWait(deliveryId, workerId, epoch, backoff,
                errorJson("authoritative_read_retry", reason));
        if (updated > 0) {
            log.warn("inbox 权威读暂败转 RETRY_WAIT delivery={} reason={} attempt={}/{} backoff={}s",
                    deliveryId, reason, failedAttempt, effectiveMax, backoff.toSeconds());
        } else {
            logLeaseLostIfZero(0, row, "RETRY_WAIT");
        }
    }

    /** 可恢复失败：attempt+1 未达上限 → RETRY_WAIT 指数退避；达上限 → DEAD_LETTER（EX-11/CT-16） */
    private void failRetryable(WebhookInbox row, Exception e) {
        String deliveryId = row.getDeliveryId();
        long epoch = row.getLeaseEpoch();
        // 递增落点在回写 SQL（attempt_count+1）；此处算的是"本次失败后的累计次数"
        int failedAttempt = row.getAttemptCount() + 1;
        int effectiveMax = Math.min(row.getMaxAttempts(), maxAttempts);
        if (failedAttempt >= effectiveMax) {
            int updated = inbox.completeDeadLetter(deliveryId, workerId, epoch,
                    errorJson("dispatch_exhausted", e.getClass().getSimpleName() + ": " + e.getMessage()));
            if (updated > 0) {
                log.error("inbox 重试耗尽转 DEAD_LETTER delivery={} attempts={}/{}",
                        deliveryId, failedAttempt, effectiveMax, e);
            } else {
                logLeaseLostIfZero(0, row, "DEAD_LETTER");
            }
        } else {
            Duration backoff = backoffForAttempt(failedAttempt, backoffBase);
            int updated = inbox.completeRetryWait(deliveryId, workerId, epoch, backoff,
                    errorJson("dispatch_failed", e.getClass().getSimpleName() + ": " + e.getMessage()));
            if (updated > 0) {
                log.warn("inbox 派发失败转 RETRY_WAIT delivery={} attempt={}/{} backoff={}s",
                        deliveryId, failedAttempt, effectiveMax, backoff.toSeconds());
            } else {
                logLeaseLostIfZero(0, row, "RETRY_WAIT");
            }
        }
    }

    /**
     * 指数退避（§4.2）：next_retry_at = now + base * 2^(failedAttempt-1)，时间落 DB now()（I17）。
     * failedAttempt 从 1 起（首次失败退避 base）；指数封顶 2^16 防 Duration 溢出。
     */
    static Duration backoffForAttempt(int failedAttempt, Duration base) {
        if (failedAttempt < 1) {
            throw new IllegalArgumentException("failedAttempt 从 1 起: " + failedAttempt);
        }
        return base.multipliedBy(1L << Math.min(failedAttempt - 1, 16));
    }

    /** I14：租约匹配回写 0 行 = 已被崩溃回收接管，晚到结果不生效 */
    private void logLeaseLostIfZero(int updated, WebhookInbox row, String targetState) {
        if (updated == 0) {
            log.warn("inbox 回写 {} 未生效（租约已失，晚到不生效 I14） delivery={} epoch={}",
                    targetState, row.getDeliveryId(), row.getLeaseEpoch());
        }
    }

    /** last_error 是 jsonb 列：手工构造最小合法 JSON（消息截断 + 转义，防注入坏行） */
    private static String errorJson(String kind, String message) {
        return "{\"kind\":\"" + escape(kind) + "\",\"message\":\"" + escape(message) + "\"}";
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        String truncated = s.length() > 500 ? s.substring(0, 500) : s;
        StringBuilder sb = new StringBuilder(truncated.length() + 16);
        for (char c : truncated.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = Thread.ofVirtual().name("inbox-processor").start(this::loop);
            log.info("InboxProcessor 启动 worker={}", workerId);
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
                if (runOnce() == 0) {
                    Thread.sleep(idleSleepMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("InboxProcessor 轮询异常", e);
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
