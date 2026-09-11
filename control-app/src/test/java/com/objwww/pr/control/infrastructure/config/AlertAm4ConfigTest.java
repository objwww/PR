package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.alert.application.agent.ChangeAgent;
import com.objwww.pr.control.alert.application.agent.DirectReadToolCatalog;
import com.objwww.pr.control.alert.application.agent.LogsAgent;
import com.objwww.pr.control.alert.application.agent.MetricsAgent;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AM4 装配纪律行为化（AM4 技术方案 §2 + EN-05 §一）：EX-B2 后生产 registry **全真源**
 * ——三兼容工具 + EN-05 P0 九工具族全部真执行器，零 ReplayToolExecutor 挂点；
 * docker 双工具为条件注册件（base-url 与容器 allowlist 未配置不注册，fail-closed）。
 */
class AlertAm4ConfigTest {

    private static final long TIMEOUT = 4_000L;
    private static final long LIMIT = 65_536L;

    /** EX-B2 全真源换绑钉 + EN-05 P0 九工具族：执行器类型 + docker 未配置不注册 */
    @Test
    void productionRegistryBindsAllRealExecutors() {
        org.springframework.jdbc.core.simple.JdbcClient jdbc =
                org.springframework.jdbc.core.simple.JdbcClient.create(
                        new org.springframework.jdbc.datasource.SimpleDriverDataSource(
                                new org.postgresql.Driver(),
                                "jdbc:postgresql://127.0.0.1:1/unused", "u", "p"));

        ToolRegistry registry = new AlertAm4Config().am4ToolRegistry(
                "http://prometheus:9090", TIMEOUT, LIMIT, jdbc,
                "http://loki:3100", "control-app,checkout", "control-app",
                "control-app,checkout", "", "");

        assertThat(registry.find(MetricsAgent.TOOL_NAME, MetricsAgent.TOOL_VERSION)
                .orElseThrow().executor())
                .isInstanceOf(
                        com.objwww.pr.control.infrastructure.tool.PrometheusQueryExecutor.class);
        assertThat(registry.find(LogsAgent.TOOL_NAME, LogsAgent.TOOL_VERSION)
                .orElseThrow().executor())
                .isInstanceOf(
                        com.objwww.pr.control.infrastructure.tool.LogQueryExecutor.class);
        assertThat(registry.find(ChangeAgent.TOOL_NAME, ChangeAgent.TOOL_VERSION)
                .orElseThrow().executor())
                .isInstanceOf(
                        com.objwww.pr.control.infrastructure.tool.ChangeQueryExecutor.class);

        // EN-05：prom×4 经共享单执行器实例方法引用注册（注册表可见面 = lambda，
        // 类型断言不可达——钉注册存在性，绑定纪律由装配构造直证）
        assertThat(registry.find(DirectReadToolCatalog.TOOL_INSTANT,
                DirectReadToolCatalog.VERSION)).as("prometheus.instant 已注册").isPresent();
        assertThat(registry.find(DirectReadToolCatalog.TOOL_CATALOG,
                DirectReadToolCatalog.VERSION)).as("prometheus.catalog 已注册").isPresent();
        assertThat(registry.find(DirectReadToolCatalog.TOOL_LABEL_VALUES,
                DirectReadToolCatalog.VERSION)).as("prometheus.label_values 已注册").isPresent();
        assertThat(registry.find(DirectReadToolCatalog.TOOL_RULES,
                DirectReadToolCatalog.VERSION)).as("prometheus.rules 已注册").isPresent();
        assertThat(registry.find(DirectReadToolCatalog.TOOL_LOGS_AGGREGATE,
                DirectReadToolCatalog.VERSION).orElseThrow().executor())
                .isInstanceOf(
                        com.objwww.pr.control.infrastructure.tool.LokiAggregateExecutor.class);
        assertThat(registry.find(DirectReadToolCatalog.TOOL_CHANGE_DIFF,
                DirectReadToolCatalog.VERSION).orElseThrow().executor())
                .isInstanceOf(
                        com.objwww.pr.control.infrastructure.tool.ChangeDiffExecutor.class);
        assertThat(registry.find(DirectReadToolCatalog.TOOL_ALERT_HISTORY,
                DirectReadToolCatalog.VERSION).orElseThrow().executor())
                .isInstanceOf(
                        com.objwww.pr.control.infrastructure.tool.AlertHistoryExecutor.class);

        // docker 条件件：未配置不注册（fail-closed，调用即 UNKNOWN_TOOL）
        assertThat(registry.find(DirectReadToolCatalog.TOOL_DOCKER_PS,
                DirectReadToolCatalog.VERSION)).isEmpty();
        assertThat(registry.find(DirectReadToolCatalog.TOOL_DOCKER_INSPECT,
                DirectReadToolCatalog.VERSION)).isEmpty();
    }

    /** docker 条件注册正向钉：base-url 与容器 allowlist 均配置 → 双工具注册 */
    @Test
    void dockerToolsRegisterWhenConfigured() {
        org.springframework.jdbc.core.simple.JdbcClient jdbc =
                org.springframework.jdbc.core.simple.JdbcClient.create(
                        new org.springframework.jdbc.datasource.SimpleDriverDataSource(
                                new org.postgresql.Driver(),
                                "jdbc:postgresql://127.0.0.1:1/unused", "u", "p"));

        ToolRegistry registry = new AlertAm4Config().am4ToolRegistry(
                "http://prometheus:9090", TIMEOUT, LIMIT, jdbc,
                "http://loki:3100", "control-app", "control-app",
                "control-app", "http://docker-engine:2375", "control-app");

        assertThat(registry.find(DirectReadToolCatalog.TOOL_DOCKER_PS,
                DirectReadToolCatalog.VERSION)).as("docker.ps 已注册").isPresent();
        assertThat(registry.find(DirectReadToolCatalog.TOOL_DOCKER_INSPECT,
                DirectReadToolCatalog.VERSION)).as("docker.inspect 已注册").isPresent();
    }

    /** P1-03 面收官钉：生产镜像 main 资源零 am4 fixture 文件（logs 全删/change 迁 test） */
    @Test
    void mainResourcesCarryNoAm4Fixtures() {
        assertThat(java.nio.file.Path.of("src/main/resources/am4").toFile().exists())
                .as("main 资源零 am4 假件目录（logs 已删、change 已迁 test 资源）").isFalse();
    }
}
