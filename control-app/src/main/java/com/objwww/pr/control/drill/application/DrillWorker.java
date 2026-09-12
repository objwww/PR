package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillEvent;
import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.drill.domain.model.DrillTemplate;
import com.objwww.pr.control.drill.domain.repository.DrillEventRepository;
import com.objwww.pr.control.drill.domain.repository.DrillJobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * DR-02 演练 worker（eval_app 身份；沿用 EV-04 EvalRunWorker 的 SKIP LOCKED 领取 +
 * 启动孤儿清扫模式，§7.3"由已有评测执行身份所在的 worker 领取"）：
 * <ul>
 *   <li><b>单拍语义</b>：claimNext 单语句 CAS（SKIP LOCKED）且领取即相位迁移
 *       QUEUED→PRECHECK（BA-114，与 EVAL claimNextLaunch 同律——行在领取语句
 *       提交时即离开 QUEUED 可见集，杜绝两语句窗口期双领）→ 停止面检查 →
 *       服务端预检重执行（落 PRECHECK_RESULT 事件）→ INJECTING →
 *       {@link DrillInjectionPort}——领取后的每次相位迁移都是 state+revision
 *       双对账 CAS 并落 PHASE_TRANSITION 事件；HTTP 线程全程零执行；</li>
 *   <li><b>停止收口</b>（§7.4）：相位边界检查 stop_requested_at——注入前取消
 *       CANCELLED；注入一旦发生/可能发生，停止或失败都必先进 RECOVERING
 *       （本批注入零副作用，NOT_PERFORMED 才允许 INJECTING→FAILED）；</li>
 *   <li><b>崩溃恢复</b>：启动扫超龄租约孤儿——PRECHECK（零副作用）→ 重排队
 *       QUEUED 身份稳定；INJECTING 及以后（注入/恢复状态无法判定）→
 *       RECOVERY_FAILED 保留靶场占位（worker_lost），不冒充现场干净；</li>
 *   <li><b>DR-05 作业级截止恢复</b>（§7.4 Flagd 段「作业级截止恢复和重启清扫」）：
 *       每拍 + 启动时扫 flagd 场景（模板 driver=FlagdScenarioDriver）超「领取时刻
 *       + 冻结 totalEstimateSeconds」仍活动中（INJECTING/OBSERVING/RECOVERING/
 *       VERIFYING）的作业 → 必先进 RECOVERING（不越级），恢复执行接线未交付 →
 *       RECOVERY_FAILED 保留占位待人工核验；arena 场景 TTL 保障归 DR-04 面不动。
 *       台账级截止清扫（eval 激活面）由可选 {@link FlagdRestoreSweeper} 携带；</li>
 *   <li><b>DR-06 关联回填</b>（§7.5）：OBSERVING 相位按「注入时间窗 + 场景主症状
 *       标签」经 {@link DrillCorrelationPort} 匹配 incident，匹配到才 CAS 回填
 *       related_incident_id（currentRcaRunId 存在才带 related_run_id）并落
 *       WORKER_NOTE；匹配不到/窗口已过保持 null（前端「尚未关联」），不按时间
 *       近似瞎关联；重复回填由「related_incident_id IS NULL」CAS 幂等；</li>
 *   <li><b>本批诚实边界</b>：注入接线未交付（DR-03/DR-04），执行器在注入相位
 *       如实 FAILED（INJECTION_NOT_IMPLEMENTED）——不假装注入成功；OBSERVING
 *       及以后的推进（症状等待/恢复/核验）随真实接线一并交付。</li>
 * </ul>
 */
public class DrillWorker {

    private static final Logger log = LoggerFactory.getLogger(DrillWorker.class);

    /** 冻结参数 JSON 读面（totalEstimateSeconds 提取；静态共享实例，配置后即线程安全） */
    private static final ObjectMapper PARAMS_JSON = new ObjectMapper();

    /** 时钟面（EvalBatchRunner.EvalClock 同式；测试面可控） */
    public interface DrillClock {
        Instant now();

        void sleepSeconds(long seconds);
    }

