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
        // I4（AFT-04/AFT-07）：白名单精确到类——仅执行器本体与 config 装配点可引用写适配器；
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

    /** AFT-04 契约：类型化请求无 raw url/method 字段（HTTP 拼装只存在于 GitHubWriteAdapter 内） */
    @Test
    void typedRequestsHaveNoRawUrlOrMethodFields() {
        for (Class<?> type : List.of(
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
}
