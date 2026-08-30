package com.objwww.pr.control;

import com.objwww.pr.control.domain.review.ModelFinding;
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