    private final DrillJobRepository jobs;
    private final DrillEventRepository events;
    private final DrillTemplateCatalog catalog;
    private final DrillInjectionPort injection;
    private final DrillClock clock;
    private final List<String> allowedEnvs;
    private final String workerId;
    private final long pollSeconds;
    private final long staleClaimSeconds;
    private final DrillCorrelationPort correlation;
    private final FlagdRestoreSweeper flagdSweeper;

    /** 旧装配面（DR-05/DR-06 接线前）：关联回填 disabled（恒不关联，不假装），
     *  台账级清扫缺席；作业级截止对账（仅走 catalog+params，无新依赖）仍生效 */
    public DrillWorker(DrillJobRepository jobs, DrillEventRepository events,
                       DrillTemplateCatalog catalog, DrillInjectionPort injection,
                       DrillClock clock, List<String> allowedEnvs, String workerId,
                       long pollSeconds, long staleClaimSeconds) {
        this(jobs, events, catalog, injection, clock, allowedEnvs, workerId,
                pollSeconds, staleClaimSeconds, DrillCorrelationPort.disabled(), null);
    }

    public DrillWorker(DrillJobRepository jobs, DrillEventRepository events,
                       DrillTemplateCatalog catalog, DrillInjectionPort injection,
                       DrillClock clock, List<String> allowedEnvs, String workerId,
                       long pollSeconds, long staleClaimSeconds,
                       DrillCorrelationPort correlation, FlagdRestoreSweeper flagdSweeper) {
        this.jobs = Objects.requireNonNull(jobs);
        this.events = Objects.requireNonNull(events);
        this.catalog = Objects.requireNonNull(catalog);
        this.injection = Objects.requireNonNull(injection);
        this.clock = Objects.requireNonNull(clock);
        this.allowedEnvs = List.copyOf(allowedEnvs);
        this.workerId = Objects.requireNonNull(workerId);
        this.pollSeconds = pollSeconds;
        this.staleClaimSeconds = staleClaimSeconds;
        this.correlation = Objects.requireNonNull(correlation);
        this.flagdSweeper = flagdSweeper; // 可空：台账未接线的装配面
    }

    /** 常驻循环：启动先扫孤儿与超期 flagd 作业，之后 领取→驱动→睡 pollSeconds（中断即退） */
    public void runLoop() {
        int orphans = sweepOrphanedClaims();
        int expired = sweepFlagdRecoveryDeadlines();
        if (orphans > 0 || expired > 0) {
            log.warn("drill worker {} 启动清扫：{} 条超龄租约孤儿 + {} 条超期 flagd 作业已处置",
                    workerId, orphans, expired);
        }
        log.warn("drill worker {} 进入轮询（poll={}s, staleClaim={}s）",
                workerId, pollSeconds, staleClaimSeconds);
        while (!Thread.currentThread().isInterrupted()) {
            tick();
            clock.sleepSeconds(pollSeconds);
        }
    }

    /** 单拍：先对账（台账清扫/flagd 截止/关联回填）再领取一条 QUEUED 驱动到本批
     *  可达终态；true = 本拍有活干（测试面直调） */
    public boolean tick() {
        int recovered = flagdSweeper == null ? 0 : flagdSweeper.sweep();
        int expired = sweepFlagdRecoveryDeadlines();
        int linked = correlateObserving();
        Optional<DrillJob> claimed = jobs.claimNext(workerId, clock.now());
        if (claimed.isEmpty()) {
            return recovered + expired + linked > 0;
        }
        DrillJob job = claimed.get();
        log.warn("drill worker {} 领取作业：drill={} scenario={} env={}",
                workerId, job.id(), job.scenarioId(), job.targetEnv());
        try {
            drive(job);
        } catch (RuntimeException e) {
            // 驱动异常：作业保持当前相位 + 租约身份，超龄后由孤儿清扫按相位
            // 对账（PRECHECK 重排队 / INJECTING 起 RECOVERY_FAILED）——不盲重放
            log.error("drill {} 驱动异常（留待孤儿对账）: {}", job.id(), e.getMessage(), e);
        }
        return true;
    }

