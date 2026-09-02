package com.objwww.pr.broker;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * AFT-33: Broker 零 control-app 依赖卡点（D1/D17 硬门）。
 *
 * <p>Broker 是独立进程，只能依赖 shared-kernel，禁止依赖：
 * <ul>
 *   <li>control-app（双向依赖会破坏进程隔离）</li>
 *   <li>publisher-app（关注点分离）</li>
 *   <li>任何数据库驱动（R1：Broker 零 DB 通道）</li>
 * </ul>
 */
class BrokerArchitectureTest {

    @Test
    void brokerHasZeroControlAppDependency() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.objwww.pr.broker");
        noClasses().that().resideInAPackage("..broker..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.objwww.pr.control..",
                        "com.objwww.pr.publisher.."
                )
                .check(classes);
    }

    @Test
    void brokerHasZeroDatabaseDependency() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.objwww.pr.broker");
        noClasses().that().resideInAPackage("..broker..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.sql..",
                        "org.postgresql..",
                        "org.springframework.jdbc..",
                        "org.springframework.data.."
                )
                .check(classes);
    }

    @Test
    void brokerServiceLayerDoesNotDependOnInfrastructure() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.objwww.pr.broker");
        noClasses().that().resideInAPackage("..broker.service..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..broker.infrastructure.."
                )
                .check(classes);
    }
}
