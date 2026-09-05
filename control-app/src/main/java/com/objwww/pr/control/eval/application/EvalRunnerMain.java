package com.objwww.pr.control.eval.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * eval-runner 批作业入口（M3-15）：跑批 → 出基线报告摘要日志 → 进程退出。
 * 仅 SpringApplication 生命周期会触发 ApplicationRunner——ApplicationContextRunner
 * 的 profile 隔离测试不会连带执行跑批。
 */
public final class EvalRunnerMain implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunnerMain.class);

    private final EvalBatchRunner runner;
    private final UsageLedgerService ledger;
    private final ConfigurableApplicationContext context;

    public EvalRunnerMain(EvalBatchRunner runner,
                          UsageLedgerService ledger,
                          ConfigurableApplicationContext context) {
        this.runner = runner;
        this.ledger = ledger;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
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