    // ------------------------------------------------------------------ 驱动

    private void drive(DrillJob job) {
        // BA-114：claim 已单语句原子完成 QUEUED→PRECHECK（领取即迁移）——此处只补
        // 事件账保持相位链完整，不再二次推进（二次推进必撞 CAS：from 已非 QUEUED）
        events.insert(DrillEvent.phaseTransition(job.id(), DrillJob.State.QUEUED,
                DrillJob.State.PRECHECK, workerId,
                "{\"note\":\"claim 单语句原子领取即迁移\"}", clock.now()));
        // 受理即取消（停止面先于一切相位动作；claim 落 PRECHECK 后取消走
        // PRECHECK→CANCELLED，状态机合法迁移）
        if (job.stopRequestedAt() != null) {
            finalize(job, DrillJob.State.PRECHECK, DrillJob.State.CANCELLED,
                    "cancelled_by_operator", null);
            return;
        }
        DrillJob current = job;

        // 服务端预检重执行（§7.2 旧预览不保证现在仍可启动；结果落 PRECHECK_RESULT 事件）
        DrillTemplate template = catalog.byScenarioId(current.scenarioId()).orElse(null);
        DrillPrecheck.Result precheck = DrillPrecheck.run(template, current.targetEnv(),
                allowedEnvs,
                jobs.findActiveOccupant(current.targetEnv(), current.id()).orElse(null));
        events.insert(DrillEvent.of(current.id(), DrillEvent.EventType.PRECHECK_RESULT,
                workerId, precheckJson(precheck), clock.now()));
        if (current.stopRequestedAt() != null) {
            finalize(current, DrillJob.State.PRECHECK, DrillJob.State.CANCELLED,
                    "cancelled_by_operator", null);
            return;
        }
        if (!precheck.canLaunch()) {
            finalize(current, DrillJob.State.PRECHECK, DrillJob.State.FAILED,
                    "precheck_failed: " + firstFailure(precheck), null);
            return;
        }
        current = advance(current, DrillJob.State.PRECHECK, DrillJob.State.INJECTING,
                null);
        if (current.stopRequestedAt() != null) {
            // 注入尚未发生（NOT_PERFORMED 端口是唯一实现）：零副作用取消仍合法；
            // 真实接线落地后此分支必须改走 RECOVERING（注入可能发生即必进恢复路径）
            finalize(current, DrillJob.State.INJECTING, DrillJob.State.FAILED,
                    "stopped_before_injection: 停止于注入执行前，确定零副作用", null);
            return;
        }

        DrillInjectionPort.Outcome outcome = injection.inject(current);
        switch (outcome.kind()) {
            case NOT_PERFORMED ->
                // 确定零副作用 → FAILED 合法（本批 INJECTION_NOT_IMPLEMENTED 如实卡因）
                    finalize(current, DrillJob.State.INJECTING, DrillJob.State.FAILED,
                            outcome.reason(), null);
            case PERFORMED -> {
                // 真实接线交付前的不可达分支：回执事件落账后推进 OBSERVING
                events.insert(DrillEvent.of(current.id(), DrillEvent.EventType.WORKER_NOTE,
                        workerId, "{\"receipt\":" + outcome.receiptJson() + "}",
                        clock.now()));
                advance(current, DrillJob.State.INJECTING, DrillJob.State.OBSERVING, null);
                log.warn("drill {} 已进入 OBSERVING——症状等待/恢复/核验推进随 DR-03/04 "
                        + "接线交付，本拍到此为止（作业保持活动占位）", current.id());
            }
            case UNKNOWN -> {
                // 注入结果无法判定：必先进恢复路径（§7.4），恢复接线未交付 →
                // RECOVERY_FAILED 保留占位待人工核验，不冒充现场干净
                DrillJob recovering = advance(current, DrillJob.State.INJECTING,
                        DrillJob.State.RECOVERING, null);
                finalize(recovering, DrillJob.State.RECOVERING,
                        DrillJob.State.RECOVERY_FAILED,
                        "ACTION_UNKNOWN 且恢复接线未交付（DR-04）：" + outcome.reason()
                                + "——保留占位，人工核验前阻止下一场演练", null);
            }
        }
    }

