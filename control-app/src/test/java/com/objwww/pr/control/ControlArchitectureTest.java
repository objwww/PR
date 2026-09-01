package com.objwww.pr.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.application.CheckpointWriter;
import com.objwww.pr.control.application.IntakeCommand;
import com.objwww.pr.control.application.ReviewStepExecutor;
import com.objwww.pr.control.application.StepExecutionContext;
import com.objwww.pr.control.domain.ai.MockModelClient;
import com.objwww.pr.control.domain.ai.ModelBudgetGuard;
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
import com.objwww.pr.control.domain.snapshot.SafeTarExtractor;
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
        MockModelClient modelClient = new MockModelClient();
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
                fx.artifacts, new SafeTarExtractor(),
                new ReviewAgentLoop(modelClient, new ModelBudgetGuard(), new FindingMapper(),
                        new PolicyEngine(new ToolRegistry())),
                ReviewBudget.DEFAULT, mapper,
                new CheckpointResumeService(fx.checkpoints, fx.artifacts, fx.cas, fx.ledger, mapper),
                new CheckpointWriter(fx.artifacts, fx.checkpoints, fx.ledger),
                fx.ledger, "aft17/model/v1");
        fx.cas.putIfAbsent(snapshotDigest, TestTarballs.tarGz(out ->
                TestTarballs.file(out, TestTarballs.GH_PREFIX + "a/Foo.java", "int x = 0/1;\n")));
        fx.cas.putIfAbsent(diffDigest, "diff".getBytes(StandardCharsets.UTF_8));
        modelClient.enqueueContent("[]");

        executor.execute(new StepExecutionContext(item, step), () -> true);

        StepCheckpoint checkpoint = fx.checkpoints.all().stream()
                .filter(c -> c.checkpointKey().equals(StepCheckpoint.REVIEW_OUTCOME))
                .findFirst().orElseThrow();
        CheckpointContract expected = new CheckpointContract(
                run.getPromptVersion() + "/" + ReviewAgentLoop.PROMPT_TEMPLATE_VERSION,
                ReviewContractVersions.FINDING_SCHEMA_VERSION,
                FindingMapper.CONTRACT_VERSION,
                ReviewAgentLoop.CONTEXT_BUILDER_VERSION,
                "aft17/model/v1");
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
}
