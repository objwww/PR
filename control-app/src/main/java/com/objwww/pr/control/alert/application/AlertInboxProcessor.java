package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.AlertInbox;
import com.objwww.pr.control.alert.domain.model.InboxDecision;
import com.objwww.pr.control.alert.domain.model.InboxState;
import com.objwww.pr.control.alert.domain.repository.AlertInboxRepository;
import com.objwww.pr.control.alert.domain.statemachine.InboxStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * inbox 六态消费循环（零注解虚拟线程 worker，旧线 InboxProcessor 同型）：
 * claim（SKIP LOCKED+租约）→ 拆组 → 投影单事务（TransactionOperations 包裹，
 * 装配给 TransactionTemplate，测试给 withoutTransaction）→ inbox 行终局流转。
 *
 * <p>行终局四路径：
 * <ul>
 *   <li>全 immediate → PROCESSED/ACCEPTED；</li>
 *   <li>有 deferred → RETRY_WAIT + decision=DEFERRED（软背压审计，§6.4；
 *       backlog 回落后 claimNext 按 next_retry_at 重领补投，ST-A07）；</li>
 *   <li>载荷腐坏/缺 alertname（IllegalArgumentException）→ DEAD_LETTER（重试无意义）；</li>
 *   <li>DB 故障（DataAccessException）→ RETRY_WAIT 退避重试，attempt 耗尽 DEAD_LETTER。</li>
 * </ul>
 * inbox 行流转在投影事务外：崩溃缝隙由租约过期回收 + event 幂等兜底（重投不重铸 run）。
 *
 * <p>状态机接线清单（BA-11①/G0-05，{@link InboxStateMachine} 为唯一迁移权威）：
 * <ul>
 *   <li>本类（领取后 state=PROCESSING 起步的五个写点，全部先经状态机校验）：
 *       markDeadLetter×3（PROCESSING→DEAD_LETTER）、markIgnored（→IGNORED）、
 *       complete（→PROCESSED）、scheduleRetry（→RETRY_WAIT / 耗尽→DEAD_LETTER）；</li>
 *   <li>仓储契约侧（SQL 条件迁移，不经本类，语义须与迁移表对齐）：
 *       claimNext（RECEIVED/RETRY_WAIT→PROCESSING）、reclaimExpired
 *       （PROCESSING→RECEIVED，仅租约过期回收路径）。</li>
 * </ul>
 */
