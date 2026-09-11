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
 *       （随机 id、零生命周期挂点）——CLI 兼容面保留。</li>
 * </ul>
 * 仅 SpringApplication 生命周期会触发 ApplicationRunner——ApplicationContextRunner
 * 的 profile 隔离测试不会连带执行跑批。
 */
public final class EvalRunnerMain implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunnerMain.class);

    private final EvalBatchRunner runner;
    private final UsageLedgerService ledger;
    private final ConfigurableApplicationContext context;
    private final EvalRunWorker worker;
    private final com.objwww.pr.control.drill.application.DrillWorker drillWorker;
    private final String mode;

    public EvalRunnerMain(EvalBatchRunner runner,
                          UsageLedgerService ledger,
                          ConfigurableApplicationContext context,
                          EvalRunWorker worker,
                          com.objwww.pr.control.drill.application.DrillWorker drillWorker,
                          String mode) {
        this.runner = runner;
        this.ledger = ledger;
        this.context = context;
        this.worker = worker;
        this.drillWorker = drillWorker;
        this.mode = mode;
    }

    @Override
    public void run(ApplicationArguments args) {
        if ("worker".equals(mode)) {
            // DR-02：演练 worker 与 eval worker 同进程（§7.3 同一执行身份）——
            // 独立守护线程跑 drill 轮询，主线程保持 eval 轮询；两 worker 各扫
            // 各的表，SKIP LOCKED 互不相撞
            Thread drillLoop = new Thread(drillWorker::runLoop, "drill-worker");
            drillLoop.setDaemon(true);
            drillLoop.start();
            worker.runLoop();
            return;
        }
        runOnce();
    }

    /** M3-15 旧形态：跑批 → 出基线报告摘要日志 → 进程退出 */
    private void runOnce() {
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
            System.exit(SpringApplication.exit(context, () -> 0));
        } catch (RuntimeException e) {
            log.error("eval 批量失败", e);
            System.exit(SpringApplication.exit(context, () -> 1));
        }
    }
}
