package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.AlertEvent;
import com.objwww.pr.control.alert.domain.model.AlertFiringStatus;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.AlertEventRepository;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.service.AlertIdentityFactory;
import com.objwww.pr.control.alert.domain.service.DeferredPolicy;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.domain.statemachine.IncidentStateMachine;
import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 投影器（§6.7 单事务算法）：拆组 → 逐 alert 软背压 → alert_event 幂等追加 →
 * incident upsert（episode 水印乱序收敛）→ 铸 rca_run + HOLMES_INVESTIGATE task。
 *
 * <p>"单事务"指 event/incident/run/task 四表的原子性，由调用方
 * （AlertInboxProcessor）经 TransactionOperations 包裹；inbox 行自身的状态流转在事务外，
 * 崩溃缝隙由租约过期回收 + event 幂等兜底（重投时重复 alert 只累加计数，不重铸 run）。
 *
 * <p>三计数语义（V7 注释对齐）：received_count=每次 alert 到达；
 * distinct_event_count=新 event 落库；notification_count=重复通知（撞 uq_alert_event_dedup）。
 *
 * <p>乱序策略（§6.7 + BA-12①/G0-07 修正）：episode 水印 = episode_started_at；
 * startsAt &lt; 水印的晚到事件（firing 或 resolved）只计数，不覆盖状态、不动水印、不铸 run。
 * 同 episode 内 resolved 先于 firing 到达的迟到 firing（startsAt &lt; resolvedAt）
 * 同样只追加 event + 计数——不是再现，不复活 incident、不铸 run（否则 incident 卡死 FIRING，
 * 真正的 resolved 已被消费，再无事件能关掉它）。
 */
public class IncidentProjector {

    /** 投影结果（AlertInboxProcessor 据此决定 inbox 行终局/重投） */
    public record ProjectOutcome(int immediateCount, int deferredCount, int duplicateCount) {
        public int totalAlerts() {
            return immediateCount + deferredCount;
        }
    }

    private final AlertEventRepository events;
    private final IncidentRepository incidents;
    private final RcaRunRepository runs;
    private final RcaTaskRepository tasks;
    private final AlertIdentityFactory identity;
    private final DeferredPolicy deferredPolicy;
    private final SlaPolicy sla;
    private final AlertClock clock;

