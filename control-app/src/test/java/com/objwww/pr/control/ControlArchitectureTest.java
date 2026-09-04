package com.objwww.pr.control;

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
 * Control 侧静态架构卡点（L0）。
 * 普通 @Test + ClassFileImporter（本环境 @ArchTest 字段发现不生效，沿用既有惯例）。
 *
 * <p>AM1-T00 清障后：PR 域规则（checkpoint 契约/review 解析/publisher 隔离等）随死代码删除，
 * 保留 M3 模型治理的 L0 规则；AM1 告警域新规则（AFT-A01~A05）由 T09 增补。
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

    /**
     * AFT-A01（AM1 §3.1/T02 验收）：告警域 domain 零框架依赖——Spring/Jakarta/JDBC/HTTP/JUnit
     * 一律禁入；jackson（纯 JSON 库）与 shared-kernel 通用件允许。
     */
    @Test
    void alertDomainHasNoFrameworkDependency() {
        noClasses().that().resideInAPackage("..control.alert.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta..",
                        "java.sql..", "org.apache.http..", "java.net.http..",
                        "org.junit..")
                .check(classes);
    }

    /** AFT-20（M3；§4.2）：故障分类与路由决策是封闭类型；Router/Gateway 决策 switch 无 default 兜底。 */
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

    /** AFT-21 重写（M3 I32）：预算门控类不得依赖成本类型；反射断言字段与方法签名无成本/价格类型。 */
    @Test
    void budgetAndGateClassesDoNotDependOnCostTypes() {
        noClasses().that().haveSimpleName("ModelStepBudgetGuard")
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

    /** AFT-24（M3 §3.1）：domain/ai 零 Spring AI、HTTP client、JDBC、Spring Retry 依赖。 */
    @Test
    void domainAiHasNoFrameworkHttpJdbcOrRetryDependency() {
        noClasses().that().resideInAPackage("..control.domain.ai..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "org.springframework.retry..",
                        "java.net.http..", "org.apache.http..", "java.sql..")
                .check(classes);
    }

    /**
     * AFT-25（M3 §3.1）：调用上下文显式传递——存活模块 src/main 全库禁止
     * ThreadLocal/InheritableThreadLocal 使用形态（ThreadLocalRandom 是退避抖动随机源，
     * 不传递上下文，豁免）。surefire 工作目录 = 模块 basedir。
     */
    @Test
    void noThreadLocalContextPassingAnywhere() throws Exception {
        for (String module : new String[]{"src/main/java", "../shared-kernel/src/main/java"}) {
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
     * AFT-26（M3 I29）：账本仓储写方法封闭集 + 无旁路 SQL——ModelCallLedgerRepository 的方法集
     * 精确等于 {insertStarted, completeTerminalSuccess, completeTerminalFailure,
     * markUnknownOlderThan}（无通用 update/delete）；control src/main 中表名
     * model_call_ledger 只允许出现在 PostgresModelCallLedgerRepository（唯一 SQL 落点）与
     * ControlSelfCheck（DB 权限探针按表名断言，非 SQL）。
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
                if (p.endsWith("PostgresModelCallLedgerRepository.java")
                        || p.endsWith("ControlSelfCheck.java")) {
                    continue;
                }
                assertThat(java.nio.file.Files.readString(p))
                        .as("%s 不得出现 model_call_ledger 旁路 SQL", p)
                        .doesNotContain("model_call_ledger");
            }
        }
    }

    /**
     * AFT-28（M3 §4.11）：路由身份/故障/账本/上下文类型的字段集不得包含密钥材料字段。
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

    /** AFT-30（M3 CT-45 静态面）：Gateway/RouteClient 实现及重试等待方法不得标注 @Transactional。 */
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

    /** AFT-31（M3 §4.4）：ModelGatewayPort.complete 签名必须携带调用上下文（deadline/取消/租约活性）。 */
    @Test
    void gatewayPortCompleteCarriesCallContext() {
        assertThat(ModelGatewayPort.class.getDeclaredMethods()).hasSize(1);
        assertThat(ModelGatewayPort.class.getDeclaredMethods()[0].getParameterTypes())
                .contains(ModelCallContext.class);
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
