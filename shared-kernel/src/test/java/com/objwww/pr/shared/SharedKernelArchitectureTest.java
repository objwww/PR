package com.objwww.pr.shared;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * shared-kernel 零框架依赖卡点（AFT-01 的 kernel 侧）：两个应用的 domain 层只允许依赖它，
 * 它自己只能依赖 JDK。普通 @Test + ClassFileImporter（本环境 @ArchTest 不生效）。
 */
class SharedKernelArchitectureTest {

    @Test
    void kernelHasZeroFrameworkDependency() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.objwww.pr.shared");
        noClasses().that().resideInAPackage("..shared..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "java.sql..",
                        "com.fasterxml.jackson..", "org.apache..")
                .check(classes);
    }
}