    /** 启动孤儿清扫（崩溃恢复）：PRECHECK 重排队；INJECTING 起 RECOVERY_FAILED 留占位 */
    public int sweepOrphanedClaims() {
        Instant staleBefore = clock.now().minusSeconds(staleClaimSeconds);
        List<DrillJob> orphans = jobs.findOrphanedClaims(staleBefore);
        int handled = 0;
        for (DrillJob orphan : orphans) {
            if (orphan.state() == DrillJob.State.QUEUED
                    || orphan.state() == DrillJob.State.PRECHECK) {
                // 尚未触及注入（零副作用）：重排队，稳定身份不换 id
                if (jobs.requeue(orphan.id(), orphan.revision(), clock.now())) {
                    events.insert(DrillEvent.of(orphan.id(),
                            DrillEvent.EventType.WORKER_NOTE, workerId,
                            "{\"note\":\"worker_lost 重排队（未触及注入，零副作用）\"}",
                            clock.now()));
                    log.warn("孤儿 drill {} 重排队（state={}）", orphan.id(), orphan.state());
                    handled++;
                }
                continue;
            }
            // INJECTING/OBSERVING：注入状态无法判定 → 必先进恢复路径（§7.4 状态机
            // 纪律：不越级直落 RECOVERY_FAILED）；RECOVERING/VERIFYING 本就在恢复路径
            DrillJob current = orphan;
            if (orphan.state() == DrillJob.State.INJECTING
                    || orphan.state() == DrillJob.State.OBSERVING) {
                if (!jobs.advance(orphan.id(), orphan.revision(), orphan.state(),
                        DrillJob.State.RECOVERING, clock.now())) {
                    continue; // CAS 竞争（状态被并发改写）：下轮清扫再对账
                }
                events.insert(DrillEvent.phaseTransition(orphan.id(), orphan.state(),
                        DrillJob.State.RECOVERING, workerId,
                        "{\"reason\":\"worker_lost 对账：注入状态无法判定，先进恢复路径\"}",
                        clock.now()));
                current = jobs.findById(orphan.id()).orElse(orphan);
            }
            // 恢复无法执行（worker 失联 + 恢复接线未交付）→ RECOVERY_FAILED 保留占位
            // （§7.4：若无法判定是否生效，保持恢复待确认并阻止下一场演练）
            if (jobs.finalize(current.id(), current.revision(), current.state(),
                    DrillJob.State.RECOVERY_FAILED,
                    "worker_lost: worker 失联，注入/恢复状态无法判定——保留占位待人工核验",
                    null, null, clock.now())) {
                events.insert(DrillEvent.phaseTransition(current.id(), current.state(),
                        DrillJob.State.RECOVERY_FAILED, workerId,
                        "{\"reason\":\"worker_lost\"}", clock.now()));
                log.warn("孤儿 drill {} → RECOVERY_FAILED（worker_lost，保留占位）",
                        current.id());
                handled++;
            }
        }
        return handled;
    }

    // ------------------------------------------------------------------ DR-05 截止恢复

