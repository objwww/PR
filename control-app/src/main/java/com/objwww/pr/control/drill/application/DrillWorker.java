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
 *       （确定未执行 = NOT_PERFORMED 才允许 INJECTING→FAILED）；</li>
 *   <li><b>DR-04 恢复/核验相位驱动</b>（本批交付，§7.4/§7.5 DR-04 卡）：每拍扫
 *       活动中作业——OBSERVING 观察窗期满（注入时刻 + 冻结 durationSeconds +
 *       maxFiringWaitSeconds）或受理停止 → 推进 RECOVERING 并立即执行恢复；
 *       RECOVERING 经 {@link DrillRecoveryPort#recover} 三态——RECOVERED →
 *       VERIFYING / FAILED → RECOVERY_FAILED 诚实卡因 / UNKNOWN 保持下拍重试；
 *       VERIFYING 经 {@link DrillRecoveryPort#verify}——VERIFIED → CLOSED +
 *       outcome 落真值（PASS/FAIL/INCONCLUSIVE 推导见 outcomeOf）/ FAILED →
 *       RECOVERY_FAILED / PENDING 保持下拍重试。重试上限 = 恢复窗口截止
 *       （相位进入时刻 + 模板 recovery.deadlineSeconds），超期 → RECOVERY_FAILED，
 *       不许死循环；</li>
 *   <li><b>崩溃恢复</b>：启动扫超龄租约孤儿——PRECHECK（零副作用）→ 重排队
 *       QUEUED 身份稳定；INJECTING 及以后（注入状态无法判定）→ 必先进 RECOVERING
 *       恢复路径并立即按固定身份驱动恢复（DR-04 接线后恢复动作幂等可重入），
 *       恢复接线缺席（{@link DrillRecoveryPort.NotImplemented}）则 FAILED 如实落
 *       RECOVERY_FAILED（worker_lost）保留靶场占位，不冒充现场干净；</li>
 *   <li><b>作业级截止对账</b>（§7.4 Flagd 段「作业级截止恢复和重启清扫」，本批
 *       推广到全部驱动）：每拍 + 启动时扫超「领取时刻 + 冻结 totalEstimateSeconds」
 *       仍活动中（INJECTING/OBSERVING）的作业、或超恢复窗口仍活动中
 *       （RECOVERING/VERIFYING）的作业 → 必先进 RECOVERING（不越级）→
 *       RECOVERY_FAILED 保留占位待人工核验。台账级截止清扫（eval 激活面）由可选
 *       {@link FlagdRestoreSweeper} 携带；</li>
 *   <li><b>DR-04 人工重试消费</b>：RECOVERY_FAILED + retry_requested_at 置位
 *       （/api/drills/{id}/retry-recovery 受理的意图列）→ 单语句 CAS 消费推进
 *       RECOVERY_FAILED→RECOVERING（清意图 + 刷新租约，状态机人工重试边），
 *       恢复/核验由后续相位驱动重走；</li>
 *   <li><b>DR-06 关联回填</b>（§7.5）：OBSERVING 相位按「注入时间窗 + 场景主症状
 *       标签」经 {@link DrillCorrelationPort} 匹配 incident，匹配到才 CAS 回填
 *       related_incident_id（currentRcaRunId 存在才带 related_run_id）并落
 *       WORKER_NOTE；匹配不到/窗口已过保持 null（前端「尚未关联」），不按时间
 *       近似瞎关联；重复回填由「related_incident_id IS NULL」CAS 幂等；</li>
 *   <li><b>FUP-01 领取复验</b>：claim 之后、进入 INJECTING 之前复验同源
 *       {@link DrillExecutionPolicy}——launch=false 时对确定未触及外部效果的
 *       领取件落 FAILED + LAUNCH_DISABLED（含拒绝事件与政策指纹，不永远重排队）；
 *       INJECTING/UNKNOWN 类相位不当无副作用失败释放（孤儿清扫保留恢复责任）。
 *       政策启动时加载，翻转需重启生效，不宣称即时急停；恢复/核验是收场方向，
 *       不受 launch 能力位把守。</li>
 * </ul>
 */
public class DrillWorker {

    private static final Logger log = LoggerFactory.getLogger(DrillWorker.class);

    /** 冻结参数 JSON 读面（totalEstimateSeconds/durationSeconds 提取；静态共享实例） */
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
    private final DrillRecoveryPort recovery;
    private final DrillClock clock;
    private final List<String> allowedEnvs;
    private final String workerId;
    private final long pollSeconds;
    private final long staleClaimSeconds;
    private final DrillCorrelationPort correlation;
    private final FlagdRestoreSweeper flagdSweeper;
    private final DrillExecutionPolicy policy;
    /** BA-180 预检洁净门信号面：scenarioId → 期望症状码当前 firing 集合（单拍；
     *  生产 = AlertProbe::firingNow，测试注入假件；异常 = 如实 UNKNOWN 不阻塞） */
    private final java.util.function.Function<String, List<String>> symptomFiringProbe;

    /** 旧装配面（DR-04 接线前）：关联回填 disabled（恒不关联，不假装），台账级清扫
     *  缺席，恢复/核验端口 = {@link DrillRecoveryPort.NotImplemented}（确定无法恢复
     *  → FAILED 如实落 RECOVERY_FAILED）；作业级截止对账（仅走 catalog+params，
     *  无新依赖）仍生效 */
    public DrillWorker(DrillJobRepository jobs, DrillEventRepository events,
                       DrillTemplateCatalog catalog, DrillInjectionPort injection,
                       DrillClock clock, List<String> allowedEnvs, String workerId,
                       long pollSeconds, long staleClaimSeconds,
                       DrillExecutionPolicy policy,
                       java.util.function.Function<String, List<String>> symptomFiringProbe) {
        this(jobs, events, catalog, injection, new DrillRecoveryPort.NotImplemented(),
                clock, allowedEnvs, workerId, pollSeconds, staleClaimSeconds,
                DrillCorrelationPort.disabled(), null, policy, symptomFiringProbe);
    }

    public DrillWorker(DrillJobRepository jobs, DrillEventRepository events,
                       DrillTemplateCatalog catalog, DrillInjectionPort injection,
                       DrillRecoveryPort recovery,
                       DrillClock clock, List<String> allowedEnvs, String workerId,
                       long pollSeconds, long staleClaimSeconds,
                       DrillCorrelationPort correlation, FlagdRestoreSweeper flagdSweeper,
                       DrillExecutionPolicy policy,
                       java.util.function.Function<String, List<String>> symptomFiringProbe) {
        this.jobs = Objects.requireNonNull(jobs);
        this.events = Objects.requireNonNull(events);
        this.catalog = Objects.requireNonNull(catalog);
        this.injection = Objects.requireNonNull(injection);
        this.recovery = Objects.requireNonNull(recovery);
        this.clock = Objects.requireNonNull(clock);
        this.allowedEnvs = List.copyOf(allowedEnvs);
        this.workerId = Objects.requireNonNull(workerId);
        this.pollSeconds = pollSeconds;
        this.staleClaimSeconds = staleClaimSeconds;
        this.correlation = Objects.requireNonNull(correlation);
        this.flagdSweeper = flagdSweeper; // 可空：台账未接线的装配面
        this.policy = Objects.requireNonNull(policy);
        this.symptomFiringProbe = Objects.requireNonNull(symptomFiringProbe);
    }

    /** 常驻循环：启动先扫孤儿与超期作业，之后 领取→驱动→睡 pollSeconds（中断即退） */
    public void runLoop() {
        int orphans = sweepOrphanedClaims();
        int expired = sweepRecoveryDeadlines();
        if (orphans > 0 || expired > 0) {
            log.warn("drill worker {} 启动清扫：{} 条超龄租约孤儿 + {} 条超期作业已处置",
                    workerId, orphans, expired);
        }
        log.warn("drill worker {} 进入轮询（poll={}s, staleClaim={}s）",
                workerId, pollSeconds, staleClaimSeconds);
        while (!Thread.currentThread().isInterrupted()) {
            tick();
            clock.sleepSeconds(pollSeconds);
        }
    }

    /** 单拍：先对账（台账清扫/截止/重试消费/相位驱动/关联回填）再领取一条 QUEUED
     *  驱动到本拍可达终态；true = 本拍有活干（测试面直调） */
    public boolean tick() {
        int recovered = flagdSweeper == null ? 0 : flagdSweeper.sweep();
        int expired = sweepRecoveryDeadlines();
        int retried = consumeRetryRequests();
        int phased = driveActivePhases();
        int linked = correlateObserving();
        Optional<DrillJob> claimed = jobs.claimNext(workerId, clock.now());
        if (claimed.isEmpty()) {
            return recovered + expired + retried + phased + linked > 0;
        }
        DrillJob job = claimed.get();
        log.warn("drill worker {} 领取作业：drill={} scenario={} env={}",
                workerId, job.id(), job.scenarioId(), job.targetEnv());
        try {
            drive(job);
        } catch (RuntimeException e) {
            // 驱动异常：作业保持当前相位 + 租约身份，超龄后由孤儿清扫按相位
            // 对账（PRECHECK 重排队 / INJECTING 起恢复路径）——不盲重放
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

        // FUP-01：领取后、进入 INJECTING 前复验同源执行政策——能力关闭时，claim 刚把
        // 作业从 QUEUED 领到 PRECHECK（确定未触及外部效果），按状态机落 FAILED +
        // LAUNCH_DISABLED 并记拒绝事件（不许永远重排队）；INJECTING/UNKNOWN 类相位
        // 不属本路径——孤儿清扫对它们保留恢复责任（RECOVERY_FAILED 占位），不伪判
        // 无副作用释放占用
        if (!policy.launchEnabled()) {
            events.insert(DrillEvent.of(current.id(), DrillEvent.EventType.WORKER_NOTE,
                    workerId,
                    "{\"rejected\":\"" + DrillExecutionPolicy.REASON_CODE
                            + "\",\"policyVersion\":\"" + policy.policyVersion()
                            + "\",\"policyFingerprint\":\"" + policy.policyFingerprint()
                            + "\"}", clock.now()));
            finalize(current, DrillJob.State.PRECHECK, DrillJob.State.FAILED,
                    policy.disabledReason(), null);
            log.warn("drill {} 领取复验拒绝：{}（确定未触及外部效果，零副作用 FAILED）",
                    current.id(), DrillExecutionPolicy.REASON_CODE);
            return;
        }

        // 服务端预检重执行（§7.2 旧预览不保证现在仍可启动；结果落 PRECHECK_RESULT 事件）
        // BA-180：环境洁净门信号在本面单拍实测（worker 执行域持有探针读面）
        DrillTemplate template = catalog.byScenarioId(current.scenarioId()).orElse(null);
        DrillPrecheck.Result precheck = DrillPrecheck.run(template, current.targetEnv(),
                allowedEnvs,
                jobs.findActiveOccupant(current.targetEnv(), current.id()).orElse(null),
                policy.launchEnabled(), residualFiring(template));
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
            // 注入可能发生即必走恢复路径（§7.4）：先进 RECOVERING 并立即驱动恢复——
            // DR-04 接线后由恢复端口按固定身份判定（注入实际未发生 = 无可恢复面，
            // arena 面如实 chaos_session_absent）
            DrillJob recovering = advance(current, DrillJob.State.INJECTING,
                    DrillJob.State.RECOVERING, null);
            driveRecovering(recovering,
                    "stopped_before_injection: 停止于注入相位（注入可能发生即必进恢复"
                            + "路径）；");
            return;
        }

        DrillInjectionPort.Outcome outcome = injection.inject(current);
        switch (outcome.kind()) {
            case NOT_PERFORMED ->
                // 确定零副作用 → FAILED 合法（闸门拒注/确定未触及外部效果如实卡因）
                    finalize(current, DrillJob.State.INJECTING, DrillJob.State.FAILED,
                            outcome.reason(), null);
            case PERFORMED -> {
                // 回执事件落账后推进 OBSERVING——观察窗期满/停止受理由相位驱动拍推进
                // 恢复链（本拍注入刚发生，窗口必然未期满，不越拍抢跑）
                events.insert(DrillEvent.of(current.id(), DrillEvent.EventType.WORKER_NOTE,
                        workerId, "{\"receipt\":" + outcome.receiptJson() + "}",
                        clock.now()));
                advance(current, DrillJob.State.INJECTING, DrillJob.State.OBSERVING, null);
                log.warn("drill {} 已进入 OBSERVING——观察窗期满或受理停止后由相位驱动拍"
                        + "推进恢复/核验链（DR-04 已接线）", current.id());
            }
            case UNKNOWN -> {
                // 注入结果无法判定：必先进恢复路径（§7.4）并立即按固定身份对账恢复；
                // 原因落 WORKER_NOTE 留痕（BA-186：此前原因随 context 形参丢弃，
                // CLOSED FAIL 的 terminal_reason 为空、页面无理由可展示）
                log.warn("drill {} 注入结果无法判定（{}）——按固定身份对账恢复",
                        current.id(), outcome.reason());
                events.insert(DrillEvent.of(current.id(), DrillEvent.EventType.WORKER_NOTE,
                        workerId,
                        "{\"action_unknown\":" + quote(outcome.reason()) + "}",
                        clock.now()));
                DrillJob recovering = advance(current, DrillJob.State.INJECTING,
                        DrillJob.State.RECOVERING, null);
                driveRecovering(recovering,
                        "ACTION_UNKNOWN: " + outcome.reason() + "；");
            }
        }
    }

    // ------------------------------------------------------------------ DR-04 相位驱动

    /**
     * 恢复/核验相位驱动（每拍）：OBSERVING 期满或受理停止 → RECOVERING + 立即恢复；
     * RECOVERING 三态推进；VERIFYING 三态推进。扫描面作业全部经 state+revision CAS，
     * 并发改写/竞争 = 本拍跳过留下拍（与既有清扫同律，不盲重放）。
     */
    private int driveActivePhases() {
        int handled = 0;
        for (DrillJob job : jobs.findActiveInStates(List.of(DrillJob.State.OBSERVING,
                DrillJob.State.RECOVERING, DrillJob.State.VERIFYING))) {
            try {
                handled += switch (job.state()) {
                    case OBSERVING -> driveObserving(job);
                    case RECOVERING -> driveRecovering(job, "");
                    case VERIFYING -> driveVerifying(job, "");
                    default -> 0;
                };
            } catch (RuntimeException e) {
                // CAS 竞争/读面抖动：保持相位留下拍对账，不中断整拍
                log.warn("drill {} 相位驱动本拍跳过（{}）", job.id(), e.getMessage());
            }
        }
        return handled;
    }

    /** OBSERVING：停止受理或观察窗期满 → RECOVERING + 立即驱动恢复；否则不动 */
    private int driveObserving(DrillJob job) {
        boolean stopped = job.stopRequestedAt() != null;
        boolean elapsed = observingWindowElapsed(job);
        if (!stopped && !elapsed) {
            return 0;
        }
        Instant now = clock.now();
        if (!jobs.advance(job.id(), job.revision(), DrillJob.State.OBSERVING,
                DrillJob.State.RECOVERING, now)) {
            return 0; // CAS 竞争：下拍再对账
        }
        events.insert(DrillEvent.phaseTransition(job.id(), DrillJob.State.OBSERVING,
                DrillJob.State.RECOVERING, workerId,
                stopped ? "{\"reason\":\"operator_stop: 观察相位受理停止，注入已发生必走"
                        + "恢复路径\"}"
                        : "{\"reason\":\"observation_window_elapsed: 观察窗期满，自动"
                        + "推进恢复\"}", now));
        DrillJob recovering = jobs.findById(job.id()).orElse(job);
        return 1 + driveRecovering(recovering,
                stopped ? "stopped_by_operator: 观察相位受理停止；" : "");
    }

    /** RECOVERING：恢复执行（三态）；恢复窗口超期 → RECOVERY_FAILED（重试上限） */
    private int driveRecovering(DrillJob job, String context) {
        if (recoveryDeadlineExceeded(job)) {
            finalizeRecoveryFailed(job, context + "recovery_deadline_exceeded: 超恢复窗口"
                    + "（进入恢复相位 + recovery.deadlineSeconds）仍未完成恢复——保留占位"
                    + "待人工核验，不冒充现场干净");
            return 1;
        }
        DrillRecoveryPort.RecoverOutcome outcome = recovery.recover(job);
        switch (outcome.kind()) {
            case RECOVERED -> {
                Instant now = clock.now();
                if (!jobs.advance(job.id(), job.revision(), DrillJob.State.RECOVERING,
                        DrillJob.State.VERIFYING, now)) {
                    return 0; // CAS 竞争：恢复动作幂等，下拍重走
                }
                events.insert(DrillEvent.phaseTransition(job.id(),
                        DrillJob.State.RECOVERING, DrillJob.State.VERIFYING, workerId,
                        "{\"recovery\":" + quote(outcome.reason()) + "}", now));
                return 1;
            }
            case FAILED -> {
                finalizeRecoveryFailed(job, context + "recovery_failed: "
                        + outcome.reason());
                return 1;
            }
            default -> {
                // UNKNOWN：保持 RECOVERING 下拍重试（上限 = 恢复窗口截止），不落事件防刷账
                log.warn("drill {} 恢复结果未判定（{}）——保持 RECOVERING 下拍重试",
                        job.id(), outcome.reason());
                return 0;
            }
        }
    }

    /** VERIFYING：症状清除核验（三态）；VERIFIED → CLOSED + outcome 落真值 */
    private int driveVerifying(DrillJob job, String context) {
        if (recoveryDeadlineExceeded(job)) {
            finalizeRecoveryFailed(job, context + "verify_deadline_exceeded: 超恢复窗口"
                    + "症状仍未核验清除——保留占位待人工核验，不冒充现场干净");
            return 1;
        }
        DrillRecoveryPort.VerifyOutcome outcome = recovery.verify(job);
        switch (outcome.kind()) {
            case VERIFIED -> {
                String value = outcomeOf(job);
                String terminalReason = "PASS".equals(value) ? null : failCloseReason(job);
                Instant now = clock.now();
                if (!jobs.finalize(job.id(), job.revision(), DrillJob.State.VERIFYING,
                        DrillJob.State.CLOSED, terminalReason, value, now, now)) {
                    return 0; // CAS 竞争：下拍重核
                }
                events.insert(DrillEvent.phaseTransition(job.id(),
                        DrillJob.State.VERIFYING, DrillJob.State.CLOSED, workerId,
                        "{\"verify\":" + quote(outcome.reason()) + "}", now));
                events.insert(DrillEvent.of(job.id(), DrillEvent.EventType.OUTCOME_RECORDED,
                        workerId, "{\"outcome\":" + quote(value) + "}", now));
                log.warn("drill {} 恢复核验完成 → CLOSED（outcome={}；CLOSED≠成功）",
                        job.id(), value);
                return 1;
            }
            case FAILED -> {
                finalizeRecoveryFailed(job, context + "verify_failed: "
                        + outcome.reason());
                return 1;
            }
            default -> {
                // PENDING：仍有残留 firing/探针暂不可读——保持 VERIFYING 下拍重试
                log.warn("drill {} 核验未通过（{}）——保持 VERIFYING 下拍重试",
                        job.id(), outcome.reason());
                return 0;
            }
        }
    }

    private void finalizeRecoveryFailed(DrillJob job, String terminalReason) {
        Instant now = clock.now();
        if (!jobs.finalize(job.id(), job.revision(), job.state(),
                DrillJob.State.RECOVERY_FAILED, terminalReason, null, null, now)) {
            return; // CAS 竞争（状态被并发改写）：下拍再对账
        }
        events.insert(DrillEvent.phaseTransition(job.id(), job.state(),
                DrillJob.State.RECOVERY_FAILED, workerId,
                "{\"terminalReason\":" + quote(terminalReason) + "}", now));
        log.warn("drill {} → RECOVERY_FAILED（{}）", job.id(), terminalReason);
    }

    /**
     * outcome 真值推导（CLOSED≠成功，outcome 另存）：模板未声明症状码 = INCONCLUSIVE
     * （核验面为空，不冒充目标达成判定）；声明了症状码则按 DR-06 关联回填裁定——
     * relatedIncidentId 存在 = 症状在注入窗口内真实触发（PASS），否则 FAIL（症状
     * 从未观测到，演练目标未达成，如实）。
     */
    private String outcomeOf(DrillJob job) {
        DrillTemplate template = catalog.byScenarioId(job.scenarioId()).orElse(null);
        if (template == null || template.symptomCodes().isEmpty()) {
            return "INCONCLUSIVE";
        }
        return job.relatedIncidentId() != null ? "PASS" : "FAIL";
    }

    /**
     * FAIL 闭环理由（BA-186）：优先回填注入未判定原因（注入相位已落 WORKER_NOTE
     * 留痕），其次如实说明症状未关联——CLOSED FAIL 不得 terminal_reason 为空、
     * 页面无理由可展示。
     */
    private String failCloseReason(DrillJob job) {
        List<DrillEvent> drillEvents = events.listByDrill(job.id());
        for (int i = drillEvents.size() - 1; i >= 0; i--) {
            DrillEvent e = drillEvents.get(i);
            if (e.eventType() == DrillEvent.EventType.WORKER_NOTE
                    && e.payloadJson() != null
                    && e.payloadJson().contains("\"action_unknown\"")) {
                return "演练 FAIL：注入未确认生效——" + extractNoteValue(e.payloadJson())
                        + "；恢复与症状核验已完成、现场已还原（观察窗内未关联到期望症状事件）";
            }
        }
        return "演练 FAIL：观察窗内未关联到期望症状事件（related_incident 为空）——"
                + "可能注入未生效、症状未触发或关联面断链；恢复与核验已完成，现场已还原";
    }

    /** WORKER_NOTE 单键 JSON 取值（{"action_unknown":"..."} 形态；自有产出面，键固定） */
    private static String extractNoteValue(String payloadJson) {
        int marker = payloadJson.indexOf(':');
        int start = payloadJson.indexOf('"', marker + 1);
        int end = start < 0 ? -1 : payloadJson.indexOf('"', start + 1);
        return start >= 0 && end > start ? payloadJson.substring(start + 1, end)
                : payloadJson;
    }

    /** 观察窗期满 = 注入时刻 + 冻结 durationSeconds + 模板 maxFiringWaitSeconds
     *  （症状迟发余量—— firing 等待结束再进恢复，关联回填窗不被截断）；窗口读不出
     *  （缺迁移事件/冻结参数缺 durationSeconds）= 不满，留作业级截止对账 */
    private boolean observingWindowElapsed(DrillJob job) {
        Instant injectedAt = observingSince(job.id());
        if (injectedAt == null) {
            return false;
        }
        DrillTemplate template = catalog.byScenarioId(job.scenarioId()).orElse(null);
        if (template == null) {
            return false;
        }
        Long duration = longParam(job.paramsJson(), "durationSeconds");
        long window = (duration != null ? duration
                : template.params().durationDefaultSeconds())
                + template.timing().maxFiringWaitSeconds();
        return !clock.now().isBefore(injectedAt.plusSeconds(window));
    }

    /** 恢复窗口超期 = 进入当前相位（事件账本读回，重启可恢复；兜底 claimedAt）
     *  + 模板 recovery.deadlineSeconds；读不出 = 不超期，留作业级截止对账 */
    private boolean recoveryDeadlineExceeded(DrillJob job) {
        DrillTemplate template = catalog.byScenarioId(job.scenarioId()).orElse(null);
        if (template == null) {
            return false;
        }
        Instant entered = phaseEnteredAt(job.id(), job.state());
        Instant base = entered != null ? entered : job.claimedAt();
        if (base == null) {
            return false;
        }
        return !clock.now().isBefore(
                base.plusSeconds(template.recovery().deadlineSeconds()));
    }

    /** 进入当前相位的时刻 = 最新一条 to_state=当前相位 的迁移事件（人工重试后
     *  重进 RECOVERING 取重试时刻——恢复窗口随重试重新起算） */
    private Instant phaseEnteredAt(UUID drillId, DrillJob.State state) {
        return events.listByDrill(drillId).stream()
                .filter(e -> e.eventType() == DrillEvent.EventType.PHASE_TRANSITION
                        && state.name().equals(e.toState()))
                .map(DrillEvent::createdAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    // ------------------------------------------------------------------ DR-04 人工重试

    /**
     * 人工重试消费（§7.4 处理入口）：RECOVERY_FAILED + retry_requested_at 置位 →
     * 单语句 CAS 推进 RECOVERING（清意图 + 刷新租约）并落迁移事件；恢复/核验由
     * 相位驱动拍重走（恢复端口幂等可重入，固定身份不换新 id）。
     */
    private int consumeRetryRequests() {
        int handled = 0;
        for (DrillJob job : jobs.findRetryRequests()) {
            Instant now = clock.now();
            if (!jobs.consumeRetry(job.id(), job.revision(), workerId, now)) {
                continue; // CAS 竞争（并发消费/截止对账）：下拍再对账
            }
            events.insert(DrillEvent.phaseTransition(job.id(),
                    DrillJob.State.RECOVERY_FAILED, DrillJob.State.RECOVERING, workerId,
                    "{\"reason\":\"manual_retry_requested: 人工核验后重试恢复（§7.4）\"}",
                    now));
            log.warn("drill {} 人工重试已消费：RECOVERY_FAILED→RECOVERING", job.id());
            handled++;
        }
        return handled;
    }

    /** 启动孤儿清扫（崩溃恢复）：PRECHECK 重排队；INJECTING 起必先进恢复路径并
     *  立即按固定身份驱动恢复（恢复接线缺席则 FAILED 如实落 RECOVERY_FAILED） */
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
            // DR-04：恢复执行接线已交付——立即驱动恢复/核验（幂等可重入）；接线缺席
            // （NotImplemented）则 FAILED 如实落 RECOVERY_FAILED 保留占位（§7.4：
            // 若无法判定是否生效，保持恢复待确认并阻止下一场演练）
            String context = "worker_lost: worker 失联，注入/恢复状态无法判定；";
            int driven = current.state() == DrillJob.State.VERIFYING
                    ? driveVerifying(current, context)
                    : driveRecovering(current, context);
            if (driven > 0) {
                handled++;
            }
        }
        return handled;
    }

    // ------------------------------------------------------------------ 作业级截止对账

    /**
     * 作业级截止对账（§7.4「作业级截止恢复和重启清扫」；跟随孤儿清扫模式：无状态、
     * 可重入、CAS 竞争留拍下轮；DR-04 起推广到全部驱动——恢复/核验接线已交付，
     * arena 侧 TTL 保障之外本面是作业相位收口的最终诚实边界）：
     * <ul>
     *   <li>INJECTING/OBSERVING 超「claimed_at + 冻结 totalEstimateSeconds」仍活动
     *       → 必先进 RECOVERING（状态机纪律不越级）→ RECOVERY_FAILED 保留占位；</li>
     *   <li>RECOVERING/VERIFYING 超恢复窗口（相位进入时刻 + recovery.deadlineSeconds）
     *       仍活动 → RECOVERY_FAILED 保留占位（重试上限，不许死循环）；</li>
     *   <li>截止读不出的行（params 缺 totalEstimateSeconds / 模板缺席）不动——
     *       留超龄租约孤儿清扫对账。</li>
     * </ul>
     */
    public int sweepRecoveryDeadlines() {
        int handled = 0;
        Instant now = clock.now();
        for (DrillJob job : jobs.findActiveInStates(List.of(
                DrillJob.State.INJECTING, DrillJob.State.OBSERVING,
                DrillJob.State.RECOVERING, DrillJob.State.VERIFYING))) {
            boolean inRecoveryPath = job.state() == DrillJob.State.RECOVERING
                    || job.state() == DrillJob.State.VERIFYING;
            if (inRecoveryPath) {
                if (!recoveryDeadlineExceeded(job)) {
                    continue;
                }
            } else {
                Long estimate = totalEstimateSeconds(job.paramsJson());
                if (estimate == null || job.claimedAt() == null
                        || now.isBefore(job.claimedAt().plusSeconds(estimate))) {
                    continue;
                }
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
                        "{\"reason\":\"作业级截止到期：必先进恢复路径（§7.4）\"}", now));
                current = jobs.findById(job.id()).orElse(job);
            }
            String reason = !inRecoveryPath
                    ? "job_deadline_exceeded: 超作业级截止（claimed_at + "
                    + "totalEstimateSeconds）仍未完成演练——保留占位待人工核验，"
                    + "不冒充现场干净"
                    : (job.state() == DrillJob.State.VERIFYING
                    ? "verify_deadline_exceeded: 超恢复窗口（进入 VERIFYING + "
                    + "recovery.deadlineSeconds）症状仍未核验清除——保留占位待人工"
                    + "核验，不冒充现场干净"
                    : "recovery_deadline_exceeded: 超恢复窗口（进入恢复相位 + "
                    + "recovery.deadlineSeconds）仍未完成恢复/核验——保留占位待人工"
                    + "核验，不冒充现场干净");
            if (jobs.finalize(current.id(), current.revision(), current.state(),
                    DrillJob.State.RECOVERY_FAILED, reason, null, null, now)) {
                events.insert(DrillEvent.phaseTransition(current.id(), current.state(),
                        DrillJob.State.RECOVERY_FAILED, workerId,
                        "{\"terminalReason\":" + quote(reason) + "}", now));
                log.warn("drill {} 超截止 → RECOVERY_FAILED（保留占位）", current.id());
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
        return longParam(paramsJson, "totalEstimateSeconds");
    }

    /** BA-180 洁净门信号：期望症状码当前 firing 集合（单拍）；模板缺症状码 = 空集
     *  （DrillPrecheck 走"无判定面"分支）；探针异常 = null（如实 UNKNOWN 不阻塞） */
    private List<String> residualFiring(DrillTemplate template) {
        if (template == null || template.symptomCodes().isEmpty()) {
            return List.of();
        }
        try {
            return List.copyOf(symptomFiringProbe.apply(template.scenarioId()));
        } catch (RuntimeException e) {
            log.warn("drill 预检洁净门探针暂不可读（{}）——SYMPTOM_CLEAN 如实 UNKNOWN",
                    e.getMessage());
            return null;
        }
    }

    private static Long longParam(String paramsJson, String key) {
        try {
            JsonNode node = PARAMS_JSON.readTree(paramsJson).path(key);
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
