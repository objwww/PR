package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.service.DeferredPolicy;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.domain.identity.InvestigationInputs;
import com.objwww.pr.control.release.application.CanaryRouter;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * EX-A4b（F24）：等待重驱扫描。WAITING_CAPABILITY（路由不意愿）/DEFERRED（背压暂扣）
 * 的 FIRING 事故在此补铸 run/task——路由意愿恢复（canary 放量）且 backlog 回落后，
 * 事故不再依赖"下一次告警"才获得 RCA；重驱前保持"尚未调查"可见（waitingReason）。
 *
 * <p>幂等/竞态面：投影事务与新告警路径经 incident 行锁 + uq_rca_run_active_incident
 * 兜底；已有活跃 run 的等待行跳过（清等待由投影/收尾面负责）。重驱无告警载荷，
 * severity 缺席 → SlaPolicy 默认优先级（如实缺省，不伪造升级语义）。
 */
public class IncidentWaitingRedrive {

    private static final Logger log = LoggerFactory.getLogger(IncidentWaitingRedrive.class);

    private final IncidentRepository incidents;
    private final RcaRunRepository runs;
    private final RcaTaskRepository tasks;
    private final CanaryRouter canaryRouter;
    private final DeferredPolicy deferredPolicy;
    private final SlaPolicy sla;
    private final AlertClock clock;
    private final Duration pollInterval;
    private final TransactionOperations tx;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread worker;

    public IncidentWaitingRedrive(IncidentRepository incidents,
                                  RcaRunRepository runs,
                                  RcaTaskRepository tasks,
                                  CanaryRouter canaryRouter,
                                  DeferredPolicy deferredPolicy,
                                  SlaPolicy sla,
                                  AlertClock clock,
                                  Duration pollInterval,
                                  TransactionOperations tx) {
        this.incidents = Objects.requireNonNull(incidents);
        this.runs = Objects.requireNonNull(runs);
        this.tasks = Objects.requireNonNull(tasks);
        this.canaryRouter = Objects.requireNonNull(canaryRouter);
        this.deferredPolicy = Objects.requireNonNull(deferredPolicy);
        this.sla = Objects.requireNonNull(sla);
        this.clock = Objects.requireNonNull(clock);
        this.pollInterval = Objects.requireNonNull(pollInterval);
        // BA-146：route() 决策行（WHITELISTED 出路带 run_id）与 insertRouted() run 行是
        // V31 deferred FK 的原子对——无事务包裹则决策行语句级提交即 23503（195 白名单
        // 放行实证）；本类被内部 worker 线程直接调 redriveOnce()，@Transactional 代理
        // 不可达，必须显式 TransactionOperations（AlertInboxProcessor 同律）
        this.tx = Objects.requireNonNull(tx);
    }

    /** 单轮重驱：返回本轮补铸的 run 数 */
    public synchronized int redriveOnce() {
        int cast = 0;
        for (Incident incident : incidents.findWaitingForRedrive()) {
            if (runs.findActiveByIncidentId(incident.id()).isPresent()) {
                continue;
            }
            if (deferredPolicy.decide(incidents.countActive(), tasks.countQueued())
                    == DeferredPolicy.Decision.DEFERRED) {
                continue;
            }
            // BA-146：决策行+run/task/incident 更新=单事务原子组（V31 deferred FK
            // 提交点检查）；任一失败整组回滚，事故留在等待集下轮重试
            Boolean minted = tx.execute(status -> mintRunAndTask(incident));
            if (Boolean.TRUE.equals(minted)) {
                cast++;
            }
        }
        return cast;
    }

    /**
     * 定向重查（人工驳回后的显式补链入口，与扫描同闸同事务）：
     * 活跃 run 在则不重铸（uq_rca_run_active_incident 同兜底）；路由意愿不在
     * （非白名单 → HOLMES 意愿）如实返回 false，不伪装成交付。
     */
    public boolean redriveIncident(UUID incidentId) {
        Incident incident = incidents.findById(incidentId).orElse(null);
        if (incident == null || runs.findActiveByIncidentId(incidentId).isPresent()) {
            return false;
        }
        Boolean minted = tx.execute(status -> mintRunAndTask(incident));
        return Boolean.TRUE.equals(minted);
    }

    /** 单事故补铸（事务内）：路由意愿仍不在则不铸，返回 false */
    private boolean mintRunAndTask(Incident incident) {
        UUID runId = UUID.randomUUID();
        var routing = canaryRouter.route(
                runId, incident.incidentKey(), incident.incidentKey());
        if (routing.engine() == RcaEngine.HOLMES) {
            return false;
        }
        Instant now = clock.now();
        Digest basis = incident.pendingInvestigationHash() != null
                ? incident.pendingInvestigationHash()
                : incident.lastInvestigationHash() != null
                ? incident.lastInvestigationHash()
                : Digest.sha256Of("redrive|" + incident.incidentKey());
        RcaRun run = new RcaRun(runId, incident.id(), incident.generation(),
                RunTrigger.INITIAL, RcaRunState.QUEUED, basis, now, now, null, null, null,
                // SR §3.1：重驱铸 run 也是生产准入（铸造点三处同闸）
                com.objwww.pr.control.alert.domain.model.RunPurpose.PRODUCTION,
                "incident-waiting-redrive", null);
        runs.insertRouted(run, routing, InvestigationInputs.freezeAt(incident, now));
        int priority = sla.priority(null);
        // SR §4.1：铸点冻结对账硬期限
        runs.fixReconcileDeadlineIfAbsent(run.id(), sla.deadline(now, priority));
        tasks.insert(new RcaTask(UUID.randomUUID(), run.id(),
                RcaTask.taskKeyFor(routing.engine()), RcaTaskState.READY, priority,
                now, now, sla.deadline(now, priority), null, null, 0, 0, 3, now, now));
        incidents.update(new Incident(incident.id(), incident.incidentKey(),
                incident.status(), incident.generation(),
                incident.episodeStartedAt(), incident.lastFiringStartsAt(),
                incident.resolvedAt(),
                incident.lastInvestigationHash(), basis,
                incident.receivedCount(), incident.distinctEventCount(),
                incident.notificationCount(),
                run.id(),
                incident.firstSeenAt(), incident.lastEventAt(), incident.createdAt(), now));
        log.info("incident {} 等待重驱完成：原因={} 路由决策={} run={}",
                incident.id(), incident.waitingReason(), routing.decision(), run.id());
        return true;
    }

    /** 启动常驻循环（幂等：已启动则忽略） */
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            worker = Thread.ofVirtual().name("incident-waiting-redrive").start(() -> {
                while (running.get()) {
                    try {
                        int cast = redriveOnce();
                        if (cast == 0) {
                            Thread.sleep(pollInterval.toMillis());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (RuntimeException e) {
                        log.error("等待重驱循环异常，{} 后重试", pollInterval, e);
                        try {
                            Thread.sleep(pollInterval.toMillis());
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            });
            log.info("IncidentWaitingRedrive 启动 interval={}", pollInterval);
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }
}
