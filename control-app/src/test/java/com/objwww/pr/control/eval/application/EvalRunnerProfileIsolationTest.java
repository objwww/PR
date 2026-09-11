package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.GoldenScenarioRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-15 装配隔离：eval-runner 配置只在 {@code eval} profile 激活——生产默认装配
 * （docker profile）上下文中 eval bean 全部缺席；eval profile 下全 bean 离线可装配
 * （RestClient 不外呼、数据源不建连、注册表/词典走 classpath 测试资源）。
 * ApplicationContextRunner 不触发 ApplicationRunner，跑批入口 bean 在场但不执行。
 */
class EvalRunnerProfileIsolationTest {

    private static final String HEX_A = "a".repeat(64);
    private static final String HEX_B = "b".repeat(64);
    private static final String HEX_C = "c".repeat(64);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EvalRunnerConfig.class);

    /** 桩数据源：只供 JdbcClient 构造（eval-runner 装配期不建连） */
    private static DataSource stubDataSource() {
        return new SimpleDriverDataSource(new org.postgresql.Driver(),
                "jdbc:postgresql://stub:5432/stub");
    }

    @Test
    @DisplayName("无 eval profile：EvalRunnerConfig 整体不装载，生产上下文无 eval bean")
    void evalBeansAbsentWithoutProfile() {
        contextRunner.withBean(DataSource.class, EvalRunnerProfileIsolationTest::stubDataSource)
                .run(context -> {
                    assertThat(context).doesNotHaveBean("evalBatchRunner");
                    assertThat(context).doesNotHaveBean("evalRunnerMain");
                    assertThat(context).doesNotHaveBean(EvalBatchRunner.class);
                    assertThat(context).doesNotHaveBean(GoldenScenarioRegistry.class);
                    assertThat(context).doesNotHaveBean(EvalRunMetadata.class);
                });
    }

    @Test
    @DisplayName("eval profile：全 bean 离线装配成功，元数据默认值与注册表内容生效")
    void evalProfileAssemblesFullRunnerStack() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=eval",
                        "app.alert.eval.prompt-digest=" + HEX_A,
                        "app.alert.eval.prompt-digest=" + HEX_A,
                        "app.alert.eval.tool-registry-digest=" + HEX_B,
                        "app.alert.eval.provider-fingerprint=fp-test",
                        "app.alert.eval.alert-rule-digest=" + HEX_C,
                        "app.alert.eval.grader-version=grader-test-v1")
                .withBean(DataSource.class, EvalRunnerProfileIsolationTest::stubDataSource)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("evalJdbcClient");
                    assertThat(context).hasBean("goldenScenarioRegistry");
                    assertThat(context).hasBean("synonymLexicon");
                    assertThat(context).hasBean("singleCaseScorer");
                    assertThat(context).hasBean("alertProbe");
                    assertThat(context).hasBean("rcaRunResolver");
                    assertThat(context).hasBean("flagdScenarioDriver");
                    assertThat(context).hasBean("arenaChaosScenarioDriver");
                    assertThat(context).hasBean("infrastructureScenarioDriver");
                    assertThat(context).hasBean("baselineReportGenerator");
                    assertThat(context).hasBean("evalBatchRunner");
                    assertThat(context).hasBean("evalRunnerMain");
                    assertThat(context).hasBean("evalRunMetadata");

                    GoldenScenarioRegistry registry =
                            context.getBean(GoldenScenarioRegistry.class);
                    assertThat(registry.scenarios()).hasSize(2);

                    EvalRunMetadata metadata = context.getBean(EvalRunMetadata.class);
                    assertThat(metadata.model()).isEqualTo("glm-5");
                    assertThat(metadata.registryDigest().value())
                            .isEqualTo(registry.contentDigest().value());
                    assertThat(metadata.graderVersion()).isEqualTo("grader-test-v1");
                });
    }

    @Test
    @DisplayName("EN-09 fail-closed：eval profile 缺 grader-version → 装配拒绝（无评分器版本不批跑）")
    void evalProfileRejectsMissingGraderVersion() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=eval",
                        "app.alert.eval.prompt-digest=" + HEX_A,
                        "app.alert.eval.tool-registry-digest=" + HEX_B,
                        "app.alert.eval.provider-fingerprint=fp-test",
                        "app.alert.eval.alert-rule-digest=" + HEX_C)
                .withBean(DataSource.class, EvalRunnerProfileIsolationTest::stubDataSource)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("grader-version 必填");
                });
    }
}
