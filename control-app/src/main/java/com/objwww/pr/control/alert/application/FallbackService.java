package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository.RoutingView;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.RunFallbackRepository;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.infrastructure.observability.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * run 级 fallback（M6-04，V33）：NATIVE run 的安全/运行故障恰一次铸 HOLMES RERUN
 * （技术方案 §4.2 第三层回退；FUT-12 语义分歧绝不回退）。
 *
 * <p>恰一次语义（C-68）：uq_rf_source(source_native_run_id) 表级唯一占位先行，
 * 同一事务内再铸 HOLMES run/task——占位与铸造同生共死，崩溃缝隙要么全有要么全无；
 * {@code fallback_of} 事件只是审计副本，不承担唯一性。
 *
 * <p>触发判定（封闭集合，fail-closed——未登记的错误类一律不触发）：
 * <ul>
 *   <li>引擎裁定：只有 NATIVE 路由 run 可进入（routing 面唯一权威）。fallback run
 *       本身是 HOLMES（plain insert → engine 列 DB 默认），结构性不会再进入本方法
 *       ——depth=1 封死（V33 check 兜底）；</li>
 *   <li>错误类裁定：仅安全/运行故障域（本集封闭）。取消（CANCELLED）/过期（EXPIRED，
 *       走 finishTask STALE fence 不到这里）/人工终止（同前）/UNRESOLVED（诚实结论
 *       不是故障）/低质量（ADAPTER_PACKAGE_REJECTED）绝不触发；PLAN_REJECTED /
 *       PROPOSAL_REJECTED 由 Supervisor 先置 run FAILED → finishTask STALE fence
 *       结构性排除；</li>
 *   <li>incident 忙裁定：同 incident 已有活跃 run（材料变化 RERUN 已上位）→ 不再
 *       fallback（incident 已被覆盖，无空窗）；</li>
 *   <li>独立预算：时间窗内 fallback 总量受 {@code app.alert.fallback.daily-budget}
 *       约束（真栈 LLM 花费护栏，与 canary 预算分账）。</li>
 * </ul>
 *
 * <p>调用面：{@code RcaRunOrchestrator.finishTask} 失败收尾分支，同事务调用
 * （占位/铸 run/事件与 run FAILED 收尾原子）。占位已胜而铸 run 撞
 * uq_rca_run_active_incident 的竞态窄窗（并发铸造者恰在判定后落活跃 run）：
 * 败者以 INCIDENT_BUSY 收场、占位行保留为审计（incident 已被竞态胜者覆盖，无空窗）。
 */
public final class FallbackService {

    private static final Logger log = LoggerFactory.getLogger(FallbackService.class);

    /** 独立预算计数窗（冻结默认：滚动 24h，M6-04） */
    public static final Duration BUDGET_WINDOW = Duration.ofHours(24);

    /**
     * 安全/运行故障域封闭错误类（fail-closed：新错误类必须显式登记才可触发）。
     * 安全类现由工具控制面在 DAG 内降级续跑（不炸 run），故本集当前只有运行域；
     * 未来安全类浮出 run 级失败时显式加入。
     */
    public static final Set<String> ELIGIBLE_ERROR_CLASSES = Set.of(
            "TIMEOUT", "EXECUTOR_ERROR", "EXECUTOR_MISSING",
            "REPORTING_NOT_ENTERED", "CONFIG_DIGEST_MISSING", "PROPOSAL_MISSING");

    /** 铸造裁定（观测/测试断言面；同时是 metrics outcome 标签封闭集） */
    public enum CastOutcome {
        CAST, ALREADY_CAST, DISABLED, INELIGIBLE_ENGINE, INELIGIBLE_ERROR_CLASS,
        INCIDENT_BUSY, BUDGET_EXHAUSTED
    }

    private final RcaRunRepository runs;
    private final IncidentRepository incidents;
    private final RcaTaskRepository tasks;
    private final RcaEventAppender events;
    private final RunFallbackRepository fallbacks;
    private final SlaPolicy sla;
    private final AlertClock clock;
    private final AlertMetrics metrics;
    private final boolean enabled;
    private final int dailyBudget;

    public FallbackService(RcaRunRepository runs,
                           IncidentRepository incidents,
                           RcaTaskRepository tasks,
                           RcaEventAppender events,
                           RunFallbackRepository fallbacks,
                           SlaPolicy sla,
                           AlertClock clock,
                           AlertMetrics metrics,
                           boolean enabled,
                           int dailyBudget) {
        this.runs = Objects.requireNonNull(runs);
        this.incidents = Objects.requireNonNull(incidents);
        this.tasks = Objects.requireNonNull(tasks);
        this.events = Objects.requireNonNull(events);
        this.fallbacks = Objects.requireNonNull(fallbacks);
        this.sla = Objects.requireNonNull(sla);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
        if (dailyBudget < 1) {
            throw new IllegalArgumentException("fallback 预算从 1 起");
        }
        this.enabled = enabled;
        this.dailyBudget = dailyBudget;
    }