public class AlertInboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(AlertInboxProcessor.class);

    /** processOnce 的行终局（测试与观测断言用） */
    public enum Outcome {ACCEPTED, DEFERRED, RETRY_SCHEDULED, DEAD_LETTER, IGNORED, SKIPPED}

    private final AlertInboxRepository inbox;
    private final IncidentProjector projector;
    private final TransactionOperations tx;
    private final AlertClock clock;
    private final String owner;
    private final Duration inboxLease;
    private final Duration deferBackoff;
    private final Duration errorBackoff;
    private final Duration pollInterval;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread worker;

    public AlertInboxProcessor(AlertInboxRepository inbox,
                               IncidentProjector projector,
                               TransactionOperations tx,
                               AlertClock clock,
                               String owner,
                               Duration inboxLease,
                               Duration deferBackoff,
                               Duration errorBackoff,
                               Duration pollInterval) {
        this.inbox = Objects.requireNonNull(inbox);
        this.projector = Objects.requireNonNull(projector);
        this.tx = Objects.requireNonNull(tx);
        this.clock = Objects.requireNonNull(clock);
        this.owner = Objects.requireNonNull(owner);
        this.inboxLease = Objects.requireNonNull(inboxLease);
        this.deferBackoff = Objects.requireNonNull(deferBackoff);
        this.errorBackoff = Objects.requireNonNull(errorBackoff);
        this.pollInterval = Objects.requireNonNull(pollInterval);
    }

    /** 消费一行；无可领行返回 SKIPPED（空转由循环层节流） */
    public Outcome processOnce() {
        Instant now = clock.now();
        Optional<AlertInbox> claimed = inbox.claimNext(owner, now, inboxLease);
        if (claimed.isEmpty()) {
            return Outcome.SKIPPED;
        }
        AlertInbox row = claimed.get();
        // 领取即 PROCESSING（仓储契约）；此后一切行改写先过状态机（G0-05）
        List<ParsedAlert> alerts;
        try {
            alerts = AlertPayloadParser.parse(row.envelope().payloadRaw());
        } catch (IllegalArgumentException e) {
            // 载荷腐坏/协议漂移：重试无意义，直接死信审计
            requireTransition(row, InboxState.DEAD_LETTER);
            inbox.markDeadLetter(row.id(), row.leaseEpoch(), "payload-corrupt: " + e.getMessage(), now);
            log.warn("alert_inbox {} 死信: {}", row.id(), e.getMessage());
            return Outcome.DEAD_LETTER;
        }
        if (alerts.isEmpty()) {
            requireTransition(row, InboxState.IGNORED);
            inbox.markIgnored(row.id(), row.leaseEpoch(), now);
            return Outcome.IGNORED;
        }

        try {
            IncidentProjector.ProjectOutcome outcome =
                    tx.execute(status -> projector.project(row.id(), alerts));
            if (outcome.deferredCount() > 0) {
                scheduleRetry(row, InboxDecision.DEFERRED,
                        "backlog-deferred " + outcome.deferredCount() + "/" + outcome.totalAlerts(),
                        deferBackoff, now);
                return Outcome.DEFERRED;
            }
            requireTransition(row, InboxState.PROCESSED);
            inbox.complete(row.id(), row.leaseEpoch(), InboxDecision.ACCEPTED, now);
            return Outcome.ACCEPTED;
        } catch (IllegalArgumentException e) {
            // 投影期协议缺陷（如缺 alertname）：重试无意义
            requireTransition(row, InboxState.DEAD_LETTER);
            inbox.markDeadLetter(row.id(), row.leaseEpoch(), "project-reject: " + e.getMessage(), now);
            log.warn("alert_inbox {} 死信: {}", row.id(), e.getMessage());
            return Outcome.DEAD_LETTER;
        } catch (DataAccessException e) {
            scheduleRetry(row, null, "db-error: " + e.getClass().getSimpleName(), errorBackoff, now);
            return Outcome.RETRY_SCHEDULED;
        }
    }

    /** 行终局/重试改写前的迁移校验（禁止绕过状态机直接构造目标态，BA-11①） */
    private static void requireTransition(AlertInbox row, InboxState to) {
        InboxStateMachine.requireTransition(row.state(), to);
    }

    /** attempt+1 后将达 max_attempts 则直接死信（不再进 RETRY_WAIT 空转一轮） */
    private void scheduleRetry(AlertInbox row, InboxDecision decision, String error,
                               Duration backoff, Instant now) {
        if (row.attemptCount() + 1 >= row.maxAttempts()) {
            requireTransition(row, InboxState.DEAD_LETTER);
            inbox.markDeadLetter(row.id(), row.leaseEpoch(),
                    "attempt-exhausted: " + error, now);
            return;
        }
        requireTransition(row, InboxState.RETRY_WAIT);
        inbox.scheduleRetry(row.id(), row.leaseEpoch(), decision, error, now.plus(backoff), now);
    }

    /** 启动常驻循环（幂等：已启动则忽略） */
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            worker = Thread.ofVirtual().name("alert-inbox-" + owner).start(this::loop);
            log.info("AlertInboxProcessor 启动 owner={}", owner);
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private void loop() {
        while (running.get()) {
            try {
                inbox.reclaimExpired(clock.now());
                Outcome outcome = processOnce();
                if (outcome == Outcome.SKIPPED) {
                    Thread.sleep(pollInterval.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("inbox 消费循环异常，{} 后重试", pollInterval, e);
                try {
                    Thread.sleep(pollInterval.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