    public IncidentProjector(AlertEventRepository events,
                             IncidentRepository incidents,
                             RcaRunRepository runs,
                             RcaTaskRepository tasks,
                             AlertIdentityFactory identity,
                             DeferredPolicy deferredPolicy,
                             SlaPolicy sla,
                             AlertClock clock) {
        this.events = Objects.requireNonNull(events);
        this.incidents = Objects.requireNonNull(incidents);
        this.runs = Objects.requireNonNull(runs);
        this.tasks = Objects.requireNonNull(tasks);
        this.identity = Objects.requireNonNull(identity);
        this.deferredPolicy = Objects.requireNonNull(deferredPolicy);
        this.sla = Objects.requireNonNull(sla);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 整组投影。BA-13③：backlog 计数在组首查一次，组内用增量估算
     * （每条非重复的立即投影至多多出 1 活跃 incident + 1 排队 task 的保守上界），
     * 洪峰时组内前 N 条投完、后续自然 DEFERRED——ST-A07 语义不变。
     * 载荷腐坏抛 IllegalArgumentException（调用方 DEAD_LETTER）。
     */
    public ProjectOutcome project(UUID inboxId, List<ParsedAlert> alerts) {
        Objects.requireNonNull(inboxId, "inboxId");
        int immediate = 0;
        int deferred = 0;
        int duplicates = 0;
        int activeIncidents = incidents.countActive();
        int queuedTasks = tasks.countQueued();
        for (ParsedAlert alert : alerts) {
            if (deferredPolicy.decide(activeIncidents, queuedTasks)
                    == DeferredPolicy.Decision.DEFERRED) {
                deferred++;
                continue;
            }
            boolean duplicate = projectAlert(inboxId, alert);
            if (!duplicate) {
                activeIncidents++;
                queuedTasks++;
            }
            if (duplicate) {
                duplicates++;
            }
            immediate++;
        }
        return new ProjectOutcome(immediate, deferred, duplicates);
    }

    /** 单条投影；返回 true = 重复通知（去重键已存在，仅计数）。 */
    private boolean projectAlert(UUID inboxId, ParsedAlert alert) {
        // 缺 alertname 抛 IllegalArgumentException → 整组 DEAD_LETTER（入口未校验该标签，投影期兜底）
        String key = identity.incidentKey(alert.labels());
        Digest payloadHash = identity.payloadHash(alert.status(), alert.labels(), alert.startsAt());
        Digest invHash = identity.investigationHash(alert.labels(), alert.annotations());
        Instant now = clock.now();

        Incident incident = incidents.findByKeyForUpdate(key).orElse(null);
        boolean firstSeen = incident == null;
        if (firstSeen) {
            incident = insertIncident(key, alert, now);
            try {
                incidents.insert(incident);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // BA-13④:同 key 首见并发,另一事务已抢先插入——重读既有行按"非首见"合并,
                // 不让 uq_incident_key 撞库放大成整组失败
                incident = incidents.findByKeyForUpdate(key).orElseThrow();
                firstSeen = false;
            }
        }

        // 去重预判（incident 行锁串行化同 key 投影，预判与追加之间无竞态；uq 为最终防线）
        boolean duplicate = events.existsByDedup(alert.fingerprint(), payloadHash, alert.startsAt());

        MergeResult merged = merge(incident, alert, invHash, duplicate, firstSeen, now);
        incidents.update(merged.incident());

        if (!duplicate) {
            // generation 取 merge 后的 episode 归属（RESOLVED→FIRING 再现事件属于新 episode）
            events.append(new AlertEvent(UUID.randomUUID(), inboxId, incident.id(),
                    merged.incident().generation(), alert.fingerprint(), alert.status(),
                    alert.labels(), alert.annotations(), alert.startsAt(), alert.endsAt(),
                    payloadHash, invHash, now));
        }
        if (merged.castRun() != null) {
            castRunAndTask(merged.incident(), alert, invHash, merged.castRun(), now);
        }
        return duplicate;
    }

    /** 首见告警铸 incident 骨架（三计数从 0 起，累计统一在 merge——避免首条双计） */
    private Incident insertIncident(String key, ParsedAlert alert, Instant now) {
        boolean firing = alert.status() == AlertFiringStatus.FIRING;
        return new Incident(UUID.randomUUID(), key,
                firing ? IncidentStatus.FIRING : IncidentStatus.RESOLVED,
                0,
                alert.startsAt(),
                firing ? alert.startsAt() : null,
                firing ? null : now,
                null, null,
                0, 0, 0,
                null,
                now, now, now, now);
    }

    // ------------------------------------------------------------------ upsert 合并算法

    /** merge 输出：更新后的 incident + 是否需要铸新 run（null=不铸） */
    private record MergeResult(Incident incident, RunTrigger castRun) {
    }

    private MergeResult merge(Incident incident, ParsedAlert alert, Digest invHash,
                              boolean duplicate, boolean firstSeen, Instant now) {
        // 首见：骨架状态/水印已由 insertIncident 定，只计首条；firing 首见无条件铸 INITIAL
        // （没有上一轮调查，不进 RERUN 判定——lastInvestigationHash=null 与任何新材料都"不等"）
        if (firstSeen) {
            Incident counted = withCounts(incident, incident.receivedCount() + 1,
                    incident.distinctEventCount() + (duplicate ? 0 : 1),
                    incident.notificationCount() + (duplicate ? 1 : 0), now);
            RunTrigger cast = alert.status() == AlertFiringStatus.FIRING && !duplicate
                    ? castRunIfFree(counted, RunTrigger.INITIAL) : null;
            return new MergeResult(counted, cast);
        }

        // 重复通知：payloadHash 相同 → labels 必相同 → 材料必相同（双哈希推导），只累加计数
        if (duplicate) {
            return new MergeResult(withCounts(incident, incident.receivedCount() + 1,
                    incident.distinctEventCount(), incident.notificationCount() + 1, now), null);
        }

        boolean withinEpisode = !alert.startsAt().isBefore(incident.episodeStartedAt());
        // 晚到（startsAt < 水印）：只计数，不覆盖状态/水印/材料（§6.7 乱序策略）
        if (!withinEpisode) {
            return new MergeResult(withCounts(incident, incident.receivedCount() + 1,
                    incident.distinctEventCount() + 1, incident.notificationCount(), now), null);
        }

        if (alert.status() == AlertFiringStatus.FIRING) {
            if (incident.status() == IncidentStatus.RESOLVED) {
                // BA-12①/G0-07 同 episode 乱序防御：resolved 已定局，迟到的 firing
                // （startsAt 早于 resolve 时刻）不是再现——只追加 event + 计数。
                // 真再现的 startsAt 必然晚于上一次 resolve 时刻。
                if (incident.resolvedAt() != null
                        && alert.startsAt().isBefore(incident.resolvedAt())) {
                    return new MergeResult(withCounts(incident, incident.receivedCount() + 1,
                            incident.distinctEventCount() + 1, incident.notificationCount(), now),
                            null);
                }
                // 再现 = 新 episode（使用状态机计算 generation，§6.7）
                int newGeneration = IncidentStateMachine.nextGeneration(
                        incident.status(), IncidentStatus.FIRING, incident.generation());
                Incident revived = new Incident(incident.id(), incident.incidentKey(),
                        IncidentStatus.FIRING, newGeneration,
                        alert.startsAt(), alert.startsAt(), null,
                        incident.lastInvestigationHash(), null,
                        incident.receivedCount() + 1, incident.distinctEventCount() + 1,
                        incident.notificationCount(),
                        incident.currentRcaRunId(),
                        incident.firstSeenAt(), now, incident.createdAt(), now);
                return new MergeResult(revived, castRunIfFree(revived, RunTrigger.INITIAL));
            }
            // FIRING 持续：更新 lastFiringStartsAt；材料变化走 pending/rerun 判定
            Instant lastFiring = maxTs(incident.lastFiringStartsAt(), alert.startsAt());
            Incident updated = new Incident(incident.id(), incident.incidentKey(),
                    incident.status(), incident.generation(),
                    incident.episodeStartedAt(), lastFiring, incident.resolvedAt(),
                    incident.lastInvestigationHash(), incident.pendingInvestigationHash(),
                    incident.receivedCount() + 1, incident.distinctEventCount() + 1,
                    incident.notificationCount(),
                    incident.currentRcaRunId(),
                    incident.firstSeenAt(), now, incident.createdAt(), now);
            if (runs.findActiveByIncidentId(incident.id()).isPresent()) {
                // 调查中：材料变化只记 pending（ST-A05：连续变化覆盖，收尾时一次 rerun）
                if (!invHash.equals(incident.pendingInvestigationHash())
                        && !invHash.equals(incident.lastInvestigationHash())) {
                    return new MergeResult(withPending(updated, invHash), null);
                }
                return new MergeResult(updated, null);
            }
            // 无活跃 run：材料变化才值得重查（RERUN）；材料未变 = 重复调查无益，不铸
            if (!invHash.equals(incident.lastInvestigationHash())) {
                return new MergeResult(updated, RunTrigger.RERUN);
            }
            return new MergeResult(updated, null);
        }

        // 有效 resolved：FIRING→RESOLVED（generation 保持；run 收尾由 finishTask 处理，T06）
        Incident resolved = new Incident(incident.id(), incident.incidentKey(),
                IncidentStatus.RESOLVED, incident.generation(),
                incident.episodeStartedAt(), incident.lastFiringStartsAt(), now,
                incident.lastInvestigationHash(), incident.pendingInvestigationHash(),
                incident.receivedCount() + 1, incident.distinctEventCount() + 1,
                incident.notificationCount(),
                incident.currentRcaRunId(),
                incident.firstSeenAt(), now, incident.createdAt(), now);
        return new MergeResult(resolved, null);
    }

    /** 新 episode 的 run 铸造前置：确认没有活跃 run 残留（收尾缝隙防御，uq 兜底 23505） */
    private RunTrigger castRunIfFree(Incident incident, RunTrigger trigger) {
        return runs.findActiveByIncidentId(incident.id()).isPresent() ? null : trigger;
    }

    private void castRunAndTask(Incident incident, ParsedAlert alert, Digest invHash,
                                RunTrigger trigger, Instant now) {
        RcaRun run = new RcaRun(UUID.randomUUID(), incident.id(), incident.generation(),
                trigger, RcaRunState.QUEUED, invHash, now, now, null, null, null);
        runs.insert(run);

        int priority = sla.priority(alert.labels().get("severity"));
        RcaTask task = new RcaTask(UUID.randomUUID(), run.id(), RcaTask.HOLMES_INVESTIGATE,
                RcaTaskState.READY, priority, now, now, sla.deadline(now, priority),
                null, null, 0, 0, 3, now, now);
        tasks.insert(task);

        // pending = 本轮 run 的调查材料基准；收尾算法（§6.7 finishTask）以其变化判 rerun
        incidents.update(new Incident(incident.id(), incident.incidentKey(),
                incident.status(), incident.generation(),
                incident.episodeStartedAt(), incident.lastFiringStartsAt(), incident.resolvedAt(),
                incident.lastInvestigationHash(), invHash,
                incident.receivedCount(), incident.distinctEventCount(),
                incident.notificationCount(),
                run.id(),
                incident.firstSeenAt(), incident.lastEventAt(), incident.createdAt(), now));
    }

    private Incident withCounts(Incident i, long received, long distinct, long notifications,
                                Instant now) {
        return new Incident(i.id(), i.incidentKey(), i.status(), i.generation(),
                i.episodeStartedAt(), i.lastFiringStartsAt(), i.resolvedAt(),
                i.lastInvestigationHash(), i.pendingInvestigationHash(),
                received, distinct, notifications,
                i.currentRcaRunId(),
                i.firstSeenAt(), now, i.createdAt(), now);
    }

    private Incident withPending(Incident i, Digest pending) {
        return new Incident(i.id(), i.incidentKey(), i.status(), i.generation(),
                i.episodeStartedAt(), i.lastFiringStartsAt(), i.resolvedAt(),
                i.lastInvestigationHash(), pending,
                i.receivedCount(), i.distinctEventCount(), i.notificationCount(),
                i.currentRcaRunId(),
                i.firstSeenAt(), i.lastEventAt(), i.createdAt(), i.lastEventAt());
    }

    private static Instant maxTs(Instant a, Instant b) {
        return a == null || b.isAfter(a) ? b : a;
    }
}
