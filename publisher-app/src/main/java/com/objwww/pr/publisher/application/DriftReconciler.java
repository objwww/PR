package com.objwww.pr.publisher.application;

import com.objwww.pr.publisher.domain.handler.PublicationHandler;
import com.objwww.pr.publisher.domain.handler.ReconcileVerdict;
import com.objwww.pr.publisher.domain.model.DriftCheckTarget;
import com.objwww.pr.publisher.domain.port.ExecutionEventAppender;
import com.objwww.pr.publisher.domain.port.PayloadReader;
import com.objwww.pr.publisher.domain.port.PayloadUnavailableException;
import com.objwww.pr.publisher.domain.port.PublicationStore;
import com.objwww.pr.publisher.domain.service.FencedPublicationExecutor;
import com.objwww.pr.publisher.domain.service.RetryBackoff;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.PublicationResourceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DriftReconciler（M1-T08，方案 §4.6；措辞修正 #2/#3 落点）：publisher 侧资源漂移巡检器，
 * 三 Reconciler 体系的第三格——管"已发布资源还在不在"（M0 Publication Reconciler 管写命令
 * 收敛，M1 PrStateReconciler 管 PR 状态，分工轴见方案图 3-4）。
 *
 * <p>每轮（{@link #runOnce()} 单轮可测）：
 * <ol>
 *   <li>公平巡检：{@code PublicationStore.findDueForDriftCheck} —— PRESENT/MISSING 且命令
 *       CONFIRMED 且 next_check_at 到期的资源，按 next_check_at 升序 LIMIT（最久未查先查，
 *       LIMIT 即 API 预算，默认 50/轮，E2E-15 不饿死尾部）；</li>
 *   <li>探测：<b>零新增触网</b>——复用 {@link FencedPublicationExecutor#reconcile} 的既有
 *       PublicationHandler reconcile 探针（AFT-13：本类不引用 GitHubWriteAdapter、不引用
 *       任何写方法、不引用 Outbox 插入路径）；</li>
 *   <li>判定：
 *     <ul>
 *       <li>在（FOUND）→ markCheckedPresent：last_checked_at=now()、next_check_at=now()+interval
 *           （默认 60min）、error_count=0；MISSING 复核找回也归 PRESENT；</li>
 *       <li>404（NOT_FOUND / MANUAL_POLICY）→ <b>sanity 读</b>（GET repo 级探针，确认
 *           token/权限/仓库可达；F-3：GitHub 以 404 替代 403 隐藏私有资源，404 本身无法区分
 *           "不存在"与"无权限"）：通过 → markMissing + PUBLICATION_DRIFT_DETECTED 账本事件
 *           （恰好一次：状态已 MISSING 的重复扫描不再发，守卫在 store 行锁侧，ST-22）；
 *           不通过 → markUnknown + 权限告警事件（E2E-18：权限异常绝不冒充"不存在"）；</li>
 *       <li>5xx/超时/429（UNKNOWN）→ 状态不动，markCheckError：error_count+1 + 指数退避；
 *           error_count &gt;= 阈值（默认 3）→ ReconcilerDegraded 告警（措辞修正 #3：探测失败
 *           不冒充事实，但必须告警，EX-14；429 按同一退避曲线处理不产生重试风暴，EX-16 同原则——
 *           精确的 retry-after 尊重受 TypedResponse 契约（不含响应头）所限，见交付报告偏差）；</li>
 *     </ul>
 *   </li>
 *   <li>MISSING 低频复核：markMissing 时 next_check_at = now() + interval×factor
 *       （factor 默认 8，可配）。</li>
 * </ol>
 *
 * <p><b>只检测，不修复</b>（v2.2 §1 + CT-20）：本类不改 outbox_command、不插新命令、不重发、
 * 不删远端对象；全部 DB 写只落 publication_resource 观测列（state/drift_detected_at/
 * last_checked_at/next_check_at/check_error_count——V3 列级授权边界内）与 execution_event
 * 只追加账本。一切时间比较走 DB now()（I17），应用时钟只驱动循环节奏。
 *
 * <p>零 Spring 注解（同 OutboxRecoveryScanner 范式），唯一装配点在
 * infrastructure/config/PublisherWiringConfig（docker profile）。
 */
public class DriftReconciler {

    private static final Logger log = LoggerFactory.getLogger(DriftReconciler.class);
    /** ReconcilerDegraded 默认阈值：连续失败 >= 3 次必须告警（措辞修正 #3，EX-14） */
    public static final int DEFAULT_DEGRADED_THRESHOLD = 3;
    private static final String PRODUCER = "publisher-app";

    private final PublicationStore store;
    private final FencedPublicationExecutor executor;
    private final PayloadReader payloadReader;
    private final ExecutionEventAppender eventAppender;
    private final Map<CommandType, PublicationHandler> handlers;
    private final RetryBackoff backoff = new RetryBackoff();
    private final int budgetPerRound;
    private final Duration checkInterval;
    private final Duration missingRecheckInterval;
    private final int degradedThreshold;
    private final long idleSleepMs;
    private final long errorSleepMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public DriftReconciler(PublicationStore store, FencedPublicationExecutor executor,
                           PayloadReader payloadReader, ExecutionEventAppender eventAppender,
                           List<PublicationHandler> handlerList,
                           int budgetPerRound, Duration checkInterval, int missingRecheckFactor,
                           int degradedThreshold, long idleSleepMs, long errorSleepMs) {
        this.store = Objects.requireNonNull(store);
        this.executor = Objects.requireNonNull(executor);
        this.payloadReader = Objects.requireNonNull(payloadReader);
        this.eventAppender = Objects.requireNonNull(eventAppender);
        if (budgetPerRound < 1) {
            throw new IllegalArgumentException("budgetPerRound 必须 >= 1: " + budgetPerRound);
        }
        if (missingRecheckFactor < 1) {
            throw new IllegalArgumentException("missingRecheckFactor 必须 >= 1: " + missingRecheckFactor);
        }
        this.budgetPerRound = budgetPerRound;
        this.checkInterval = Objects.requireNonNull(checkInterval);
        this.missingRecheckInterval = checkInterval.multipliedBy(missingRecheckFactor);
        this.degradedThreshold = degradedThreshold;
        this.idleSleepMs = idleSleepMs;
        this.errorSleepMs = errorSleepMs;
        this.handlers = new EnumMap<>(CommandType.class);
        for (PublicationHandler handler : handlerList) {
            this.handlers.put(handler.commandType(), handler);
        }
    }

    /** 单轮公平巡检；返回处理条数（测试与循环共用入口） */
    public int runOnce() {
        List<DriftCheckTarget> due = store.findDueForDriftCheck(budgetPerRound);
        for (DriftCheckTarget target : due) {
            checkOne(target);
        }
        return due.size();
    }

    /** 单资源巡检：探针 → 判定树（§4.6 原文）→ 观测列回写 */
    private void checkOne(DriftCheckTarget target) {
        ReconcileVerdict verdict;
        try {
            verdict = executor.reconcile(target.command());
        } catch (Exception e) {
            // 探针编排自身异常（如 payload 缺必需字段）：同探测失败的退避语义，不冒充事实
            log.warn("DriftReconciler 探针异常 resource={} remoteId={}", target.resourceId(),
                    target.remoteId(), e);
            handleCheckError(target, "probe_exception:" + e.getClass().getSimpleName());
            return;
        }
        switch (verdict.kind()) {
            case FOUND -> store.markCheckedPresent(target.resourceId(), checkInterval);
            case NOT_FOUND, MANUAL_POLICY ->
                // 404：单资源探针（GET_CHECK_RUN）直给；列表探针窗口内穷尽（执行器裁决）
                    handleNotFound(target);
            case UNKNOWN -> handleCheckError(target, "probe_unknown"); // 5xx/超时/429/形态异常
        }
    }

    /**
     * 404 分支：sanity 读确证（F-3）。sanity 通过 = 真没了 → MISSING + 单次漂移事件；
     * sanity 失败 = 无法区分权限撤销与不存在 → UNKNOWN + 权限告警（E2E-18），
     * 绝不标 MISSING。
     */
    private void handleNotFound(DriftCheckTarget target) {
        String repo = repoOf(target);
        if (repo == null) {
            // 本地 payload 不可读 = 无法发起 sanity → 按探测失败退避（不做任何状态判定）
            handleCheckError(target, "payload_unavailable");
            return;
        }
        PublicationHandler handler = handlers.get(target.command().commandType());
        boolean sane = executor.sanityRead(handler.buildSanityProbe(repo));
        if (sane) {
            boolean newly = store.markMissing(target.resourceId(), missingRecheckInterval,
                    target.state() == PublicationResourceState.MISSING ? null : driftEvent(target));
            if (newly) {
                log.warn("资源漂移确认 MISSING resource={} type={} remoteId={} repo={}",
                        target.resourceId(), target.resourceType(), target.remoteId(), repo);
            }
        } else if (target.state() == PublicationResourceState.MISSING) {
            // 低频复核期遇权限抖动：既有 MISSING 结论不回退，只重排期（告警首轮已发）
            store.markMissing(target.resourceId(), missingRecheckInterval, null);
            log.warn("MISSING 复核 sanity 失败（保持 MISSING 仅重排期）resource={} repo={}",
                    target.resourceId(), repo);
        } else {
            store.markUnknown(target.resourceId(), permissionAlertEvent(target, repo));
            log.error("sanity 读失败，资源标 UNKNOWN + 权限告警（E2E-18/F-3）resource={} repo={}",
                    target.resourceId(), repo);
        }
    }

    /** 探测失败统一落点：error_count+1 + 指数退避；达阈值 → ReconcilerDegraded（EX-14/措辞修正 #3） */
    private void handleCheckError(DriftCheckTarget target, String reason) {
        Duration delay = Duration.between(Instant.now(),
                backoff.nextAttemptAt(target.checkErrorCount() + 1, Instant.now()));
        int newCount = store.markCheckError(target.resourceId(), delay);
        if (newCount <= 0) {
            return; // 状态守卫未命中（并发下已被移出巡检集），本轮放弃
        }
        log.warn("DriftReconciler 探测失败 resource={} remoteId={} reason={} errorCount={}",
                target.resourceId(), target.remoteId(), reason, newCount);
        if (newCount >= degradedThreshold) {
            eventAppender.append(newEvent(target, ExecutionEventType.RECONCILER_DEGRADED, Map.of(
                    "reconciler", "drift",
                    "resource_id", target.resourceId().toString(),
                    "remote_id", target.remoteId(),
                    "reason", reason,
                    "error_count", newCount,
                    "threshold", degradedThreshold)));
            log.error("ReconcilerDegraded 已落账（drift）resource={} errorCount={} reason={}",
                    target.resourceId(), newCount, reason);
        }
    }

    /** sanity 读所需的 repo 取自创建命令的 payload（CAS；同 executor.reconcile 的数据源） */
    private String repoOf(DriftCheckTarget target) {
        try {
            Object repo = payloadReader.read(target.command().payloadHash()).get("repo");
            return repo == null || repo.toString().isBlank() ? null : repo.toString();
        } catch (PayloadUnavailableException e) {
            return null;
        }
    }

    private ExecutionEvent driftEvent(DriftCheckTarget target) {
        return newEvent(target, ExecutionEventType.PUBLICATION_DRIFT_DETECTED, Map.of(
                "resource_id", target.resourceId().toString(),
                "resource_type", target.resourceType().name(),
                "remote_id", target.remoteId(),
                "operation_id", target.command().operationId().toString()));
    }

    private ExecutionEvent permissionAlertEvent(DriftCheckTarget target, String repo) {
        return newEvent(target, ExecutionEventType.PUBLICATION_DRIFT_PERMISSION_ALERT, Map.of(
                "resource_id", target.resourceId().toString(),
                "resource_type", target.resourceType().name(),
                "remote_id", target.remoteId(),
                "repo", repo,
                "reason", "sanity_read_failed"));
    }

    /** 事件挂载：review_run_id/pr_revision_id 取创建命令的（execution_event 两列 NOT NULL + FK） */
    private ExecutionEvent newEvent(DriftCheckTarget target, ExecutionEventType type,
                                    Map<String, Object> payload) {
        return new ExecutionEvent(UUID.randomUUID(), target.command().reviewRunId(),
                target.command().prRevisionId(), null, null, type, 1, null,
                target.command().reviewRunId(), PRODUCER, payload, Instant.now());
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = Thread.ofVirtual().name("drift-reconciler").start(this::loop);
            log.info("DriftReconciler 启动 budgetPerRound={} interval={}s missingRecheck={}s",
                    budgetPerRound, checkInterval.toSeconds(), missingRecheckInterval.toSeconds());
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
                log.error("DriftReconciler 轮询异常", e);
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
