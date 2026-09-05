package com.objwww.pr.arena;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Arena 静态架构卡点（L0，M2-07 验收）：domain 零框架依赖——
 * Spring/Jakarta/JDBC/HTTP/JUnit 一律禁入（jackson 纯 JSON 库与 shared-kernel 允许）；
 * 故障注入判定不在 domain 出现（chaos 只活在 application/chaos，INV-AM2-5 的静态面）。
 * 普通 @Test + ClassFileImporter（本环境 @ArchTest 字段发现不生效，沿 control-app 惯例）。
 */
class ArenaArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.objwww.pr.arena");
    }

    @Test
    void domainHasNoFrameworkDependency() {
        noClasses().that().resideInAPackage("..arena.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",   // 框架
                        "jakarta..",               // 注解/Servlet
                        "java.sql..",              // JDBC
                        "java.net.http..",         // HTTP 客户端
                        "org.apache.http..",
                        "org.junit..")             // 测试框架
                .check(classes);
    }

    @Test
    void domainModelHasNoChaosDependency() {
        noClasses().that().resideInAPackage("..arena.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..arena.application.chaos..")
                .check(classes);
    }

    @Test
    void domainDoesNotDependOnApplicationOrInterfaces() {
        noClasses().that().resideInAPackage("..arena.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..arena.application..", "..arena.interfaces..", "..arena.infrastructure..")
                .check(classes);
    }
}
