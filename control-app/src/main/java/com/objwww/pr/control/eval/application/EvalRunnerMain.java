package com.objwww.pr.control.eval.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * eval-runner 入口（EV-04 双形态）：
 * <ul>
 *   <li><b>worker（默认）</b>：{@link EvalRunWorker#runLoop()} 常驻轮询
 *       eval_run_command——页面/脚本发起经持久化命令进入执行面（HTTP 线程不跑批）；</li>
 *   <li><b>once</b>（{@code app.alert.eval.worker.mode=once}）：M3-15 旧一次性跑批
 *       （随机 id、零生命周期挂点）——CLI 兼容面保留；FUP-01 起闭面期
 *       （{@link EvalLaunchGate#launchEnabled()}=false）直接禁止，与命令面/worker
 *       领取复验同源，不再默认放行。</li>
 * </ul>
 * FUP-01：mode 显式 switch，仅允许 worker/once——未知值（误拼/空白）启动失败
 * （非零退出 + 明确原因），绝不默认落到一次性执行。
 * 仅 SpringApplication 生命周期会触发 ApplicationRunner——ApplicationContextRunner
 * 的 profile 隔离测试不会连带执行跑批。
 */
public final class EvalRunnerMain implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunnerMain.class);

    /** 未知 mode（配置校验失败）退出码 */
    static final int EXIT_UNKNOWN_MODE = 2;
    /** once 闭面期禁止退出码 */
    static final int EXIT_ONCE_DISABLED = 3;

    /** 进程出口（测试面替换为记录型假件，避免真实 System.exit 杀测试 JVM） */
    public interface ExitHook {
        void exit(int code);
    }

    private final EvalBatchRunner runner;
    private final UsageLedgerService ledger;
    private final EvalRunWorker worker;
    private final com.objwww.pr.control.drill.application.DrillWorker drillWorker;
    private final EvalLaunchGate gate;
    private final String mode;
    private final ExitHook exitHook;

    /** 生产装配面：进程出口 = SpringApplication.exit + System.exit */
    public EvalRunnerMain(EvalBatchRunner runner,
                          UsageLedgerService ledger,
                          ConfigurableApplicationContext context,
                          EvalRunWorker worker,
                          com.objwww.pr.control.drill.application.DrillWorker drillWorker,
                          EvalLaunchGate gate,
                          String mode) {
        this(runner, ledger, worker, drillWorker, gate, mode,
                code -> System.exit(SpringApplication.exit(context, () -> code)));
    }

    /** 测试面：进程出口可注入（FCT-05/06 断言非零退出而不杀 JVM） */
    public EvalRunnerMain(EvalBatchRunner runner,
                          UsageLedgerService ledger,
                          EvalRunWorker worker,
                          com.objwww.pr.control.drill.application.DrillWorker drillWorker,
                          EvalLaunchGate gate,
                          String mode,
                          ExitHook exitHook) {
        this.runner = runner;
        this.ledger = ledger;
        this.worker = worker;
        this.drillWorker = drillWorker;
        this.gate = gate;
        this.mode = mode;
        this.exitHook = exitHook;
    }

    @Override
    public void run(ApplicationArguments args) {
        // FUP-01：只允许 worker/once；未知值（误拼/空白/null）启动失败，绝不默认 once
        switch (mode == null ? "" : mode.trim()) {
            case "worker" -> {
                // DR-02：演练 worker 与 eval worker 同进程（§7.3 同一执行身份）——
                // 独立守护线程跑 drill 轮询，主线程保持 eval 轮询；两 worker 各扫
                // 各的表，SKIP LOCKED 互不相撞
                Thread drillLoop = new Thread(drillWorker::runLoop, "drill-worker");
                drillLoop.setDaemon(true);
                drillLoop.start();
                worker.runLoop();
            }
            case "once" -> runOnce();
            default -> {
                log.error("app.alert.eval.worker.mode 仅允许 worker/once，实际值="
                        + "\"{}\"——配置校验失败，启动终止（绝不默认落到一次性执行）", mode);
                exitHook.exit(EXIT_UNKNOWN_MODE);
            }
        }
    }

    /** M3-15 旧形态：跑批 → 出基线报告摘要日志 → 进程退出 */
    private void runOnce() {
        // FUP-01：once 与命令面/worker 领取复验同源闭面——闭面期直接禁止一次性
        // 跑批（零 runBatch/零注入），非零退出并给明确原因
        if (!gate.launchEnabled()) {
            log.error("评测发起当前已关闭（LAUNCH_DISABLED，SAFE-02/FUP-01）——"
                    + "mode=once 闭面期禁止一次性跑批；重开需经 app.eval.launch.enabled "
                    + "显式配置并重启生效");
            exitHook.exit(EXIT_ONCE_DISABLED);
            return;
        }
        try {
            EvalBatchRunner.BatchResult result = runner.runBatch();
            log.warn("eval 批量完成: run={} coverage={} conditional={} e2e={} unresolvedRate={} "
                            + "tp={} fp={} fn={} reportDigest={}",
                    result.evalRunId(), result.snapshot().coverage(),
                    result.snapshot().conditionalAccuracy(), result.snapshot().endToEndHitRate(),
                    result.snapshot().unresolvedRate(),
                    result.symptomCounts().truePositives(),
                    result.symptomCounts().falsePositives(),
                    result.symptomCounts().falseNegatives(),
                    result.baselineReportDigest().value());
            // usage 三态出账（M3-25；P0-8 降级链在 service 内收口，失败不阻断批件终态）
            try {
                log.warn("usage ledger: {}", ledger.reconcileEvalRun(result.evalRunId()).toJson());
            } catch (RuntimeException e) {
                log.warn("usage 对账出账失败（不改变批件终态）", e);
            }
            exitHook.exit(0);
        } catch (RuntimeException e) {
            log.error("eval 批量失败", e);
            exitHook.exit(1);
        }
    }
}