    /**
     * flagd 作业级截止对账（§7.4「作业级截止恢复和重启清扫」；跟随孤儿清扫模式：
     * 无状态、可重入、CAS 竞争留拍下轮）。超「claimed_at + 冻结 totalEstimateSeconds」
     * 仍活动中 → 必先进 RECOVERING（状态机纪律不越级）；恢复执行接线未交付
     * （DR-03/DR-04）→ RECOVERY_FAILED 保留占位，不冒充现场干净。
     * 仅限 flagd 场景（模板 driver=FlagdScenarioDriver）；arena 场景的 TTL 保障与
     * 恢复接线归 DR-04 面，本清扫不动。截止读不出的行（params 缺 totalEstimateSeconds）
     * 不动——留超龄租约孤儿清扫对账。
     */
    public int sweepFlagdRecoveryDeadlines() {
        int handled = 0;
        Instant now = clock.now();
        for (DrillJob job : jobs.findActiveInStates(List.of(
                DrillJob.State.INJECTING, DrillJob.State.OBSERVING,
                DrillJob.State.RECOVERING, DrillJob.State.VERIFYING))) {
            DrillTemplate template = catalog.byScenarioId(job.scenarioId()).orElse(null);
            if (template == null || !"FlagdScenarioDriver".equals(template.driver())) {
                continue;
            }
            Long estimate = totalEstimateSeconds(job.paramsJson());
            if (estimate == null || job.claimedAt() == null) {
                continue;
            }
            if (now.isBefore(job.claimedAt().plusSeconds(estimate))) {
                continue;
            }
            DrillJob current = job;
            if (job.state() == DrillJob.State.INJECTING
                    || job.state() == DrillJob.State.OBSERVING) {
                if (!jobs.advance(job.id(), job.revision(), job.state(),
                        DrillJob.State.RECOVERING, now)) {
                    continue; // CAS 竞争：下轮再对账
                }
                events.insert(DrillEvent.phaseTransition(job.id(), job.state(),
                        DrillJob.State.RECOVERING, workerId,
                        "{\"reason\":\"flagd 作业级截止到期：必先进恢复路径（§7.4）\"}",
                        now));
                current = jobs.findById(job.id()).orElse(job);
            }
            if (jobs.finalize(current.id(), current.revision(), current.state(),
                    DrillJob.State.RECOVERY_FAILED,
                    "flagd_recovery_deadline_exceeded: 超作业级截止（claimed_at+"
                            + "totalEstimateSeconds）仍未完成恢复，且恢复执行接线未交付"
                            + "（DR-03/DR-04）——保留占位待人工核验，不冒充现场干净",
                    null, null, now)) {
                events.insert(DrillEvent.phaseTransition(current.id(), current.state(),
                        DrillJob.State.RECOVERY_FAILED, workerId,
                        "{\"reason\":\"flagd_recovery_deadline_exceeded\"}", now));
                log.warn("flagd drill {} 超作业级截止 → RECOVERY_FAILED（保留占位）",
                        current.id());
                handled++;
            }
        }
        return handled;
    }

    // ------------------------------------------------------------------ DR-06 关联回填

    /**
     * OBSERVING 相位的告警/调查关联回填（§7.5 DR-06）：时间窗 = INJECTING→OBSERVING
     * 迁移时刻（事件账本读回，重启可恢复）+ 模板 maxFiringWaitSeconds；关联键 =
     * 场景主症状码（symptomCodes 首项）。匹配到才 CAS 回填并落 WORKER_NOTE；
     * 匹配不到/窗口已过保持 null（前端「尚未关联」），严禁按时间近似瞎关联。
     */
    private int correlateObserving() {
        int linked = 0;
        for (DrillJob job : jobs.findActiveInStates(List.of(DrillJob.State.OBSERVING))) {
            if (job.relatedIncidentId() != null) {
                continue; // 重复回填幂等
            }
            DrillTemplate template = catalog.byScenarioId(job.scenarioId()).orElse(null);
            if (template == null || template.symptomCodes().isEmpty()) {
                continue;
            }
            Instant injectedAt = observingSince(job.id());
            if (injectedAt == null) {
                continue;
            }
            Instant windowEnd = injectedAt
                    .plusSeconds(template.timing().maxFiringWaitSeconds());
            if (clock.now().isAfter(windowEnd)) {
                continue; // 窗口外不猜（保持尚未关联）
            }
            String alertname = template.symptomCodes().getFirst();
            Optional<DrillCorrelationPort.Correlation> hit =
                    correlation.correlate(alertname, injectedAt, windowEnd);
            if (hit.isEmpty()) {
                continue;
            }
            DrillCorrelationPort.Correlation c = hit.get();
            if (jobs.linkRelated(job.id(), job.revision(), c.incidentId(),
                    c.currentRcaRunId(), clock.now())) {
                events.insert(DrillEvent.of(job.id(), DrillEvent.EventType.WORKER_NOTE,
                        workerId,
                        "{\"correlation\":{\"incidentId\":\"" + c.incidentId()
                                + "\",\"runId\":" + (c.currentRcaRunId() == null
                                ? "null" : "\"" + c.currentRcaRunId() + "\"")
                                + ",\"alertname\":" + quote(alertname)
                                + ",\"rule\":\"primary-symptom+injection-window\"}}",
                        clock.now()));
                log.warn("drill {} 关联回填：incident={} run={}",
                        job.id(), c.incidentId(), c.currentRcaRunId());
                linked++;
            }
        }
        return linked;
    }

