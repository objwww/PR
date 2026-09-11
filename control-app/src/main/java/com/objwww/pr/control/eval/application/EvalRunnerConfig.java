package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaToolCallRepository;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.GoldenScenarioRegistry;
import com.objwww.pr.control.eval.domain.SynonymLexicon;
import com.objwww.pr.control.eval.domain.repository.EvalRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvalRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentResolutionProbe;
import com.objwww.pr.control.infrastructure.persistence.PostgresInvestigationResultRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaReportRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunResolver;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaToolCallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * eval-runner 装配（M3-15）：只在 {@code eval} profile 激活——生产默认装配
 * （docker profile 的 control_app 持久化 + 调度器）与本类互斥，Spring context 层面
 * 隔离；DB 身份为 eval_app（V10/V11 授权面），与生产 control_app 零交集。
 *
 * <p>运行形态：{@code --spring.profiles.active=eval} + eval_app 数据源 env 注入 +
 * {@link EvalRunnerMain} 入口。EV-04 起默认 worker 形态（常驻轮询 eval_run_command，
 * 页面发起经持久化命令进入执行面）；{@code app.alert.eval.worker.mode=once} 保留
 * M3 一次性跑批退出旧形态。CHAOS_ADMIN_TOKEN 仅 env 注入（INV-AM3-3），
 * 未配置时 ArenaChaosScenarioDriver fail-closed 拒绝注入。
 */
@Configuration
@Profile("eval")
public class EvalRunnerConfig {

    private static final Logger log = LoggerFactory.getLogger(EvalRunnerConfig.class);

    @Bean
    public JdbcClient evalJdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    // ---------------- 仓储（eval_app 身份；生产调查链路表 = V11 只读授权） ----------------

    @Bean
    public EvalRunRepository evalRunRepository(JdbcClient jdbc) {
        return new PostgresEvalRunRepository(jdbc);
    }

    @Bean
    public RcaRunRepository rcaRunRepository(JdbcClient jdbc) {
        return new PostgresRcaRunRepository(jdbc);
    }

    @Bean
    public RcaReportRepository rcaReportRepository(JdbcClient jdbc) {
        return new PostgresRcaReportRepository(jdbc);
    }

    @Bean
    public InvestigationResultRepository investigationResultRepository(JdbcClient jdbc) {
        return new PostgresInvestigationResultRepository(jdbc);
    }

    @Bean
    public RcaToolCallRepository rcaToolCallRepository(JdbcClient jdbc) {
        return new PostgresRcaToolCallRepository(jdbc);
    }

    // ---------------- 注册表与词典（classpath:/file: 均可；deploy/alert/eval 为部署事实源） ----------------

    @Bean
    public GoldenScenarioRegistry goldenScenarioRegistry(
            ResourceLoader loader,
            @Value("${app.alert.eval.registry-path:classpath:eval/eval-scenarios.yml}")
            String path) throws java.io.IOException {
        try (var in = loader.getResource(path).getInputStream()) {
            return GoldenScenarioRegistry.load(in);
        }
    }

