package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.model.EvalRunCommand;
import com.objwww.pr.control.eval.domain.repository.EvalRunCommandRepository;
import com.objwww.pr.control.eval.domain.repository.EvalRunRepository;
import com.objwww.pr.control.eval.domain.statemachine.EvalRunLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * EV-04 eval 持久化 worker（eval_app 身份；方案 EV-04 卡"持久化命令与worker"落点）：
 * <ul>
 *   <li><b>轮询领取</b>：claimNextLaunch 单语句 CAS（SKIP LOCKED）→
 *       {@link EvalLaunchExecutor} 跑批 → 命令 DONE/FAILED 收尾；HTTP 线程全程零执行；</li>
 *   <li><b>取消</b>：不在 worker 主动轮询——EvalBatchRunner 案例边界检查点直读
 *       eval_run_command 受理面（EvalLaunchExecutor 注入的信号），受理即"取消中"；</li>
 *   <li><b>崩溃恢复（EU15 边界面）</b>：启动扫 CLAIMED 超龄孤儿——run 行从未落库
 *       （崩溃于 insertRunning 前）→ 重排队 PENDING，预定 id 不变（稳定身份）；
 *       run 仍 RUNNING（崩溃于跑批中）→ 终态化 FAILED（terminal_reason=worker_lost，
 *       L 模式 recovery_state 保持 PENDING=恢复未核验，不冒充 VERIFIED）；
 *       run 已终态（崩溃于收尾前）→ 命令按 run 终态对齐 DONE/FAILED；</li>
 *   <li>usage 对账（M3-25）在批件终态后照旧执行，失败不阻断命令收尾。</li>
 *   <li><b>陈旧自拒</b>（2026-09-17）：{@link WorkerSchemaFreshnessGuard} 每拍前置
 *       ——镜像迁移面落后于 DB flyway 最大版本则不领取，命令留 PENDING 等新镜像
 *       接管（47h 旧镜像抢跑新命令、INSERT 撞新 schema 半途失败的实证修复）。</li>
 * </ul>
 */
public class EvalRunWorker {

    private static final Logger log = LoggerFactory.getLogger(EvalRunWorker.class);

    /** LAUNCH 执行面（真栈 = {@link EvalLaunchExecutor}；函数口便于假件测试） */
    public interface LaunchExecutor {
        EvalBatchRunner.BatchResult execute(EvalRunCommand command);
    }

    private final EvalRunCommandRepository commands;
    private final EvalRunRepository evalRuns;
    private final LaunchExecutor executor;
    private final UsageLedgerService ledger;
    private final EvalBatchRunner.EvalClock clock;
    private final String workerId;
    private final long pollSeconds;
    private final long staleClaimSeconds;
    private final WorkerSchemaFreshnessGuard freshness;
    /** 陈旧自拒的日志沿状态翻转落（不每 tick 刷屏） */
    private boolean staleLogged;

    public EvalRunWorker(EvalRunCommandRepository commands,
                         EvalRunRepository evalRuns,
                         LaunchExecutor executor,
                         UsageLedgerService ledger,
                         EvalBatchRunner.EvalClock clock,
                         String workerId,
                         long pollSeconds,
                         long staleClaimSeconds,
                         WorkerSchemaFreshnessGuard freshness) {
        this.commands = Objects.requireNonNull(commands);
        this.evalRuns = Objects.requireNonNull(evalRuns);
        this.executor = Objects.requireNonNull(executor);
        this.ledger = Objects.requireNonNull(ledger);
        this.clock = Objects.requireNonNull(clock);
        this.workerId = Objects.requireNonNull(workerId);
        this.pollSeconds = pollSeconds;
        this.staleClaimSeconds = staleClaimSeconds;
        this.freshness = Objects.requireNonNull(freshness);
    }

    /** 常驻循环：启动先扫孤儿，之后 领取→执行→收尾→睡 pollSeconds（中断即退） */
    public void runLoop() {
        int orphans = sweepOrphanedClaims();
        if (orphans > 0) {
            log.warn("eval worker {} 启动孤儿清扫：{} 条 CLAIMED 命令已处置", workerId, orphans);
        }
        log.warn("eval worker {} 进入轮询（poll={}s, staleClaim={}s）",
                workerId, pollSeconds, staleClaimSeconds);
        while (!Thread.currentThread().isInterrupted()) {
            tick();
            clock.sleepSeconds(pollSeconds);
        }
    }

