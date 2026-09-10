package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.alert.application.agent.ChangeAgent;
import com.objwww.pr.control.alert.application.agent.LogsAgent;
import com.objwww.pr.control.alert.application.agent.MetricsAgent;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AM4 装配纪律行为化（AM4 技术方案 §2）：EX-B2 后生产 registry **全真源**——
 * prometheus.query 真查 + logs.query 真 Loki（试验资源）+ change.query 真实变更源，
 * 零 ReplayToolExecutor 挂点（Phase 3 验收门第 1 条；fixtureBytes 已随之退役，
 * 生产镜像零 fixture——P1-03 面收官）。
 */
class AlertAm4ConfigTest {

    /** EX-B2 全真源换绑钉：三工具执行器类型 + 生产装配零 Replay 假件 */
    @Test
    void productionRegistryBindsAllRealExecutors() {
        org.springframework.jdbc.core.simple.JdbcClient jdbc =
                org.springframework.jdbc.core.simple.JdbcClient.create(
                        new org.springframework.jdbc.datasource.SimpleDriverDataSource(
                                new org.postgresql.Driver(),
                                "jdbc:postgresql://127.0.0.1:1/unused", "u", "p"));

        ToolRegistry registry = new AlertAm4Config().am4ToolRegistry(
                "http://prometheus:9090", 4_000L, 65_536L, jdbc,
                "http://loki:3100", "control-app,checkout", "control-app");

        assertThat(registry.find(LogsAgent.TOOL_NAME, LogsAgent.TOOL_VERSION).orElseThrow()
                .executor()).isInstanceOf(
                com.objwww.pr.control.infrastructure.tool.LogQueryExecutor.class);
        assertThat(registry.find(ChangeAgent.TOOL_NAME, ChangeAgent.TOOL_VERSION).orElseThrow()
                .executor()).isInstanceOf(
                com.objwww.pr.control.infrastructure.tool.ChangeQueryExecutor.class);
        assertThat(registry.find(MetricsAgent.TOOL_NAME, MetricsAgent.TOOL_VERSION).orElseThrow()
                .executor()).isInstanceOf(
                com.objwww.pr.control.infrastructure.tool.PrometheusQueryExecutor.class);
    }

    /** P1-03 面收官钉：生产镜像 main 资源零 am4 fixture 文件（logs 全删/change 迁 test） */
    @Test
    void mainResourcesCarryNoAm4Fixtures() {
        assertThat(java.nio.file.Path.of("src/main/resources/am4").toFile().exists())
                .as("main 资源零 am4 假件目录（logs 已删、change 已迁 test 资源）").isFalse();
    }
}