    /**
     * 失败收尾分支调用（调用方以收尾事务包裹）。返回封闭裁定供观测/测试断言；
     * 除 INCIDENT_BUSY 竞态窄窗（见类注）外本方法不抛，不拖垮收尾事务。
     */
    public CastOutcome tryCastFromFailedNative(RcaRun failedRun, String errorClass, int priority) {
        Instant now = clock.now();
        CastOutcome outcome = decide(failedRun, errorClass, priority, now);
        metrics.fallbackDecision(outcome.name());
        StructuredLog.event(log, "rca_fallback_decision", Map.ofEntries(
                Map.entry("source_run_id", failedRun.id().toString()),
                Map.entry("incident_id", failedRun.incidentId().toString()),
                Map.entry("error_class", String.valueOf(errorClass)),
                Map.entry("outcome", outcome.name())));
        return outcome;
    }

    private CastOutcome decide(RcaRun failedRun, String errorClass, int priority, Instant now) {
        if (!enabled) {
            return CastOutcome.DISABLED;
        }
        // 引擎裁定：NATIVE 路由唯一权威；无路由行 = 存量 HOLMES 语义（V25），不触发
        RcaEngine engine = runs.findRoutingById(failedRun.id())
                .map(RoutingView::engine)
                .orElse(RcaEngine.HOLMES);
        if (engine != RcaEngine.NATIVE) {
            return CastOutcome.INELIGIBLE_ENGINE;
        }
        if (!ELIGIBLE_ERROR_CLASSES.contains(errorClass)) {
            return CastOutcome.INELIGIBLE_ERROR_CLASS;
        }
        if (fallbacks.countCreatedSince(now.minus(BUDGET_WINDOW)) >= dailyBudget) {
            return CastOutcome.BUDGET_EXHAUSTED;
        }
        Incident incident = incidents.findByIdForUpdate(failedRun.incidentId()).orElse(null);
        if (incident == null) {
            return CastOutcome.INCIDENT_BUSY;
        }
        // ① 唯一占位（C-68：表级唯一先行，事件只是审计副本）——先于 incident 忙裁定：
        //    同源重放（进程重启再触发）在占位面即判 ALREADY_CAST，不误报 INCIDENT_BUSY
        UUID fallbackRunId = UUID.randomUUID();
        boolean claimed = fallbacks.insertOccupancy(new RunFallbackRepository.OccupancyRow(
                failedRun.id(), incident.id(), failedRun.generation(), fallbackRunId, 1,
                errorClass, now));
        if (!claimed) {
            return CastOutcome.ALREADY_CAST;
        }
        if (runs.findActiveByIncidentId(incident.id()).isPresent()) {
            // 资格已被本次触发消耗（占位行留审计），但 incident 已被活跃 run 覆盖，无空窗
            return CastOutcome.INCIDENT_BUSY;
        }
        try {
            // ② 同事务铸 HOLMES RERUN（plain insert → engine 列 DB 默认 HOLMES，
            //    无路由语义；task_key 按 HOLMES 选，worker 分派面走 holmes 执行器）
            runs.insert(new RcaRun(fallbackRunId, incident.id(), failedRun.generation(),
                    RunTrigger.RERUN, RcaRunState.QUEUED, failedRun.investigationHash(),
                    now, now, null, null, null));
            tasks.insert(new RcaTask(UUID.randomUUID(), fallbackRunId,
                    RcaTask.taskKeyFor(RcaEngine.HOLMES), RcaTaskState.READY, priority,
                    now, now, sla.deadline(now, priority), null, null, 0, 0, 3, now, now));
            // ③ incident 指针上移
            incidents.update(new Incident(incident.id(), incident.incidentKey(), incident.status(),
                    incident.generation(), incident.episodeStartedAt(), incident.lastFiringStartsAt(),
                    incident.resolvedAt(), incident.lastInvestigationHash(), null,
                    incident.receivedCount(), incident.distinctEventCount(), incident.notificationCount(),
                    fallbackRunId, incident.firstSeenAt(), incident.lastEventAt(),
                    incident.createdAt(), now));
            // ④ fallback_of 审计事件（状态事实：join 收尾事务，回滚一并回滚）
            events.append(fallbackRunId, new RcaEventAppender.EventDraft(
                    UUID.randomUUID(), "fallback_of", eventPayload(failedRun, errorClass)));
        } catch (DuplicateKeyException busy) {
            // 竞态窄窗（见类注）：判定后并发铸造者已为本 incident 落活跃 run——
            // 本侧以 INCIDENT_BUSY 收场，占位行保留为审计（incident 已被胜者覆盖，无空窗）
            return CastOutcome.INCIDENT_BUSY;
        }
        log.warn("NATIVE run {} 失败（{}）→ 恰一次铸 HOLMES fallback run {}",
                failedRun.id(), errorClass, fallbackRunId);
        return CastOutcome.CAST;
    }

    private static String eventPayload(RcaRun failedRun, String errorClass) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_run_id", failedRun.id().toString());
        payload.put("source_incident_id", failedRun.incidentId().toString());
        payload.put("error_class", errorClass);
        payload.put("depth", 1);
        return payload.toString();
    }
}
