package com.objwww.pr.shared;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * shared-kernel 零框架依赖卡点（AFT-01 的 kernel 侧）：两个应用的 domain 层只允许依赖它，
 * 它自己只能依赖 JDK。普通 @Test + ClassFileImporter（本环境 @ArchTest 不生效）。
 *
 * <p>例外（ADR-M4-001）：允许 commons-compress（SafeTarExtractor 专用，纯算法库，零副作用）。
 * 禁止其他 Apache 框架（日志/HTTP/消息/Web）保持 shared-kernel 纯净性。
 */
class SharedKernelArchitectureTest {

    @Test
    void kernelHasZeroFrameworkDependency() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.objwww.pr.shared");
        noClasses().that().resideInAPackage("..shared..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "java.sql..",
                        "com.fasterxml.jackson.."
                )
                .orShould(new ArchCondition<>("depend on org.apache.. except commons-compress") {
                    @Override
                    public void check(com.tngtech.archunit.core.domain.JavaClass javaClass, ConditionEvents events) {
                        javaClass.getDirectDependenciesFromSelf().forEach(dep -> {
                            String targetName = dep.getTargetClass().getName();
                            if (targetName.startsWith("org.apache.")
                                && !targetName.startsWith("org.apache.commons.compress.")) {
                                String message = String.format(
                                    "Class <%s> depends on <%s> (org.apache.. except commons-compress is forbidden)",
                                    javaClass.getName(), targetName);
                                events.add(SimpleConditionEvent.violated(javaClass, message));
                            }
                        });
                    }
                })
                .check(classes);
    }
}
