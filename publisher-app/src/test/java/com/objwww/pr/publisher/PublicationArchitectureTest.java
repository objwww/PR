package com.objwww.pr.publisher;

import com.objwww.pr.publisher.domain.service.FencedPublicationExecutor;
import com.objwww.pr.publisher.infrastructure.config.PublisherWiringConfig;
import com.objwww.pr.publisher.infrastructure.github.GitHubWriteAdapter;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Publisher 侧静态架构卡点（AFT-01/04，§12 L0；AFT-07 的结构部分）：
 * Handler 包对 infrastructure 适配器包零依赖；domain 零 Spring/JDBC/HTTP/SDK/Jackson；
 * GitHubWriteAdapter 仅被 FencedPublicationExecutor 与 PublisherWiringConfig 装配点引用；
 * TypedWrite/TypedReadRequest 无 raw url/method 字段。
 *
 * <p>用普通 @Test + ClassFileImporter 而非 @ArchTest 字段：本仓库 surefire 环境下
 * ArchUnit 引擎的规则字段发现不生效（实测 tests=0），方法式断言保证 CI 必然执行。
 */
class PublicationArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        // 只导入生产类：测试 stub（StubGitHubWriteAdapter 等）是合法测试接缝，不入结构断言
        classes = new ClassFileImporter()
                .withImportOption(com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.objwww.pr.publisher");
    }

    @Test
    void handlerPackageHasZeroInfrastructureDependency() {
        noClasses().that().resideInAPackage("..publisher.domain.handler..")
                .should().dependOnClassesThat().resideInAPackage("..publisher.infrastructure..")
                .check(classes);
    }

    @Test
    void domainHasNoSpringOrHttpDependency() {
        // I1（AFT-01）：domain 只允许依赖 shared-kernel 与 JDK。
        // 黑名单：Spring（含 org.springframework.ai 模型 SDK）/JDBC/HTTP 客户端/GitHub SDK/Jackson。
        noClasses().that().resideInAPackage("..publisher.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "java.sql..",
                        "java.net.http..", "org.apache.http..",
                        "com.github..", "org.kohsuke.github..",
                        "com.fasterxml.jackson..")
                .check(classes);
    }

    @Test
    void onlyExecutorReferencesGitHubWriteAdapter() {
        // I4（AFT-04/AFT-07；AFT-14 的"GitHubWriteAdapter 唯一写出口回归"同由本规则兜底）：
        // 白名单精确到类——仅执行器本体与 config 装配点可引用写适配器；
        // 不再整体豁免 infrastructure 包（那里出现新引用点同样要被抓）。
        // 适配器自身的合成内部类（GitHubWriteAdapter$1/$2，switch 表等）属实现细节，豁免。
        noClasses().that().resideInAPackage("..publisher..")
                .and().doNotHaveFullyQualifiedName(FencedPublicationExecutor.class.getName())
                .and().doNotHaveFullyQualifiedName(PublisherWiringConfig.class.getName())
                .and(new DescribedPredicate<>("不是 GitHubWriteAdapter 自身的合成内部类") {
                    @Override
                    public boolean test(JavaClass javaClass) {
                        return !javaClass.getName().startsWith(GitHubWriteAdapter.class.getName() + "$");
                    }
                })
                .should().dependOnClassesThat()
                .haveFullyQualifiedName(GitHubWriteAdapter.class.getName())
                .check(classes);
    }

    /**
     * AFT-13（M1 方案 L0）：DriftReconciler 只经 PublicationHandler 探针触网——
     * 不引用 GitHubWriteAdapter（I4 已有全库规则兜底，此处显式钉死新 worker）、
     * 不引用写请求类型（TypedWriteRequest = 写路径），不引用 control 侧 Outbox 插入路径。
     */
    @Test
    void driftReconcilerTouchesNetworkOnlyViaHandlerProbes() {
        noClasses().that().haveFullyQualifiedName(
                        "com.objwww.pr.publisher.application.DriftReconciler")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName(GitHubWriteAdapter.class.getName())
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("com.objwww.pr.shared.TypedWriteRequest")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("com.objwww.pr.control.application.OutboxWriter")
                .check(classes);
    }

    /** AFT-18：checkpoint 是 control 私产，publisher 生产代码不得引用其模型/服务/仓储。 */
    @Test
    void publisherHasNoCheckpointDependency() {
        noClasses().that().resideInAPackage("..publisher..")
                .should().dependOnClassesThat().haveSimpleNameContaining("Checkpoint")
                .check(classes);
    }

    /**
     * AFT-14（M2 方案 §11/L0，I20）：DriftReconciler 静态依赖面钉死——只能 INSERT
     * repair_request：① 对 PublicationStore 的方法调用收敛于巡检读/观测列回写/repair 登记
     * 白名单（outbox_command 的写方法一个都不许碰）；② 零 control 包依赖（outbox 插入路径
     * 是 control 私产）；③ 不调 Handler 写侧方法（buildRequest/interpret 是 execute 独占）；
     * ④ 对执行器只调 reconcile/sanityRead 探针，不调写入口 execute。
     * "只能 INSERT repair_request" 的运行时语义由 CT-24/CT-29 真库钉，此条只钉静态依赖面。
     */
    @Test
    void driftReconcilerOnlyInsertsRepairRequestNeverWritesOutbox() {
        JavaClass reconciler = classes.get("com.objwww.pr.publisher.application.DriftReconciler");

        // ① PublicationStore 调用白名单
        java.util.Set<String> allowedStoreMethods = java.util.Set.of(
                "findDueForDriftCheck", "markCheckedPresent", "markContentDrift", "clearContentDrift",
                "markMissing", "markMissingWithRepair", "markUnknown", "markCheckError");
        List<String> storeCalls = reconciler.getMethodCallsFromSelf().stream()
                .filter(call -> call.getTargetOwner().isAssignableTo(
                        "com.objwww.pr.publisher.domain.port.PublicationStore"))
                .map(call -> call.getTarget().getName())
                .distinct()
                .toList();
        org.assertj.core.api.Assertions.assertThat(storeCalls)
                .as("DriftReconciler 对 PublicationStore 的调用必须收敛于巡检/repair 登记白名单")
                .isNotEmpty()
                .allMatch(allowedStoreMethods::contains);

        // ② 零 control 包依赖（outbox 写 port 在 control 侧）
        noClasses().that().haveFullyQualifiedName(
                        "com.objwww.pr.publisher.application.DriftReconciler")
                .should().dependOnClassesThat().resideInAPackage("..control..")
                .check(classes);

        // ③ 不调 Handler 写侧方法（commandType 是构造期建 Map 键的元数据读，合法）
        List<String> handlerCalls = reconciler.getMethodCallsFromSelf().stream()
                .filter(call -> call.getTargetOwner().isAssignableTo(
                        "com.objwww.pr.publisher.domain.handler.PublicationHandler"))
                .map(call -> call.getTarget().getName())
                .distinct()
                .toList();
        org.assertj.core.api.Assertions.assertThat(handlerCalls)
                .as("DriftReconciler 只允许调 Handler 探针/期望 digest/元数据方法，实际调用: %s", handlerCalls)
                .isNotEmpty()
                .allMatch(name -> java.util.Set.of(
                        "expectedContentDigest", "buildSanityProbe", "commandType").contains(name));

        // ④ 执行器只调探针，不调写入口
        List<String> executorCalls = reconciler.getMethodCallsFromSelf().stream()
                .filter(call -> call.getTargetOwner().isAssignableTo(
                        "com.objwww.pr.publisher.domain.service.FencedPublicationExecutor"))
                .map(call -> call.getTarget().getName())
                .distinct()
                .toList();
        org.assertj.core.api.Assertions.assertThat(executorCalls)
                .as("DriftReconciler 对执行器只许调 reconcile/sanityRead，实际调用: %s", executorCalls)
                .isNotEmpty()
                .allMatch(name -> java.util.Set.of("reconcile", "sanityRead").contains(name));
    }

    /** AFT-04 契约：类型化请求无 raw url/method 字段（HTTP 拼装只存在于 GitHubWriteAdapter 内） */
    @Test
    void typedRequestsHaveNoRawUrlOrMethodFields() {        for (Class<?> type : List.of(
                com.objwww.pr.shared.TypedWriteRequest.class,
                com.objwww.pr.shared.TypedReadRequest.class)) {
            for (java.lang.reflect.RecordComponent component : type.getRecordComponents()) {
                String name = component.getName().toLowerCase(java.util.Locale.ROOT);
                org.assertj.core.api.Assertions.assertThat(name)
                        .as("%s 不得含原始寻址/动词字段: %s", type.getSimpleName(), component.getName())
                        .isNotIn("url", "uri", "method", "path", "endpoint");
                // String 类型组件仅允许 repositoryFullName（仓库标识 ≠ raw url）
                if (component.getType().equals(String.class)) {
                    org.assertj.core.api.Assertions.assertThat(component.getName())
                            .isEqualTo("repositoryFullName");
                }
            }
        }
    }

    /**
     * AFT-22（M3 方案 §1 不做项）：publisher 全模块源码零引用模型治理类——
     * Gateway/路由/熔断/预算/定价/账本一律不得出现在 publisher 依赖中。
     * 动态兜底：publisher 对账本表的零权限由 DP-20/DP-24 栈级断言。
     */
    @Test
    void publisherHasNoModelGovernanceDependency() {
        noClasses().that().resideInAPackage("..publisher..")
                .should().dependOnClassesThat().resideInAPackage("..control.domain.ai..")
                .orShould().dependOnClassesThat().haveSimpleNameContaining("ModelGateway")
                .orShould().dependOnClassesThat().haveSimpleNameContaining("ModelRoute")
                .orShould().dependOnClassesThat().haveSimpleNameContaining("CircuitBreaker")
                .orShould().dependOnClassesThat().haveSimpleNameContaining("ModelStepBudgetGuard")
                .orShould().dependOnClassesThat().haveSimpleNameContaining("PricingService")
                .orShould().dependOnClassesThat().haveSimpleNameContaining("ModelCallLedger")
                .check(classes);
    }

    /**
     * AFT-32（M3 方案 §1 不做项，与 AFT-22 互补）：依赖图向钉死——publisher 不得依赖
     * Gateway / 路由目录 / 定价 / 账本仓储任何其一（AFT-22 扫类名引用面，本条按全限定名
     * 钉关键类型本身，防改名绕过）。
     */
    @Test
    void publisherDoesNotDependOnGatewayRoutePricingOrLedger() {
        noClasses().that().resideInAPackage("..publisher..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "com.objwww.pr.control.application.ModelGateway")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                        "com.objwww.pr.control.domain.ai.ModelCallLedgerRepository")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                        "com.objwww.pr.control.domain.ai.PricingService")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                        "com.objwww.pr.control.domain.ai.ModelRouteCatalog")
                .check(classes);
    }
}
