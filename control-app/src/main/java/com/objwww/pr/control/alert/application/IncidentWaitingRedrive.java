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
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread worker;

    public IncidentWaitingRedrive(IncidentRepository incidents,
                                  RcaRunRepository runs,
                                  RcaTaskRepository tasks,
                                  CanaryRouter canaryRouter,
                                  DeferredPolicy deferredPolicy,
                                  SlaPolicy sla,
                                  AlertClock clock,
                                  Duration pollInterval) {
        this.incidents = Objects.requireNonNull(incidents);
        this.runs = Objects.requireNonNull(runs);
        this.tasks = Objects.requireNonNull(tasks);
        this.canaryRouter = Objects.requireNonNull(canaryRouter);
        this.deferredPolicy = Objects.requireNonNull(deferredPolicy);
        this.sla = Objects.requireNonNull(sla);
        this.clock = Objects.requireNonNull(clock);
        this.pollInterval = Objects.requireNonNull(pollInterval);
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
            UUID runId = UUID.randomUUID();
            var routing = canaryRouter.route(
                    runId, incident.incidentKey(), incident.incidentKey());
            if (routing.engine() == RcaEngine.HOLMES) {
                continue;
            }
            Instant now = clock.now();
            Digest basis = incident.pendingInvestigationHash() != null
                    ? incident.pendingInvestigationHash()
                    : incident.lastInvestigationHash() != null
                    ? incident.lastInvestigationHash()
                    : Digest.sha256Of("redrive|" + incident.incidentKey());
            RcaRun run = new RcaRun(runId, incident.id(), incident.generation(),
                    RunTrigger.INITIAL, RcaRunState.QUEUED, basis, now, now, null, null, null);
            runs.insertRouted(run, routing, InvestigationInputs.freezeAt(incident, now));
            int priority = sla.priority(null);
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
            cast++;
            log.info("incident {} 等待重驱完成：原因={} 路由决策={} run={}",
                    incident.id(), incident.waitingReason(), routing.decision(), run.id());
        }
        return cast;
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