    /** 注入时刻 = 最早一条 to_state=OBSERVING 的相位迁移事件（账本读回，重启可恢复） */
    private Instant observingSince(UUID drillId) {
        return events.listByDrill(drillId).stream()
                .filter(e -> e.eventType() == DrillEvent.EventType.PHASE_TRANSITION
                        && DrillJob.State.OBSERVING.name().equals(e.toState()))
                .map(DrillEvent::createdAt)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /** 冻结参数里的后端计算总窗口（§7.2 不允许前端各算一套；读不出 = null，不猜） */
    private static Long totalEstimateSeconds(String paramsJson) {
        try {
            JsonNode node = PARAMS_JSON.readTree(paramsJson).path("totalEstimateSeconds");
            return node.isNumber() ? node.asLong() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 相位推进 + 事件落账；CAS 失败即状态被并发改写（停止面除外）——快速失败留对账 */
    private DrillJob advance(DrillJob job, DrillJob.State from, DrillJob.State to,
                             String note) {
        Instant now = clock.now();
        if (!jobs.advance(job.id(), job.revision(), from, to, now)) {
            throw new IllegalStateException("相位推进 CAS 失败: " + job.id() + " "
                    + from + "→" + to + "（状态被并发改写，留孤儿对账）");
        }
        events.insert(DrillEvent.phaseTransition(job.id(), from, to, workerId,
                note == null ? "{}" : note, now));
        return jobs.findById(job.id()).orElseThrow(() ->
                new IllegalStateException("作业在推进窗口内消失: " + job.id()));
    }

    private void finalize(DrillJob job, DrillJob.State from, DrillJob.State to,
                          String terminalReason, String outcome) {
        Instant now = clock.now();
        if (!jobs.finalize(job.id(), job.revision(), from, to, terminalReason, outcome,
                to == DrillJob.State.CLOSED ? now : null, now)) {
            throw new IllegalStateException("终态化 CAS 失败: " + job.id() + " "
                    + from + "→" + to + "（状态被并发改写，留孤儿对账）");
        }
        events.insert(DrillEvent.phaseTransition(job.id(), from, to, workerId,
                "{\"terminalReason\":" + quote(terminalReason) + "}", now));
        if (outcome != null) {
            events.insert(DrillEvent.of(job.id(), DrillEvent.EventType.OUTCOME_RECORDED,
                    workerId, "{\"outcome\":" + quote(outcome) + "}", now));
        }
    }

    private static String quote(String raw) {
        return raw == null ? "null" : "\"" + raw.replace("\"", "\\\"") + "\"";
    }

    private String precheckJson(DrillPrecheck.Result precheck) {
        StringBuilder json = new StringBuilder("{\"canLaunch\":")
                .append(precheck.canLaunch()).append(",\"checks\":[");
        for (int i = 0; i < precheck.checks().size(); i++) {
            DrillPrecheck.Check c = precheck.checks().get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"name\":").append(quote(c.name()))
                    .append(",\"status\":").append(quote(c.status().name()))
                    .append(",\"detail\":").append(quote(c.detail())).append('}');
        }
        return json.append("]}").toString();
    }

    private static String firstFailure(DrillPrecheck.Result precheck) {
        return precheck.checks().stream()
                .filter(c -> c.status() == DrillPrecheck.Status.FAIL)
                .findFirst()
                .map(c -> c.name() + ": " + c.detail())
                .orElse("unknown");
    }
}