    /** 单拍：领取一条 LAUNCH 并执行；true = 本拍有活干（测试面直调） */
    public boolean tick() {
        if (freshness.stale()) {
            // 陈旧镜像自拒（WorkerSchemaFreshnessGuard）：不领取，命令留 PENDING 等
            // 新镜像 worker——判 REJECTED 会永久杀死本属于新 worker 的命令，不可
            if (!staleLogged) {
                log.error("eval worker {} schema 落后于 DB（镜像陈旧）：自拒领取，"
                        + "LAUNCH 留 PENDING 待新镜像接管", workerId);
                staleLogged = true;
            }
            return false;
        }
        if (staleLogged) {
            log.warn("eval worker {} schema 新鲜度恢复，恢复领取", workerId);
            staleLogged = false;
        }
        Optional<EvalRunCommand> claimed =
                commands.claimNextLaunch(workerId, clock.now());
        if (claimed.isEmpty()) {
            return false;
        }
        EvalRunCommand command = claimed.get();
        log.warn("eval worker {} 领取 LAUNCH：command={} run={}", workerId,
                command.id(), command.evalRunId());
        Instant claimAt = clock.now();
        try {
            EvalBatchRunner.BatchResult result = executor.execute(command);
            reconcileUsage(result.evalRunId());
            commands.finish(command.id(), EvalRunCommand.State.DONE, clock.now());
            // 批件完成观测面（诊断"批线程无声消失"：有领取无完成 = 线程消失实锤）
            log.warn("eval worker {} 批件完成: run={} 耗时={}s",
                    workerId, command.evalRunId(),
                    clock.now().minusSeconds(claimAt.getEpochSecond()).getEpochSecond());
        } catch (EvalLaunchGate.EvalLaunchUnsupportedException e) {
            // PAGE-03 能力拒绝（领取后复验）：命令 REJECTED 留痕——从未执行（与跑批
            // 失败 FAILED 区分），run 行零落库；旧客户端漏网命令在此收口
            log.warn("eval worker {} 拒绝 LAUNCH（{}）：{}", workerId, e.code(), e.getMessage());
            commands.finish(command.id(), EvalRunCommand.State.REJECTED, clock.now());
        } catch (RuntimeException e) {
            // 批件异常：EvalBatchRunner 已把 run 终态化 FAILED（batch_error 卡因），
            // 这里只收口命令行——异常不吞，命令 FAILED 留痕
            log.error("eval run {} 跑批失败: {}", command.evalRunId(), e.getMessage(), e);
            commands.finish(command.id(), EvalRunCommand.State.FAILED, clock.now());
        }
        return true;
    }

    /** 启动孤儿清扫（崩溃恢复）：返回处置条数 */
    public int sweepOrphanedClaims() {
        Instant staleBefore = clock.now().minusSeconds(staleClaimSeconds);
        List<EvalRunCommand> orphans = commands.findOrphanedClaims(staleBefore);
        int handled = 0;
        for (EvalRunCommand orphan : orphans) {
            Optional<EvalRun> run = evalRuns.findById(orphan.evalRunId());
            if (run.isEmpty()) {
                // 崩溃于 insertRunning 前：零副作用——重排队，预定 run id 不变
                if (commands.requeue(orphan.id())) {
                    log.warn("孤儿命令 {} 重排队（run {} 从未落库）", orphan.id(),
                            orphan.evalRunId());
                    handled++;
                }
                continue;
            }
            EvalRun existing = run.get();
            if (existing.state() == EvalRun.EvalRunState.RUNNING) {
                // 崩溃于跑批中：run 不会自己终态——worker_lost 终态化（L 模式恢复未核验
                // 如实留 PENDING，见 EvalRunLifecycle.orphanTerminalReason）
                String mode = modeOf(orphan);
                evalRuns.finalizeOnce(EvalRun.terminal(existing.id(), existing.metadata(),
                        EvalRun.EvalRunState.FAILED, existing.startedAt(), clock.now(),
                        null, null, null,
                        EvalRunLifecycle.orphanTerminalReason(mode)));
                commands.finish(orphan.id(), EvalRunCommand.State.FAILED, clock.now());
                log.warn("孤儿 run {} 终态化 FAILED（worker_lost）", existing.id());
            } else {
                // 崩溃于收尾前：run 已终态——命令对齐（不再执行任何业务动作）
                EvalRunCommand.State align = existing.state()
                        == EvalRun.EvalRunState.SUCCEEDED
                        ? EvalRunCommand.State.DONE : EvalRunCommand.State.FAILED;
                commands.finish(orphan.id(), align, clock.now());
                log.warn("孤儿命令 {} 对齐 run 终态 {}", orphan.id(), existing.state());
            }
            handled++;
        }
        return handled;
    }

    /** usage 三态出账（M3-25 同律：对账失败不改变批件/命令终态） */
    private void reconcileUsage(java.util.UUID evalRunId) {
        try {
            log.warn("usage ledger: {}", ledger.reconcileEvalRun(evalRunId).toJson());
        } catch (RuntimeException e) {
            log.warn("usage 对账出账失败（不改变命令终态）", e);
        }
    }

    /** L 模式判定取命令 payload（run 行无 mode 读面——EvalRun 聚合不载 V80 列） */
    private static String modeOf(EvalRunCommand command) {
        try {
            return EvalLaunchExecutor.parsePlan(command.payloadJson()).mode();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
