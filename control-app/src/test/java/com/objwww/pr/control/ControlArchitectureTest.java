package com.objwww.pr.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.application.CheckpointWriter;
import com.objwww.pr.control.application.IntakeCommand;
import com.objwww.pr.control.application.ReviewStepExecutor;
import com.objwww.pr.control.application.StepExecutionContext;
import com.objwww.pr.control.domain.ai.MockModelGateway;
import com.objwww.pr.control.domain.ai.CostCalculation;
import com.objwww.pr.control.domain.ai.ModelCallContext;
import com.objwww.pr.control.domain.ai.ModelCallFailure;
import com.objwww.pr.control.domain.ai.ModelCallLedgerRepository;
import com.objwww.pr.control.domain.ai.ModelGatewayPort;
import com.objwww.pr.control.domain.ai.ModelRoute;
import com.objwww.pr.control.domain.ai.ModelRouteIdentity;
import com.objwww.pr.control.domain.ai.ModelStepBudgetGuard;
import com.objwww.pr.control.domain.ai.PricingService;
import com.objwww.pr.control.domain.ai.RouteDecision;
import com.objwww.pr.control.domain.ai.RoutedModelResult;
import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.model.RunStep;
import com.objwww.pr.control.domain.model.StepCheckpoint;
import com.objwww.pr.control.domain.model.WorkItem;
import com.objwww.pr.control.domain.review.ModelFinding;
import com.objwww.pr.control.domain.review.FindingMapper;
import com.objwww.pr.control.domain.review.ReviewAgentLoop;
import com.objwww.pr.control.domain.review.ReviewContractVersions;
import com.objwww.pr.control.domain.service.CheckpointContract;
import com.objwww.pr.control.domain.service.CheckpointResumeService;
import com.objwww.pr.control.domain.review.ReviewBudget;
import com.objwww.pr.shared.snapshot.SafeTarExtractor;
import com.objwww.pr.control.domain.tool.PolicyEngine;
import com.objwww.pr.control.domain.tool.ToolRegistry;
import com.objwww.pr.control.support.OrchestratorFixture;
import com.objwww.pr.control.support.TestTarballs;
import com.objwww.pr.shared.Digest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Control 侧静态架构卡点（AFT-01/02s/03，§12 L0）。
 * 普通 @Test + ClassFileImporter（本环境 @ArchTest 字段发现不生效，沿用 publisher 侧惯例）。
 *
 * <p>评审修正记录（AFT-01 Jackson 豁免）：com.fasterxml.jackson 不在黑名单——domain.review 的
 * FindingJsonParser 用 Jackson 树模型手工取字段解析模型输出 JSON，属有意的已知偏差
 * （模型输出本质是 JSON，树模型不引入绑定注解/框架语义；收紧为自研解析器收益为负）。
 * commons-compress（SafeTarExtractor）同属 T06 决策的允许清单，不做白名单一刀切。
 */
class ControlArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.objwww.pr.control");
    }

    /** AFT-01：domain 黑名单——Spring/模型 SDK/JDBC/GitHub SDK/HTTP 客户端一律禁入 */
    @Test
    void domainHasNoFrameworkOrSdkDependency() {
        noClasses().that().resideInAPackage("..control.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",      // 框架
                        "org.springframework.ai..", // 模型 SDK（上单条已覆盖，单列只为可读性）
                        "java.sql..",               // JDBC
                        "com.github..", "org.kohsuke.github..", // GitHub SDK
                        "org.apache.http..", "java.net.http..") // HTTP 客户端也不许进 domain
                .check(classes);
    }

    /** AFT-02s：Control 任何类不得触碰 publisher 写路径实现（I2 静态门） */
    @Test
    void controlNeverReferencesPublisherWriteInfrastructure() {
        noClasses().that().resideInAPackage("..control..")
                .should().dependOnClassesThat().resideInAnyPackage("..publisher.infrastructure..")
                .check(classes);
        noClasses().that().resideInAPackage("..control..")
                .should().dependOnClassesThat().haveSimpleNameContaining("GitHubWriteAdapter")
                .orShould().dependOnClassesThat().haveSimpleNameContaining("CredentialBroker")
                .check(classes);
    }

    /** AFT-03 包向：模型交互包（ai/model 适配/review 解析）不得依赖发布/凭证/审批包 */
    @Test
    void reviewAndAiPackagesDoNotDependOnPublicationOrCredential() {
        noClasses().that().resideInAnyPackage(
                        "..control.domain.review..", "..control.domain.ai..", "..control.infrastructure.model..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..publisher..", "..credential..", "..publication..", "..approval..")
                .check(classes);
    }

    /** AFT-15：恢复/修复规划只编排端口；不得越过 OutboxWriter 或触碰 GitHub/凭证实现。 */
    @Test
    void checkpointAndRepairPlannerStayAwayFromExternalWriteInfrastructure() {
        noClasses().that().haveSimpleName("RepairPlanner")
                .or().haveSimpleName("CheckpointResumeService")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..infrastructure.github..", "..credential..", "java.net.http..")
                .orShould().dependOnClassesThat().haveSimpleName("OutboxWriter")
                .check(classes);
    }

    /** AFT-17 存在向：checkpoint 五分量及其四个代码契约版本常量必须显式存在。 */
    @Test
    void checkpointContractKeepsAllFiveVersionedComponents() {
        assertThat(java.util.Arrays.stream(CheckpointContract.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList())
                .containsExactly("promptTemplateVersion", "findingSchemaVersion",
                        "mapperContractVersion", "contextBuilderVersion", "modelIdentity");
        assertThat(ReviewAgentLoop.PROMPT_TEMPLATE_VERSION).isNotBlank();
        assertThat(ReviewAgentLoop.CONTEXT_BUILDER_VERSION).isNotBlank();
        assertThat(ReviewContractVersions.FINDING_SCHEMA_VERSION).isNotBlank();
        assertThat(FindingMapper.CONTRACT_VERSION).isNotBlank();
    }

    /**
     * AFT-17 接线向（回指 I18，防 bump 纪律落空）：四个版本常量必须被 CheckpointContract
     * 唯一生产构造点 ReviewStepExecutor 实际接线，且 digest 列与五分量列一致（防半截接线）。
     * 常量均为编译期 String 字面量，javac 内联后字节码中无字段引用，ArchUnit 静态规则
     * 看不到接线关系——故本断言行为化：真跑一次 executor，对其写出的 step_checkpoint
     * 逐列对拍常量期望值。红绿验证：把 ReviewStepExecutor 任一分量改成字面量即红。
     */
    @Test
    void checkpointContractConstantsAreActuallyWired() {
        Digest snapshotDigest = Digest.sha256Of("aft17-snap");
        Digest diffDigest = Digest.sha256Of("aft17-diff");
        OrchestratorFixture fx = new OrchestratorFixture();
        MockModelGateway modelClient = new MockModelGateway();
        ReviewRun run = fx.orchestrator.runIntake(new IntakeCommand(987L, 12345L, "org/repo", 7,
                PrSubjectState.OPEN, false, false, "head1", "main", "base1", null,
                diffDigest, snapshotDigest,
                "aft17-policy-v1", "aft17-prompt-v1", "aft17-toolset-v1", "d-1", null));
        RunStep step = fx.steps.findByRunId(run.getId()).get(0);
        WorkItem item = fx.workItems.findByStepId(step.getId()).orElseThrow();
        Instant now = Instant.now();
        item.leaseTo("aft17-worker", now.plusSeconds(600), now);
        ObjectMapper mapper = new ObjectMapper();
        ReviewStepExecutor executor = new ReviewStepExecutor(fx.runs, fx.revisions, fx.cas,
                fx.artifacts, new SafeTarExtractor(10000, 100 * 1024 * 1024, 1024L * 1024 * 1024),
                new ReviewAgentLoop(modelClient, new FindingMapper(),
                        new PolicyEngine(new ToolRegistry())),
                ReviewBudget.DEFAULT, mapper,
                new CheckpointResumeService(fx.checkpoints, fx.artifacts, fx.cas, fx.ledger, mapper),
                new CheckpointWriter(fx.artifacts, fx.checkpoints, fx.ledger),
                fx.ledger, requestedModel -> java.util.Optional.of(
                        new ModelRouteIdentity("mock-provider", requestedModel, "v1")));
        fx.cas.putIfAbsent(snapshotDigest, TestTarballs.tarGz(out ->
                TestTarballs.file(out, TestTarballs.GH_PREFIX + "a/Foo.java", "int x = 0/1;\n")));
        fx.cas.putIfAbsent(diffDigest, "diff".getBytes(StandardCharsets.UTF_8));
        modelClient.enqueueContent("[]");

        executor.execute(new StepExecutionContext(item, step, java.util.UUID.randomUUID()), () -> true);

        StepCheckpoint checkpoint = fx.checkpoints.all().stream()
                .filter(c -> c.checkpointKey().equals(StepCheckpoint.REVIEW_OUTCOME))
                .findFirst().orElseThrow();
        CheckpointContract expected = new CheckpointContract(
                run.getPromptVersion() + "/" + ReviewAgentLoop.PROMPT_TEMPLATE_VERSION,
                ReviewContractVersions.FINDING_SCHEMA_VERSION,
                FindingMapper.CONTRACT_VERSION,
                ReviewAgentLoop.CONTEXT_BUILDER_VERSION,
                "mock-provider/mock-model/v1");
        assertThat(checkpoint.promptTemplateVersion()).isEqualTo(expected.promptTemplateVersion());
        assertThat(checkpoint.findingSchemaVersion()).isEqualTo(expected.findingSchemaVersion());
        assertThat(checkpoint.mapperContractVersion()).isEqualTo(expected.mapperContractVersion());
        assertThat(checkpoint.contextBuilderVersion()).isEqualTo(expected.contextBuilderVersion());
        assertThat(checkpoint.modelIdentity()).isEqualTo(expected.modelIdentity());
        assertThat(checkpoint.checkpointContractDigest()).isEqualTo(expected.digest());
    }

    /** AFT-03 schema 向：模型输出 schema（解析目标 ModelFinding，含嵌套类型）无 token/url/permission 字段 */
    @Test
    void modelOutputSchemaHasNoCredentialOrUrlFields() {
        assertNoCredentialFields(ModelFinding.class);
    }

    /** 反射断言：给定类型及其嵌套项目内字段类型的字段名集合，无凭证/寻址语义 */
    static void assertNoCredentialFields(Class<?>... schemaTypes) {
        Set<String> names = new HashSet<>();
        for (Class<?> type : schemaTypes) {
            collectFieldNames(type, names, new HashSet<>());
        }
        assertThat(names).noneMatch(n -> n.contains("token") || n.contains("url")
                || n.contains("permission") || n.contains("secret")
                || n.contains("password") || n.contains("credential") || n.contains("apikey"));
    }

    private static void collectFieldNames(Class<?> type, Set<String> names, Set<Class<?>> seen) {
        if (!seen.add(type)) {
            return;
        }
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            names.add(f.getName().toLowerCase(Locale.ROOT));
            Class<?> ft = f.getType();
            // 只递归项目内类型（JDK/第三方类型的字段不属于 schema 契约）
            if (!ft.isPrimitive() && !ft.isEnum()
                    && ft.getPackageName().startsWith("com.objwww")) {
                collectFieldNames(ft, names, seen);
            }
        }
    }

    // ==================== M3 L0（AFT-19~31 control 侧，M3 方案 §11/L0） ====================

    /**
     * AFT-19（I28）：模型调用唯一出口——ReviewAgentLoop 只依赖上层端口 ModelGatewayPort，
     * 不直接触碰底层 RouteClientPort / ModelGateway / 路由与熔断实现；RouteClientPort 仅允许被
     * ModelGateway 与其装配点 M3ModelGatewayConfig 依赖；Spring AI 只允许出现在
     * infrastructure.model 包。
     */
    @Test
    void modelGatewayIsTheOnlyModelCallExit() {
        classes().that().haveSimpleName("ReviewAgentLoop")
                .should().dependOnClassesThat().haveSimpleName("ModelGatewayPort")
                .check(classes);
        noClasses().that().haveSimpleName("ReviewAgentLoop")
                .should().dependOnClassesThat().haveSimpleName("RouteClientPort")
                .orShould().dependOnClassesThat().haveSimpleName("ModelGateway")
                .orShould().dependOnClassesThat().haveSimpleName("SpringAiRouteClient")
                .orShould().dependOnClassesThat().haveSimpleName("ModelRouter")
                .orShould().dependOnClassesThat().haveSimpleName("CircuitBreaker")
                .check(classes);
        noClasses().that().resideInAPackage("..control..")
                .and().doNotHaveSimpleName("ModelGateway")
                .and().doNotHaveSimpleName("M3ModelGatewayConfig")
                .and().doNotHaveSimpleName("SpringAiRouteClient") // 端口实现本身，豁免
                .should().dependOnClassesThat().haveSimpleName("RouteClientPort")
                .check(classes);
        noClasses().that().resideInAPackage("..control..")
                .and().resideOutsideOfPackage("..control.infrastructure.model..")
                .and().doNotHaveSimpleName("M3ModelGatewayConfig") // 手工装配点（§4.10），豁免
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai..")
                .check(classes);
    }

    /**
     * AFT-20（I28；§4.2）：故障分类与路由决策是封闭类型，新增故障类必须编译期显式处置——
     * ModelCallFailure/RouteDecision 均为 sealed；Router/Gateway 的决策 switch 无 default
     * 兜底分支（文本守卫：default 会把新故障类静默吞进模糊路径，fail-closed 变 fail-open）。
     */
    @Test
    void failureAndDecisionTypesAreSealedWithNoDefaultBranch() throws Exception {
        assertThat(ModelCallFailure.class.isSealed()).as("ModelCallFailure 必须 sealed").isTrue();
        assertThat(RouteDecision.class.isSealed()).as("RouteDecision 必须 sealed").isTrue();
        for (String file : new String[]{
                "src/main/java/com/objwww/pr/control/domain/ai/ModelRouter.java",
                "src/main/java/com/objwww/pr/control/application/ModelGateway.java"}) {
            assertThat(java.nio.file.Files.readString(java.nio.file.Path.of(file)))
                    .as("%s 的决策 switch 不得有 default 兜底分支", file)
                    .doesNotContainPattern("(?m)^\\s*default\\s*[:>-]");
        }
    }

    /**
     * AFT-21 重写（I32）：成本金额不参与决策——类型依赖约束：预算与门控类
     * （ModelStepBudgetGuard/ReviewBudget/PolicyEngine）不得依赖成本类型
     * （CostCalculation/PricingService）；辅以反射断言 ModelStepBudgetGuard 的字段与方法
     * 签名中不出现成本/价格类型（替代 v1.0 易误报可绕过的源码文本搜索；
     * 行为面由 ModelStepBudgetGuardTest 覆盖）。
     */
    @Test
    void budgetAndGateClassesDoNotDependOnCostTypes() {
        noClasses().that().haveSimpleName("ModelStepBudgetGuard")
                .or().haveSimpleName("ReviewBudget")
                .or().haveSimpleName("PolicyEngine")
                .should().dependOnClassesThat().haveSimpleName("CostCalculation")
                .orShould().dependOnClassesThat().haveSimpleName("PricingService")
                .check(classes);
        for (java.lang.reflect.Method m : ModelStepBudgetGuard.class.getDeclaredMethods()) {
            assertThat(m.getReturnType()).isNotIn(CostCalculation.class, PricingService.class);
            assertThat(m.getParameterTypes()).doesNotContain(CostCalculation.class, PricingService.class);
        }
        for (Field f : ModelStepBudgetGuard.class.getDeclaredFields()) {
            assertThat(f.getType()).isNotIn(CostCalculation.class, PricingService.class);
        }
    }

    /**
     * AFT-23（I28；§3.1）：Gateway 上下层端口隔离——ModelGatewayPort（上层，domain 侧唯一
     * 消费者是 ReviewAgentLoop）与 RouteClientPort（底层，domain 零依赖）分离。
     */
    @Test
    void gatewayUpperAndLowerPortsAreSeparated() {
        noClasses().that().resideInAPackage("..control.domain..")
                .and().doNotHaveSimpleName("ReviewAgentLoop")
                .should().dependOnClassesThat().haveSimpleName("ModelGatewayPort")
                .check(classes);
        noClasses().that().resideInAPackage("..control.domain..")
                .should().dependOnClassesThat().haveSimpleName("RouteClientPort")
                .check(classes);
    }

    /** AFT-24（§3.1）：domain/ai 零 Spring AI、HTTP client、JDBC、Spring Retry 依赖。 */
    @Test
    void domainAiHasNoFrameworkHttpJdbcOrRetryDependency() {
        noClasses().that().resideInAPackage("..control.domain.ai..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "org.springframework.retry..",
                        "java.net.http..", "org.apache.http..", "java.sql..")
                .check(classes);
    }

    /**
     * AFT-25（§3.1 ModelCallContext）：调用上下文显式传递——三模块 src/main 全库禁止
     * ThreadLocal/InheritableThreadLocal 使用形态（ThreadLocalRandom 是退避抖动随机源，
     * 不传递上下文，豁免；javadoc 中的纪律文字提及不是使用形态，不在扫描列）。
     * surefire 工作目录 = 模块 basedir，相对路径与 SqlGuardTest 先例一致。
     */
    @Test
    void noThreadLocalContextPassingAnywhere() throws Exception {
        for (String module : new String[]{"src/main/java", "../publisher-app/src/main/java",
                "../shared-kernel/src/main/java"}) {
            try (java.util.stream.Stream<java.nio.file.Path> stream =
                         java.nio.file.Files.walk(java.nio.file.Path.of(module))) {
                for (java.nio.file.Path p : stream.filter(f -> f.toString().endsWith(".java")).toList()) {
                    assertThat(java.nio.file.Files.readString(p))
                            .as("%s 不得使用 ThreadLocal 传递上下文", p)
                            .doesNotContain("ThreadLocal<")
                            .doesNotContain("ThreadLocal.withInitial")
                            .doesNotContain("new ThreadLocal")
                            .doesNotContain("InheritableThreadLocal");
                }
            }
        }
    }

    /**
     * AFT-26（I29）：账本仓储写方法封闭集 + 无旁路 SQL——ModelCallLedgerRepository 的方法集
     * 精确等于 {insertStarted, completeTerminalSuccess, completeTerminalFailure,
     * markUnknownOlderThan}（无通用 update/delete）；control src/main 中表名
     * model_call_ledger 只允许出现在 PostgresModelCallLedgerRepository（防绕过仓储的旁路写）。
     */
    @Test
    void ledgerRepositoryWriteSurfaceIsClosedAndNoBypassSql() throws Exception {
        assertThat(java.util.Arrays.stream(ModelCallLedgerRepository.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder("insertStarted", "completeTerminalSuccess",
                        "completeTerminalFailure", "markUnknownOlderThan");
        try (java.util.stream.Stream<java.nio.file.Path> stream =
                     java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            for (java.nio.file.Path p : stream.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (p.endsWith("PostgresModelCallLedgerRepository.java")) {
                    continue;
                }
                assertThat(java.nio.file.Files.readString(p))
                        .as("%s 不得出现 model_call_ledger 旁路 SQL", p)
                        .doesNotContain("model_call_ledger");
            }
        }
    }

    /**
     * AFT-27（I32）：决策链路（Router/Breaker/BudgetGuard/PolicyEngine）的依赖图与源码中
     * 不出现成本/价格语义（CostCalculation/PricingService/costMicros/cost_micros）。
     */
    @Test
    void decisionChainDoesNotSeeCosts() throws Exception {
        noClasses().that().haveSimpleName("ModelRouter")
                .or().haveSimpleName("CircuitBreaker")
                .or().haveSimpleName("ModelStepBudgetGuard")
                .or().haveSimpleName("PolicyEngine")
                .should().dependOnClassesThat().haveSimpleName("CostCalculation")
                .orShould().dependOnClassesThat().haveSimpleName("PricingService")
                .check(classes);
        for (String file : new String[]{
                "src/main/java/com/objwww/pr/control/domain/ai/ModelRouter.java",
                "src/main/java/com/objwww/pr/control/domain/ai/CircuitBreaker.java",
                "src/main/java/com/objwww/pr/control/domain/ai/ModelStepBudgetGuard.java",
                "src/main/java/com/objwww/pr/control/domain/tool/PolicyEngine.java"}) {
            assertThat(java.nio.file.Files.readString(java.nio.file.Path.of(file)))
                    .as("%s 不得出现成本字段/价格语义", file)
                    .doesNotContain("costMicros")
                    .doesNotContain("cost_micros");
        }
    }

    /**
     * AFT-28（§4.11）：路由身份/故障/账本/上下文类型的字段集不得包含密钥材料字段
     * （apiKey/Authorization/Bearer/secret/password/privateKey）。
     * usage token 计数（promptTokens 等）是计费用量不是密钥材料，不在禁令内。
     */
    @Test
    void governanceTypesHaveNoSecretMaterialFields() {
        Set<String> names = new HashSet<>();
        Set<Class<?>> seen = new HashSet<>();
        for (Class<?> type : new Class<?>[]{
                ModelRoute.class, ModelRouteIdentity.class,
                com.objwww.pr.control.domain.ai.ModelCallLedgerEntry.class,
                RoutedModelResult.class,
                com.objwww.pr.control.domain.ai.RouteCallOutcome.class,
                ModelCallContext.class, RouteDecision.class, ModelCallFailure.class}) {
            collectFieldNames(type, names, seen);
            for (Class<?> nested : type.getDeclaredClasses()) {
                collectFieldNames(nested, names, seen);
            }
        }
        assertThat(names)
                .as("治理类型字段名不得含密钥材料语义，实际: %s", names)
                .noneMatch(n -> n.matches(".*(apikey|api_key|authorization|bearer|secret"
                        + "|password|privatekey).*"));
    }

    /**
     * AFT-29（I30）：checkpoint 契约身份与实际路由身份是两个独立概念——
     * CheckpointContract.modelIdentity 是 canonical String（持久化契约面），
     * RoutedModelResult.contractIdentity 是 ModelRouteIdentity 记录（运行时路由面），
     * 两者类型不同，禁止混用字段。
     */
    @Test
    void checkpointContractIdentityIsDistinctFromRouteIdentity() {
        assertThat(recordComponentType(CheckpointContract.class, "modelIdentity"))
                .isEqualTo(String.class);
        assertThat(recordComponentType(RoutedModelResult.class, "contractIdentity"))
                .isEqualTo(ModelRouteIdentity.class);
        assertThat(recordComponentType(RoutedModelResult.class, "route"))
                .isEqualTo(ModelRoute.class);
    }

    /** AFT-30（CT-45 静态面）：Gateway/RouteClient 实现及重试等待方法不得标注 @Transactional。 */
    @Test
    void gatewayAndRouteClientAreNotTransactional() {
        for (Class<?> type : new Class<?>[]{
                com.objwww.pr.control.application.ModelGateway.class,
                com.objwww.pr.control.infrastructure.model.SpringAiRouteClient.class}) {
            assertThat(type.isAnnotationPresent(
                    org.springframework.transaction.annotation.Transactional.class))
                    .as("%s 类级不得标 @Transactional", type.getSimpleName())
                    .isFalse();
            for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
                assertThat(m.isAnnotationPresent(
                        org.springframework.transaction.annotation.Transactional.class))
                        .as("%s.%s 不得标 @Transactional", type.getSimpleName(), m.getName())
                        .isFalse();
            }
        }
    }

    /** AFT-31（§4.4）：ModelGatewayPort.complete 签名必须携带调用上下文（deadline/取消/租约活性）。 */
    @Test
    void gatewayPortCompleteCarriesCallContext() {
        assertThat(ModelGatewayPort.class.getDeclaredMethods()).hasSize(1);
        assertThat(ModelGatewayPort.class.getDeclaredMethods()[0].getParameterTypes())
                .contains(ModelCallContext.class);
    }

    private static Class<?> recordComponentType(Class<?> recordType, String componentName) {
        return java.util.Arrays.stream(recordType.getRecordComponents())
                .filter(c -> c.getName().equals(componentName))
                .map(java.lang.reflect.RecordComponent::getType)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        recordType.getSimpleName() + " 缺少 record 组件 " + componentName));
    }
}
