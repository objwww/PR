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
 * {@link EvalRunnerMain} 跑批退出。CHAOS_ADMIN_TOKEN 仅 env 注入（INV-AM3-3），
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
                                           RcaRunResolver resolver,
                                           SingleCaseScorer scorer,
                                           EvalRunRepository evalRuns,
                                           BaselineReportGenerator generator,
                                           EvalRunMetadata metadata) {
        return new EvalBatchRunner(registry, Map.of(
                        "FlagdScenarioDriver", flagd,
                        "ArenaChaosScenarioDriver", arena,
                        "InfrastructureScenarioDriver", infra),
                alertProbe, resolver, scorer, evalRuns, generator, metadata,
                2, systemClock());
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
     * 批量入口：跑批 → 日志出报告摘要 → 进程退出（eval-runner 是批作业，非常驻）。
     * ApplicationContextRunner 场景测试不会触发 ApplicationRunner（生产 SpringApplication 才会）。
     */
    @Bean
    public EvalRunnerMain evalRunnerMain(EvalBatchRunner runner,
                                         UsageLedgerService ledger,
                                         ConfigurableApplicationContext context) {
        return new EvalRunnerMain(runner, ledger, context);
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
            GoldenScenarioRegistry registry,
            SynonymLexicon lexicon) {
        return new EvalRunMetadata(1, datasetVersion, registry.contentDigest(),
                lexicon.lexiconVersion(), model, promptVersion,
                new com.objwww.pr.shared.Digest(promptDigest),
                new com.objwww.pr.shared.Digest(toolRegistryDigest),
                null, null, null, null, null,
                providerFingerprint, new com.objwww.pr.shared.Digest(alertRuleDigest),
                driverVersion);
    }
}