    @Bean
    public SynonymLexicon synonymLexicon(
            ResourceLoader loader,
            @Value("${app.alert.eval.lexicon-path:classpath:eval/synonym-lexicon-v1.yml}")
            String path) throws java.io.IOException {
        try (var in = loader.getResource(path).getInputStream()) {
            return SynonymLexicon.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    // ---------------- 评分（M3-16） ----------------

    @Bean
    public ScenarioEvaluator scenarioEvaluator(SynonymLexicon lexicon) {
        return new ScenarioEvaluator(lexicon);
    }

    @Bean
    public SingleCaseScorer singleCaseScorer(RcaRunRepository runs,
                                             RcaReportRepository reports,
                                             InvestigationResultRepository investigations,
                                             RcaToolCallRepository toolCalls,
                                             ScenarioEvaluator evaluator) {
        return new SingleCaseScorer(runs, reports, investigations, toolCalls, evaluator);
    }

    // ---------------- 驱动器（M3-17；token/env 仅此入口注入） ----------------

    @Bean
    public AlertProbe alertProbe(GoldenScenarioRegistry registry,
                                 @Value("${app.alert.eval.prometheus-url:http://prometheus:9090}")
                                 String prometheusUrl) {
        return new PrometheusAlertProbe(prometheusUrl, registry,
                millis -> {
                    try {
                        Thread.sleep(millis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
    }

    @Bean
    public IncidentResolutionProbe incidentResolutionProbe(JdbcClient jdbc) {
        return new PostgresIncidentResolutionProbe(jdbc, millis -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Bean
    public RcaRunResolver rcaRunResolver(JdbcClient jdbc,
            @Value("${app.alert.eval.run-tag:}") String runTag) {
        return new PostgresRcaRunResolver(jdbc, millis -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, runTag);
    }

    @Bean
    public FlagdScenarioDriver flagdScenarioDriver(
            @Value("${app.alert.eval.flag-admin-url:http://flagd-admin:8081}")
            String flagAdminUrl, AlertProbe alertProbe) {
        return new FlagdScenarioDriver(
                new FlagdScenarioDriver.FlagAdminClient.Http(flagAdminUrl), alertProbe);
    }

    @Bean
    public ArenaTrafficClient arenaTrafficClient(
            @Value("${app.alert.eval.arena-base-url:http://order-arena:8080}")
            String arenaBaseUrl) {
        return new ArenaTrafficClient.Http(arenaBaseUrl);
    }

    @Bean
    public ArenaChaosScenarioDriver arenaChaosScenarioDriver(
            @Value("${app.alert.eval.chaos-admin-url:http://arena-chaos-admin:8080}")
            String chaosAdminUrl,
            @Value("${CHAOS_ADMIN_TOKEN:}") String adminToken,
            AlertProbe alertProbe,
            ArenaTrafficClient traffic,
            @Value("${app.alert.eval.dataset-version:eval-ds-1}") String datasetVersion,
            @Value("${app.alert.eval.run-tag:}") String runTag) {
        return new ArenaChaosScenarioDriver(
                new ChaosAdminClient.Http(chaosAdminUrl, adminToken), alertProbe, traffic,
                datasetVersion, runTag);
    }

    @Bean
    public InfrastructureScenarioDriver infrastructureScenarioDriver() {
        return new InfrastructureScenarioDriver();
    }

    // ---------------- 批量 runner（M3-17）与基线报告（M3-18） ----------------

    @Bean
    public BaselineReportGenerator baselineReportGenerator() {
        return new BaselineReportGenerator();
    }

    @Bean
    public EvalBatchRunner evalBatchRunner(GoldenScenarioRegistry registry,
                                           FlagdScenarioDriver flagd,
                                           ArenaChaosScenarioDriver arena,
                                           InfrastructureScenarioDriver infra,
                                           AlertProbe alertProbe,
                                           IncidentResolutionProbe incidentProbe,
                                           RcaRunResolver resolver,
                                           SingleCaseScorer scorer,
                                           EvalRunRepository evalRuns,
                                           BaselineReportGenerator generator,
                                           EvalRunMetadata metadata) {
        return new EvalBatchRunner(registry, Map.of(
                        "FlagdScenarioDriver", flagd,
                        "ArenaChaosScenarioDriver", arena,
                        "InfrastructureScenarioDriver", infra),
                alertProbe, incidentProbe, resolver, scorer, evalRuns, generator, metadata,
                2, systemClock());
    }

    // ---------------- EV-04 持久化命令 + worker（eval_run_command 写面 = eval_app 列级授权） ----------------

    @Bean
    public com.objwww.pr.control.eval.domain.repository.EvalRunCommandRepository
            evalRunCommandRepository(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence
                .PostgresEvalRunCommandRepository(jdbc);
    }

    @Bean
    public com.objwww.pr.control.eval.domain.repository.EvalPhaseEventSink evalPhaseEventSink(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence
                .PostgresEvalPhaseEventSink(jdbc);
    }

    @Bean
    public EvalLaunchExecutor evalLaunchExecutor(
            GoldenScenarioRegistry registry,
            FlagdScenarioDriver flagd,
            ArenaChaosScenarioDriver arena,
            InfrastructureScenarioDriver infra,
            AlertProbe alertProbe,
            IncidentResolutionProbe incidentProbe,
            RcaRunResolver resolver,
            SingleCaseScorer scorer,
            EvalRunRepository evalRuns,
            BaselineReportGenerator generator,
            com.objwww.pr.control.eval.domain.repository.EvalPhaseEventSink phaseSink,
            com.objwww.pr.control.eval.domain.repository.EvalRunCommandRepository commands,
            EvalRunMetadata metadata,
            @Value("${app.alert.eval.worker.id:eval-worker-1}") String workerId) {
        return new EvalLaunchExecutor(registry, Map.of(
                        "FlagdScenarioDriver", flagd,
                        "ArenaChaosScenarioDriver", arena,
                        "InfrastructureScenarioDriver", infra),
                alertProbe, incidentProbe, resolver, scorer, evalRuns, generator,
                phaseSink, commands, metadata, 2, systemClock(), workerId);
    }

    @Bean
    public EvalRunWorker evalRunWorker(
            com.objwww.pr.control.eval.domain.repository.EvalRunCommandRepository commands,
            EvalRunRepository evalRuns,
            EvalLaunchExecutor executor,
            UsageLedgerService ledger,
            @Value("${app.alert.eval.worker.id:eval-worker-1}") String workerId,
            @Value("${app.alert.eval.worker.poll-seconds:5}") long pollSeconds,
            @Value("${app.alert.eval.worker.stale-claim-seconds:900}")
            long staleClaimSeconds) {
        return new EvalRunWorker(commands, evalRuns, executor, ledger, systemClock(),
                workerId, pollSeconds, staleClaimSeconds);
    }

    // ---------------- usage 对账（M3-25；未配置 litellm 时诚实降级 UNMATCHED/BEST_EFFORT） ----------------

    /**
     * master key 仅 env 注入（INV-AM3-3）；base-url 为空 = 未接 proxy，出账按 chain③
     * 降级（UNMATCHED/BEST_EFFORT），批件不受影响。wait 兜 proxy spend 日志异步 flush
     * （spike 实测 ~16s）。
     */
    @Bean
    public UsageLedgerService usageLedgerService(EvalRunRepository evalRuns,
                                                 InvestigationResultRepository investigations,
                                                 @Value("${app.alert.eval.litellm.base-url:}")
                                                 String litellmBaseUrl,
                                                 @Value("${app.alert.eval.litellm.master-key:}")
                                                 String litellmMasterKey,
                                                 @Value("${app.alert.eval.litellm.run-key-alias:}")
                                                 String runKeyAlias,
                                                 @Value("${app.alert.eval.litellm.reconcile-wait-seconds:0}")
                                                 long reconcileWaitSeconds) {
        com.objwww.pr.control.eval.domain.litellm.LiteLlmAdminPort port =
                litellmBaseUrl.isBlank() ? null
                        : new com.objwww.pr.control.infrastructure.litellm.HttpLiteLlmAdminClient(
                                litellmBaseUrl, litellmMasterKey);
        return new UsageLedgerService(evalRuns, investigations, port,
                runKeyAlias, reconcileWaitSeconds * 1000);
    }

    private static EvalBatchRunner.EvalClock systemClock() {
        return new EvalBatchRunner.EvalClock() {
            @Override
            public java.time.Instant now() {
                return java.time.Instant.now();
            }

            @Override
            public void sleepSeconds(long seconds) {
                try {
                    Thread.sleep(seconds * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    /**
     * 入口（EV-04）：worker 形态（默认）= 常驻轮询持久化命令；once = M3 一次性跑批
     * 退出（ApplicationContextRunner 场景测试不会触发 ApplicationRunner——两种形态
     * 都不会在装配测试里执行）。
     */
    @Bean
    public EvalRunnerMain evalRunnerMain(EvalBatchRunner runner,
                                         UsageLedgerService ledger,
                                         ConfigurableApplicationContext context,
                                         EvalRunWorker worker,
                                         com.objwww.pr.control.drill.application.DrillWorker
                                                 drillWorker,
                                         @Value("${app.alert.eval.worker.mode:worker}")
                                         String workerMode) {
        return new EvalRunnerMain(runner, ledger, context, worker, drillWorker,
                workerMode);
    }

    // ---------------- DR-02 演练 worker（§7.3：由已有评测执行身份所在的 worker 领取；
    //   eval_app 授权面 V86；注入接线未交付——NotImplemented 端口如实 FAILED） ----------------

    @Bean
    public com.objwww.pr.control.drill.application.DrillTemplateCatalog
            drillTemplateCatalog(
            ResourceLoader loader,
            @Value("${app.drill.template-path:classpath:drill/drill-templates.yml}")
            String path) throws java.io.IOException {
        try (var in = loader.getResource(path).getInputStream()) {
            return com.objwww.pr.control.drill.application.DrillTemplateCatalog.load(in);
        }
    }

    @Bean
    public com.objwww.pr.control.drill.domain.repository.DrillJobRepository
            drillJobRepository(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence
                .PostgresDrillJobRepository(jdbc);
    }

    @Bean
    public com.objwww.pr.control.drill.domain.repository.DrillEventRepository
            drillEventRepository(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence
                .PostgresDrillEventRepository(jdbc);
    }

    /** DR-02 本批唯一注入实现：接线未交付（DR-03/DR-04），确定零副作用如实卡因 */
    @Bean
    public com.objwww.pr.control.drill.application.DrillInjectionPort drillInjectionPort() {
        return new com.objwww.pr.control.drill.application.DrillInjectionPort
                .NotImplemented();
    }

    @Bean
    public com.objwww.pr.control.drill.application.DrillWorker drillWorker(
            com.objwww.pr.control.drill.domain.repository.DrillJobRepository
                    drillJobRepository,
            com.objwww.pr.control.drill.domain.repository.DrillEventRepository
                    drillEventRepository,
            com.objwww.pr.control.drill.application.DrillTemplateCatalog
                    drillTemplateCatalog,
            com.objwww.pr.control.drill.application.DrillInjectionPort
                    drillInjectionPort,
            @Value("${app.drill.target-envs:arena-195}") String targetEnvs,
            @Value("${app.alert.eval.worker.id:eval-worker-1}") String workerId,
            @Value("${app.drill.worker.poll-seconds:5}") long pollSeconds,
            @Value("${app.drill.worker.stale-claim-seconds:900}")
            long staleClaimSeconds) {
        return new com.objwww.pr.control.drill.application.DrillWorker(
                drillJobRepository, drillEventRepository, drillTemplateCatalog,
                drillInjectionPort, drillClock(),
                java.util.Arrays.stream(targetEnvs.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList(),
                workerId + "-drill", pollSeconds, staleClaimSeconds);
    }

    private static com.objwww.pr.control.drill.application.DrillWorker.DrillClock
            drillClock() {
        return new com.objwww.pr.control.drill.application.DrillWorker.DrillClock() {
            @Override
            public java.time.Instant now() {
                return java.time.Instant.now();
            }

            @Override
            public void sleepSeconds(long seconds) {
                try {
                    Thread.sleep(seconds * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    /** 元数据来源（十项可复现元数据；M3-24/25 的对账输入在 provider 侧另行回填） */
    @Bean
    public EvalRunMetadata evalRunMetadata(
            @Value("${app.alert.eval.model:glm-5}") String model,
            @Value("${app.alert.eval.prompt-version:am3-rca-v2}") String promptVersion,
            @Value("${app.alert.eval.prompt-digest}") String promptDigest,
            @Value("${app.alert.eval.tool-registry-digest}") String toolRegistryDigest,
            @Value("${app.alert.eval.provider-fingerprint}") String providerFingerprint,
            @Value("${app.alert.eval.alert-rule-digest}") String alertRuleDigest,
            @Value("${app.alert.eval.scenario-driver-version:scenario-driver-v1}")
            String driverVersion,
            @Value("${app.alert.eval.dataset-version:eval-ds-1}") String datasetVersion,
            @Value("${app.alert.eval.grader-version:}") String graderVersion,
            GoldenScenarioRegistry registry,
            SynonymLexicon lexicon) {
        // EN-09：评分器版本必填（发布前必须完成——无评分器版本的评测批次不得存在，
        // 历史分数归属 E11 面无锚即拒批）
        if (graderVersion == null || graderVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "app.alert.eval.grader-version 必填（EN-09：评分器版本入批次身份面）");
        }
        return new EvalRunMetadata(1, datasetVersion, registry.contentDigest(),
                lexicon.lexiconVersion(), model, promptVersion,
                new com.objwww.pr.shared.Digest(promptDigest),
                new com.objwww.pr.shared.Digest(toolRegistryDigest),
                null, null, null, null, null,
                providerFingerprint, new com.objwww.pr.shared.Digest(alertRuleDigest),
                driverVersion, graderVersion);
    }
}
