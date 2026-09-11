package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillEvent;
import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.drill.domain.model.DrillTemplate;
import com.objwww.pr.control.drill.domain.repository.DrillEventRepository;
import com.objwww.pr.control.drill.domain.repository.DrillJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * DR-02 演练 worker（eval_app 身份；沿用 EV-04 EvalRunWorker 的 SKIP LOCKED 领取 +
 * 启动孤儿清扫模式，§7.3"由已有评测执行身份所在的 worker 领取"）：
 * <ul>
 *   <li><b>单拍语义</b>：claimNext 单语句 CAS（SKIP LOCKED）→ 停止面检查 →
 *       PRECHECK（服务端预检重执行并落 PRECHECK_RESULT 事件）→ INJECTING →
 *       {@link DrillInjectionPort}——每次相位迁移都是 state+revision 双对账 CAS
 *       并落 PHASE_TRANSITION 事件；HTTP 线程全程零执行；</li>
 *   <li><b>停止收口</b>（§7.4）：相位边界检查 stop_requested_at——注入前取消
 *       CANCELLED；注入一旦发生/可能发生，停止或失败都必先进 RECOVERING
 *       （本批注入零副作用，NOT_PERFORMED 才允许 INJECTING→FAILED）；</li>
 *   <li><b>崩溃恢复</b>：启动扫超龄 CLAIMED 孤儿——PRECHECK（零副作用）→ 重排队
 *       QUEUED 身份稳定；INJECTING 及以后（注入/恢复状态无法判定）→
 *       RECOVERY_FAILED 保留靶场占位（worker_lost），不冒充现场干净；</li>
 *   <li><b>本批诚实边界</b>：注入接线未交付（DR-03/DR-04），执行器在注入相位
 *       如实 FAILED（INJECTION_NOT_IMPLEMENTED）——不假装注入成功；OBSERVING
 *       及以后的推进（症状等待/恢复/核验）随真实接线一并交付。</li>
 * </ul>
 */
public class DrillWorker {

    private static final Logger log = LoggerFactory.getLogger(DrillWorker.class);

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

    public DrillWorker(DrillJobRepository jobs, DrillEventRepository events,
                       DrillTemplateCatalog catalog, DrillInjectionPort injection,
                       DrillClock clock, List<String> allowedEnvs, String workerId,
                       long pollSeconds, long staleClaimSeconds) {
        this.jobs = Objects.requireNonNull(jobs);
        this.events = Objects.requireNonNull(events);
        this.catalog = Objects.requireNonNull(catalog);
        this.injection = Objects.requireNonNull(injection);
        this.clock = Objects.requireNonNull(clock);
        this.allowedEnvs = List.copyOf(allowedEnvs);
        this.workerId = Objects.requireNonNull(workerId);
        this.pollSeconds = pollSeconds;
        this.staleClaimSeconds = staleClaimSeconds;
    }

    /** 常驻循环：启动先扫孤儿，之后 领取→驱动→睡 pollSeconds（中断即退） */
    public void runLoop() {
        int orphans = sweepOrphanedClaims();
        if (orphans > 0) {
            log.warn("drill worker {} 启动孤儿清扫：{} 条超龄 CLAIMED 作业已处置",
                    workerId, orphans);
        }
        log.warn("drill worker {} 进入轮询（poll={}s, staleClaim={}s）",
                workerId, pollSeconds, staleClaimSeconds);
        while (!Thread.currentThread().isInterrupted()) {
            tick();
            clock.sleepSeconds(pollSeconds);
        }
    }

    /** 单拍：领取一条 QUEUED 并驱动到本批可达终态；true = 本拍有活干（测试面直调） */
    public boolean tick() {
        Optional<DrillJob> claimed = jobs.claimNext(workerId, clock.now());
        if (claimed.isEmpty()) {
            return false;
        }
        DrillJob job = claimed.get();
        log.warn("drill worker {} 领取作业：drill={} scenario={} env={}",
                workerId, job.id(), job.scenarioId(), job.targetEnv());
        try {
            drive(job);
        } catch (RuntimeException e) {
            // 驱动异常：作业保持当前相位 + CLAIMED 身份，超龄后由孤儿清扫按相位
            // 对账（PRECHECK 重排队 / INJECTING 起 RECOVERY_FAILED）——不盲重放
            log.error("drill {} 驱动异常（留待孤儿对账）: {}", job.id(), e.getMessage(), e);
        }
        return true;
    }

    // ------------------------------------------------------------------ 驱动

    private void drive(DrillJob job) {
        // 受理即取消（停止面先于一切相位动作）
        if (job.stopRequestedAt() != null) {
            finalize(job, DrillJob.State.QUEUED, DrillJob.State.CANCELLED,
                    "cancelled_by_operator", null);
            return;
        }
        DrillJob current = advance(job, DrillJob.State.QUEUED, DrillJob.State.PRECHECK,
                null);

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
