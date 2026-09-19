package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaToolCallRepository;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.GoldenCase;
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

    /**
     * R6 用量链读面（rca_model_call 逐调用行）：eval 批跑 profile 自持装配——
     * docker profile 的 PersistenceConfig 不在本 profile 内，UsageLedgerService
     * 的账本读口在此落地（eval_app 只读，SELECT 授权随 V93）；事务面走批跑数据源，
     * 装配期不建连（与 evalJdbcClient 同律）。
     */
    @Bean
    public com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger rcaModelCallLedger(
            JdbcClient jdbc, DataSource dataSource) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresRcaModelCallLedger(
                jdbc, new com.fasterxml.jackson.databind.ObjectMapper(),
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                dataSource)));
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
            org.springframework.beans.factory.ObjectProvider<
                    com.objwww.pr.control.eval.domain.repository.ReplayCaseReader>
                    replayCaseReader,
            JdbcClient jdbc,
            @Value("${app.alert.eval.registry-path:classpath:eval/eval-scenarios.yml}")
            String path,
            @Value("${app.alert.eval.dataset-version:eval-ds-1}") String datasetVersion)
            throws java.io.IOException {
        GoldenScenarioRegistry base;
        try (var in = loader.getResource(path).getInputStream()) {
            base = GoldenScenarioRegistry.load(in);
        }
        // P2 执行集接通：materialize 入集案例（dataset version 精确键 + 适用期 +
        // HOLDOUT RLS）映射为 REPLAY 场景合入执行注册表——"生产失败→评测用例"
        // 闭环的最后一公里（OpenRCA/Meta point-in-time 回放形态）。
        // reader 缺席 = 装配测试/离线形态（零回放案例）；在场 = Postgres 实现直连。
        com.objwww.pr.control.eval.domain.repository.ReplayCaseReader reader =
                replayCaseReader.getIfAvailable(
                        () -> new com.objwww.pr.control.infrastructure.persistence
                                .PostgresReplayCaseReader(jdbc));
        java.util.List<GoldenCase> replayCases = new java.util.ArrayList<>();
        for (com.objwww.pr.control.eval.domain.repository.ReplayCaseReader.ReplayCaseRow row
                : reader.listReplayCases(datasetVersion)) {
            try {
                replayCases.add(DatasetCaseMapper.toGoldenCase(row));
            } catch (RuntimeException e) {
                throw new IllegalStateException("回放案例装载失败（期望面残缺禁入评测）: "
                        + row.caseKey() + " — " + e.getMessage(), e);
            }
        }
        GoldenScenarioRegistry merged = base.plus(replayCases);
        log.info("评测执行注册表装载：注入场景 {} + 回放案例 {}（dataset-version={}）",
                base.scenarios().size(), replayCases.size(), datasetVersion);
        return merged;
    }

    /** P4 红队人造刺激面：case_key → crafted AM payload（rawArtifact 保留键直取） */
    private static java.util.Map<String, String> craftedPayloadsOf(
            com.objwww.pr.control.eval.domain.repository.ReplayCaseReader reader,
            String datasetVersion) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (com.objwww.pr.control.eval.domain.repository.ReplayCaseReader.ReplayCaseRow row
                : reader.listReplayCases(datasetVersion)) {
            String crafted = DatasetCaseMapper
                    .adversarialPayload(DatasetCaseMapper.rawArtifactOf(row));
            if (crafted != null) {
                out.put(row.caseKey(), crafted);
            }
        }
        return out;
    }

    /** P2 回放驱动器（冻结载荷读面 + webhook 重投面；bearer env 注入 fail-closed）；
     *  P4：crafted 刺激面注入——案例自带 payload 优先于冻结读面。案例读面走
     *  ObjectProvider（装配测试=离线空桩零 SQL；生产=Postgres 回退），与
     *  goldenScenarioRegistry 同律——装配隔离契约（装配期零 SQL）不被破坏 */
    @Bean
    public ReplayScenarioDriver replayScenarioDriver(
            JdbcClient jdbc,
            org.springframework.beans.factory.ObjectProvider<
                    com.objwww.pr.control.eval.domain.repository.ReplayCaseReader>
                    replayCaseReader,
            @Value("${app.alert.eval.dataset-version:eval-ds-1}") String datasetVersion,
            @Value("${app.alert.eval.webhook-url:http://control-app:8080/webhooks/alertmanager}")
            String webhookUrl,
            @Value("${app.alert.eval.webhook-bearer:}") String webhookBearer) {
        com.objwww.pr.control.eval.domain.repository.ReplayCaseReader caseReader =
                replayCaseReader.getIfAvailable(
                        () -> new com.objwww.pr.control.infrastructure.persistence
                                .PostgresReplayCaseReader(jdbc));
        return new ReplayScenarioDriver(
                new com.objwww.pr.control.infrastructure.persistence
                        .PostgresFrozenPayloadReader(jdbc),
                new ReplayScenarioDriver.HttpWebhook(webhookUrl, webhookBearer),
                java.time.Instant::now,
                craftedPayloadsOf(caseReader, datasetVersion));
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
                                             ScenarioEvaluator evaluator,
                                             JdbcClient jdbc,
                                             com.objwww.pr.control.eval.domain.repository.EvalReportJudge judge,
                                             com.objwww.pr.control.alert.domain.evidence.EvidenceRepository evidence) {
        // P4：SafetyGate 裁决落库面（eval_case_safety，V141）接评分链；
        // P7：LLM-judge 第三判定式（eval_case_judge，V145）——judge 未配置 =
        // fail-closed 不落行（HttpEvalReportJudge 内部 empty），缺席=未评如实；
        // 证据回退：NATIVE 链过程计数回退 rca_evidence 面（不恒 0）；
        // M-d T5：六要素检出版库面（eval_case_six_parts，V152）——缺席=未评如实
        return new SingleCaseScorer(runs, reports, investigations, toolCalls, evaluator,
                new com.objwww.pr.control.infrastructure.persistence
                        .PostgresEvalCaseSafetySink(jdbc),
                judge,
                new com.objwww.pr.control.infrastructure.persistence
                        .PostgresEvalCaseJudgeSink(jdbc),
                evidence,
                new com.objwww.pr.control.infrastructure.persistence
                        .PostgresEvalCaseSixPartsSink(jdbc));
    }

    /** P7 LLM-judge（OpenAI 兼容面；195=litellm-am3 代理）。base-url/api-key 缺席 =
     *  fail-closed 未启用（judge() 返回 empty，零落行零外呼） */
    @Bean
    public com.objwww.pr.control.eval.domain.repository.EvalReportJudge evalReportJudge(
            @Value("${app.alert.eval.judge.base-url:}") String baseUrl,
            @Value("${app.alert.eval.judge.api-key:}") String apiKey,
            @Value("${app.alert.eval.judge.model:qwen3-max}") String model) {
        return new com.objwww.pr.control.infrastructure.model.HttpEvalReportJudge(
                baseUrl, apiKey, model);
    }

    /** BA-172：eval profile 自持事务管理器（PersistenceConfig 与本品互斥不装载；
     *  DataSourceTransactionManager 构造不建连，装配隔离纪律保持） */
    @Bean
    public org.springframework.transaction.PlatformTransactionManager evalTransactionManager(
            javax.sql.DataSource dataSource) {
        return new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
    }

    /** BA-172：SingleCaseScorer 证据回退读面（rca_evidence）自持装配——曾漏配导致
     *  eval profile 启动即炸，装配隔离测试用 Mockito 桩掩盖了该缺口（桩已撤） */
    @Bean
    public com.objwww.pr.control.alert.domain.evidence.EvidenceRepository evidenceRepository(
            JdbcClient jdbc,
            org.springframework.transaction.PlatformTransactionManager txManager) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresEvidenceRepository(
                jdbc, new org.springframework.transaction.support.TransactionTemplate(txManager),
                new com.fasterxml.jackson.databind.ObjectMapper());
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
            @Value("${app.alert.eval.run-tag:}") String runTag,
            @Value("${app.alert.eval.resolver-skew-seconds:0}") long skewSeconds) {
        return new PostgresRcaRunResolver(jdbc, millis -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, runTag, skewSeconds);
    }

    /** flagd 管理面客户端提为共享 bean（DR-A 批）：driver 条件恢复与
     *  FlagdRestoreSweeper 截止清扫经 asAdminPort() 共用同一传输面 */
    @Bean
    public FlagdScenarioDriver.FlagAdminClient flagAdminClient(
            @Value("${app.alert.eval.flag-admin-url:http://flagd-admin:8081}")
            String flagAdminUrl) {
        return new FlagdScenarioDriver.FlagAdminClient.Http(flagAdminUrl);
    }

    /** DR-05 生产接线（DR-A 批）：四参构造——激活即落恢复台账（V95），台账 bean
     *  与 sweeper 收口共用同一实例（写账与收口同库同表）；两参无台账过渡构造
     *  仅留测试面对照 */
    @Bean
    public FlagdScenarioDriver flagdScenarioDriver(
            FlagdScenarioDriver.FlagAdminClient flagAdminClient, AlertProbe alertProbe,
            com.objwww.pr.control.drill.domain.repository.FlagdRestoreLedger
                    flagdRestoreLedger) {
        return new FlagdScenarioDriver(flagAdminClient, alertProbe, flagdRestoreLedger,
                java.time.Clock.systemUTC());
    }

    @Bean
    public ArenaTrafficClient arenaTrafficClient(
            @Value("${app.alert.eval.arena-base-url:http://order-arena:8080}")
            String arenaBaseUrl) {
        return new ArenaTrafficClient.Http(arenaBaseUrl);
    }

    /** chaos-admin 客户端提为共享 bean（DR-A 批）：eval 批跑驱动与
     *  ArenaChaosDrillInjection 演练注入面共用（token 仅 env 注入，INV-AM3-3） */
    @Bean
    public ChaosAdminClient chaosAdminClient(
            @Value("${app.alert.eval.chaos-admin-url:http://arena-chaos-admin:8080}")
            String chaosAdminUrl,
            @Value("${CHAOS_ADMIN_TOKEN:}") String adminToken) {
        return new ChaosAdminClient.Http(chaosAdminUrl, adminToken);
    }

    @Bean
    public ArenaChaosScenarioDriver arenaChaosScenarioDriver(
            ChaosAdminClient chaosAdminClient,
            AlertProbe alertProbe,
            ArenaTrafficClient traffic,
            @Value("${app.alert.eval.dataset-version:eval-ds-1}") String datasetVersion,
            @Value("${app.alert.eval.run-tag:}") String runTag) {
        return new ArenaChaosScenarioDriver(chaosAdminClient, alertProbe, traffic,
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
                                           ReplayScenarioDriver replay,
                                           AlertProbe alertProbe,
                                           IncidentResolutionProbe incidentProbe,
                                           RcaRunResolver resolver,
                                           SingleCaseScorer scorer,
                                           EvalRunRepository evalRuns,
                                           BaselineReportGenerator generator,
                                           EvalRunMetadata metadata,
                                           @Value("${app.eval.rounds:2}") int rounds) {
        return new EvalBatchRunner(registry, Map.of(
                        "FlagdScenarioDriver", flagd,
                        "ArenaChaosScenarioDriver", arena,
                        "InfrastructureScenarioDriver", infra,
                        "ReplayScenarioDriver", replay),
                alertProbe, incidentProbe, resolver, scorer, evalRuns, generator, metadata,
                rounds, systemClock());
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

    /** PAGE-03 能力闸门（worker 侧复验源）：模式/数据集版本取本装配真实事实源，
     *  覆盖项与限额执行面未实现全闭——开放任一项时先补执行面再改这里。
     *  SAFE-02：launch-enabled 默认关闭，与命令面同源拒绝（领取后复验 → REJECTED） */
    @Bean
    public EvalLaunchGate evalLaunchGate(
            @Value("${app.alert.eval.dataset-version:eval-ds-1}") String datasetVersion,
            @Value("${app.eval.launch.modes:L}") String modes,
            @Value("${app.eval.launch.max-concurrency:1}") int maxConcurrency,
            @Value("${app.eval.launch.max-rounds:30}") int maxRounds,
            @Value("${app.eval.launch.enabled:false}") boolean launchEnabled) {
        return new EvalLaunchGate(java.util.Arrays.stream(modes.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toSet()),
                java.util.Set.of(datasetVersion), maxConcurrency, maxRounds,
                false, false, false, false, launchEnabled);
    }

    @Bean
    public EvalLaunchExecutor evalLaunchExecutor(
            GoldenScenarioRegistry registry,
            FlagdScenarioDriver flagd,
            ArenaChaosScenarioDriver arena,
            InfrastructureScenarioDriver infra,
            ReplayScenarioDriver replay,
            AlertProbe alertProbe,
            IncidentResolutionProbe incidentProbe,
            RcaRunResolver resolver,
            SingleCaseScorer scorer,
            EvalRunRepository evalRuns,
            BaselineReportGenerator generator,
            com.objwww.pr.control.eval.domain.repository.EvalPhaseEventSink phaseSink,
            com.objwww.pr.control.eval.domain.repository.EvalRunCommandRepository commands,
            EvalRunMetadata metadata,
            EvalLaunchGate gate,
            EvalComparisonAutoRecorder evalComparisonAutoRecorder,
            @Value("${app.alert.eval.worker.id:eval-worker-1}") String workerId,
            @Value("${app.eval.rounds:2}") int defaultRounds) {
        return new EvalLaunchExecutor(registry, Map.of(
                        "FlagdScenarioDriver", flagd,
                        "ArenaChaosScenarioDriver", arena,
                        "InfrastructureScenarioDriver", infra,
                        "ReplayScenarioDriver", replay),
                alertProbe, incidentProbe, resolver, scorer, evalRuns, generator,
                phaseSink, commands, metadata, defaultRounds, systemClock(), workerId, gate,
                evalComparisonAutoRecorder);
    }

    // ---------------- EV-07 终态自动落档（eval_comparison；eval_app 授权面 V149） ----------------

    /** worker 侧对比读面（与 docker profile 的 PostgresEvalQueryReader 同实现，
     *  本 profile 自持装配——EvalRunnerConfig 与 PersistenceConfig 互斥） */
    @Bean
    public com.objwww.pr.control.eval.domain.repository.EvalQueryReader evalQueryReader(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence
                .PostgresEvalQueryReader(jdbc);
    }

    @Bean
    public com.objwww.pr.control.eval.domain.repository.EvalComparisonRepository
            evalComparisonRepository(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence
                .PostgresEvalComparisonRepository(jdbc);
    }

    @Bean
    public EvalCompareService evalCompareService(
            com.objwww.pr.control.eval.domain.repository.EvalQueryReader evalQueryReader,
            com.objwww.pr.control.eval.domain.repository.EvalComparisonRepository
                    evalComparisonRepository) {
        return new EvalCompareService(evalQueryReader, evalComparisonRepository,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    /** 终态自动落档钩子（幂等 + 无 baseline 诚实跳过 + 失败不拖垮 finalize，语义见类注释） */
    @Bean
    public EvalComparisonAutoRecorder evalComparisonAutoRecorder(
            com.objwww.pr.control.eval.domain.repository.EvalQueryReader evalQueryReader,
            com.objwww.pr.control.eval.domain.repository.EvalComparisonRepository
                    evalComparisonRepository,
            EvalCompareService evalCompareService,
            @Value("${app.alert.eval.worker.id:eval-worker-1}") String workerId) {
        return new EvalComparisonAutoRecorder(evalQueryReader, evalComparisonRepository,
                evalCompareService, "auto-terminal:" + workerId);
    }

    @Bean
    public EvalRunWorker evalRunWorker(
            com.objwww.pr.control.eval.domain.repository.EvalRunCommandRepository commands,
            EvalRunRepository evalRuns,
            EvalLaunchExecutor executor,
            UsageLedgerService ledger,
            WorkerSchemaFreshnessGuard freshness,
            @Value("${app.alert.eval.worker.id:eval-worker-1}") String workerId,
            @Value("${app.alert.eval.worker.poll-seconds:5}") long pollSeconds,
            @Value("${app.alert.eval.worker.stale-claim-seconds:900}")
            long staleClaimSeconds) {
        return new EvalRunWorker(commands, evalRuns, executor, ledger, systemClock(),
                workerId, pollSeconds, staleClaimSeconds, freshness);
    }

    /** 陈旧 worker 自拒护栏（2026-09-17 实证修复）：DB flyway 最大版本 vs 本进程
     *  classpath 迁移面；eval_app 读面授权见 V143 */
    @Bean
    public WorkerSchemaFreshnessGuard workerSchemaFreshnessGuard(JdbcClient jdbc) {
        return new com.objwww.pr.control.eval.infrastructure
                .ClasspathFlywayFreshnessGuard(jdbc);
    }

    // ---------------- usage 对账（M3-25；未配置 litellm 时诚实降级 UNMATCHED/BEST_EFFORT） ----------------

    /**
     * master key 仅 env 注入（INV-AM3-3）；base-url 为空 = 未接 proxy，出账按 chain③
     * 降级（UNMATCHED/BEST_EFFORT），批件不受影响。wait 兜 proxy spend 日志异步 flush
     * （spike 实测 ~16s）。
     */
    @Bean
    public UsageLedgerService usageLedgerService(EvalRunRepository evalRuns,
                                                 com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger rcaModelCallLedger,
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
        return new UsageLedgerService(evalRuns, rcaModelCallLedger, port,
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
     * 都不会在装配测试里执行）。FUP-01：mode 未知值启动失败；once 经同源
     * EvalLaunchGate 闭面判定（闭面期禁止一次性跑批）。
     */
    @Bean
    public EvalRunnerMain evalRunnerMain(EvalBatchRunner runner,
                                         UsageLedgerService ledger,
                                         ConfigurableApplicationContext context,
                                         EvalRunWorker worker,
                                         com.objwww.pr.control.drill.application.DrillWorker
                                                 drillWorker,
                                         EvalLaunchGate evalLaunchGate,
                                         @Value("${app.alert.eval.worker.mode:worker}")
                                         String workerMode) {
        return new EvalRunnerMain(runner, ledger, context, worker, drillWorker,
                evalLaunchGate, workerMode);
    }

    /**
     * FUP-01 演练执行政策（单一事实源）：drillWorker 领取复验与 drillInjectionPort
     * 最终副作用边界共用本 bean；配置启动时加载（非热更新，翻转需重启生效），
     * policyFingerprint 供与 API 进程（PersistenceConfig 同源构造）核对一致。
     */
    @Bean
    public com.objwww.pr.control.drill.application.DrillExecutionPolicy
            drillExecutionPolicy(
            @Value("${app.drill.launch-enabled:false}") boolean launchEnabled,
            @Value("${app.drill.target-envs:arena-195}") String targetEnvs) {
        return new com.objwww.pr.control.drill.application.DrillExecutionPolicy(
                launchEnabled, splitTargetEnvs(targetEnvs));
    }

    // ---------------- DR 演练 worker（§7.3：由已有评测执行身份所在的 worker 领取；
    //   eval_app 授权面 V86/V95/V150；DR-A 批接线交付：复合注入端口（DR-03）+ 恢复台账/
    //   sweeper（DR-05）+ 关联回填（DR-06）；DR-04 批：复合恢复/核验端口接线，
    //   OBSERVING→RECOVERING→VERIFYING→CLOSED 全链由 worker 相位驱动） ----------------

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

    /** DR-05 恢复台账（V95 flagd_restore_ledger）：driver 激活写账与 sweeper
     *  截止收口共用本 bean 实例（同库同表） */
    @Bean
    public com.objwww.pr.control.drill.domain.repository.FlagdRestoreLedger
            flagdRestoreLedger(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence
                .PostgresFlagdRestoreLedger(jdbc);
    }

    /** DR-05 台账级截止清扫（每拍 + 启动对账超 deadline 仍 OPEN/UNKNOWN 的台账行；
     *  传输面与 driver 共用 flagAdminClient.asAdminPort()） */
    @Bean
    public com.objwww.pr.control.drill.application.FlagdRestoreSweeper
            flagdRestoreSweeper(
            FlagdScenarioDriver.FlagAdminClient flagAdminClient,
            com.objwww.pr.control.drill.domain.repository.FlagdRestoreLedger
                    flagdRestoreLedger) {
        return new com.objwww.pr.control.drill.application.FlagdRestoreSweeper(
                flagAdminClient.asAdminPort(), flagdRestoreLedger,
                java.time.Instant::now);
    }

    /** DR-06 关联回填读面（eval_app 经 V11 只读 incident；匹配不到保持 null 不猜） */
    @Bean
    public com.objwww.pr.control.drill.application.DrillCorrelationPort
            drillCorrelationPort(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence
                .PostgresDrillCorrelationReader(jdbc);
    }

    /** DR-03 arena-chaos 注入适配（与 eval 批跑驱动共用 ChaosAdminClient 传输面） */
    @Bean
    public com.objwww.pr.control.drill.application.ArenaChaosDrillInjection
            arenaChaosDrillInjection(
            ChaosAdminClient chaosAdminClient,
            AlertProbe alertProbe,
            ArenaTrafficClient arenaTrafficClient,
            @Value("${app.alert.eval.dataset-version:eval-ds-1}") String datasetVersion) {
        return new com.objwww.pr.control.drill.application.ArenaChaosDrillInjection(
                chaosAdminClient, alertProbe, arenaTrafficClient, datasetVersion);
    }

    /** DR-03 flagd 注入适配（复用四参 FlagdScenarioDriver——激活即落恢复台账） */
    @Bean
    public com.objwww.pr.control.drill.application.FlagdDrillInjection
            flagdDrillInjection(FlagdScenarioDriver flagdScenarioDriver) {
        return new com.objwww.pr.control.drill.application.FlagdDrillInjection(
                flagdScenarioDriver);
    }

    /** DR-04 flagd 恢复适配（与 driver/sweeper 共用台账与传输面，三面 CAS 恰一方收口） */
    @Bean
    public com.objwww.pr.control.drill.application.FlagdDrillRecovery
            flagdDrillRecovery(
            FlagdScenarioDriver.FlagAdminClient flagAdminClient,
            com.objwww.pr.control.drill.domain.repository.FlagdRestoreLedger
                    flagdRestoreLedger) {
        return new com.objwww.pr.control.drill.application.FlagdDrillRecovery(
                flagAdminClient.asAdminPort(), flagdRestoreLedger,
                java.time.Instant::now);
    }

    /** DR-04 arena-chaos 恢复适配（与注入面共用 ChaosAdminClient；off CAS + status 读面） */
    @Bean
    public com.objwww.pr.control.drill.application.ArenaChaosDrillRecovery
            arenaChaosDrillRecovery(ChaosAdminClient chaosAdminClient) {
        return new com.objwww.pr.control.drill.application.ArenaChaosDrillRecovery(
                chaosAdminClient);
    }

    /** DR-04 复合恢复/核验端口：按模板 driver 分派 kind 适配器 + 共享告警面核验
     *  （恢复是收场方向，不经 launch 能力位把守） */
    @Bean
    public com.objwww.pr.control.drill.application.DrillRecoveryPort
            drillRecoveryPort(
            com.objwww.pr.control.drill.application.DrillTemplateCatalog
                    drillTemplateCatalog,
            GoldenScenarioRegistry goldenScenarioRegistry,
            com.objwww.pr.control.drill.application.ArenaChaosDrillRecovery
                    arenaChaosDrillRecovery,
            com.objwww.pr.control.drill.application.FlagdDrillRecovery
                    flagdDrillRecovery,
            AlertProbe alertProbe) {
        return new com.objwww.pr.control.drill.application.CompositeDrillRecovery(
                drillTemplateCatalog, goldenScenarioRegistry,
                arenaChaosDrillRecovery, flagdDrillRecovery, alertProbe);
    }

    /** DR-03 复合注入端口（DR-A 批接线交付）：FUP-01 同源执行政策最终边界 +
     *  公共闸门（ready/白名单/参数…）+ 按模板 driver 分派；NotImplemented 保留为
     *  fail-closed 兜底与测试对照，不再装配 */
    @Bean
    public com.objwww.pr.control.drill.application.DrillInjectionPort
            drillInjectionPort(
            com.objwww.pr.control.drill.application.DrillTemplateCatalog
                    drillTemplateCatalog,
            GoldenScenarioRegistry goldenScenarioRegistry,
            com.objwww.pr.control.drill.application.ArenaChaosDrillInjection
                    arenaChaosDrillInjection,
            com.objwww.pr.control.drill.application.FlagdDrillInjection
                    flagdDrillInjection,
            com.objwww.pr.control.drill.application.DrillExecutionPolicy
                    drillExecutionPolicy,
            @Value("${app.drill.target-envs:arena-195}") String targetEnvs) {
        return new com.objwww.pr.control.drill.application.CompositeDrillInjection(
                drillTemplateCatalog, goldenScenarioRegistry,
                splitTargetEnvs(targetEnvs), arenaChaosDrillInjection,
                flagdDrillInjection, drillExecutionPolicy);
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
            com.objwww.pr.control.drill.application.DrillRecoveryPort
                    drillRecoveryPort,
            com.objwww.pr.control.drill.application.DrillCorrelationPort
                    drillCorrelationPort,
            com.objwww.pr.control.drill.application.FlagdRestoreSweeper
                    flagdRestoreSweeper,
            com.objwww.pr.control.drill.application.DrillExecutionPolicy
                    drillExecutionPolicy,
            @Value("${app.drill.target-envs:arena-195}") String targetEnvs,
            @Value("${app.alert.eval.worker.id:eval-worker-1}") String workerId,
            @Value("${app.drill.worker.poll-seconds:5}") long pollSeconds,
            @Value("${app.drill.worker.stale-claim-seconds:900}")
            long staleClaimSeconds) {
        return new com.objwww.pr.control.drill.application.DrillWorker(
                drillJobRepository, drillEventRepository, drillTemplateCatalog,
                drillInjectionPort, drillRecoveryPort, drillClock(),
                splitTargetEnvs(targetEnvs),
                workerId + "-drill", pollSeconds, staleClaimSeconds,
                drillCorrelationPort, flagdRestoreSweeper, drillExecutionPolicy);
    }

    /** app.drill.target-envs 拆分（worker 领取白名单与复合注入端口靶场白名单
     *  两处共用同一配置源，防漂移） */
    private static java.util.List<String> splitTargetEnvs(String targetEnvs) {
        return java.util.Arrays.stream(targetEnvs.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
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
