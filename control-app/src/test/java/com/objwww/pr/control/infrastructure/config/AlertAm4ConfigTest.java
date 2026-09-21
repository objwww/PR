package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.alert.application.agent.ChangeAgent;
import com.objwww.pr.control.alert.application.agent.DirectReadToolCatalog;
import com.objwww.pr.control.alert.application.agent.LogsAgent;
import com.objwww.pr.control.alert.application.agent.MetricsAgent;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AM4 装配纪律行为化（AM4 技术方案 §2 + EN-05 §一 + EN-07 §三阶段 1）：EX-B2 后生产
 * registry **全真源**——三兼容工具 + EN-05 P0 九工具族全部真执行器，零
 * ReplayToolExecutor 挂点；docker 双工具与 EN-07 runbook 双工具为条件注册件
 * （未配置不注册，fail-closed）；history_rca_search 无语料依赖恒注册。
 */
class AlertAm4ConfigTest {

    private static final long TIMEOUT = 4_000L;
    private static final long LIMIT = 65_536L;
    private static final String CORPUS_DIGEST = "ab".repeat(32);

    /** 装配面语料库桩（registry 构造不触资产；行为面归 rag 包各测试） */
    private static com.objwww.pr.control.alert.application.rag.RunbookCorpusStore store() {
        return new com.objwww.pr.control.alert.application.rag.RunbookCorpusStore(
                new com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository() {
                    @Override
                    public boolean insert(
                            com.objwww.pr.control.release.domain.model.ReleaseAsset asset) {
                        return true;
                    }

                    @Override
                    public java.util.Optional<com.objwww.pr.control.release.domain.model.ReleaseAsset>
                            findByDigest(String kind, com.objwww.pr.shared.Digest digest) {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public java.util.List<com.objwww.pr.control.release.domain.model.ReleaseAsset>
                            listRecent(String kind, int limit) {
                        return java.util.List.of();
                    }
                });
    }

    /** EX-B2 全真源换绑钉 + EN-05 P0 九工具族：执行器类型 + docker/RAG 未配置不注册 */
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
                "control-app,checkout", "", "",
                store(), "", "control-app", "", "");

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

        // EN-07：runbook 双工具——语料目录 digest 未配置不注册（fail-closed 同 docker）；
        // history_rca_search 无语料依赖，恒注册
        assertThat(registry.find(DirectReadToolCatalog.TOOL_RUNBOOK_CATALOG,
                DirectReadToolCatalog.VERSION)).isEmpty();
        assertThat(registry.find(DirectReadToolCatalog.TOOL_RUNBOOK_FETCH,
                DirectReadToolCatalog.VERSION)).isEmpty();
        assertThat(registry.find(DirectReadToolCatalog.TOOL_RCA_HISTORY,
                DirectReadToolCatalog.VERSION).orElseThrow().executor())
                .isInstanceOf(
                        com.objwww.pr.control.infrastructure.rag.HistoryRcaSearchExecutor.class);

        // R7-X10 代码取证：未配置不注册（fail-closed，docker 同律）
        assertThat(registry.find(DirectReadToolCatalog.TOOL_CODE_SEARCH,
                DirectReadToolCatalog.VERSION)).isEmpty();
        assertThat(registry.find(DirectReadToolCatalog.TOOL_CODE_READ,
                DirectReadToolCatalog.VERSION)).isEmpty();

        // BA-171：两 R3 写类审批工具无条件注册——显式 R3、schema 必填 service、
        // 占位执行器触达即炸（VALIDATE_ONLY 短路之外触达 = 装配缺陷，永不触网）
        var restart = registry.find(
                com.objwww.pr.control.alert.application.tool.MutationToolCatalog
                        .TOOL_SERVICE_RESTART,
                com.objwww.pr.control.alert.application.tool.MutationToolCatalog.VERSION)
                .orElseThrow();
        assertThat(restart.definition().risk())
                .isEqualTo(com.objwww.pr.control.alert.domain.tool.ToolRisk.R3);
        assertThat(restart.definition().schema().get("required"))
                .isEqualTo(java.util.List.of("service"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> restart.executor()
                        .execute(new com.objwww.pr.control.alert.application.tool.ToolExecutor
                                .ToolExecution(java.util.Map.of("service", "checkout"),
                                        0L, 1024L)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(registry.find(
                com.objwww.pr.control.alert.application.tool.MutationToolCatalog
                        .TOOL_SERVICE_ROLLBACK,
                com.objwww.pr.control.alert.application.tool.MutationToolCatalog.VERSION)
                .orElseThrow().definition().risk())
                .isEqualTo(com.objwww.pr.control.alert.domain.tool.ToolRisk.R3);
    }

    /** docker + EN-07 条件注册正向钉：配置齐 → 双工具注册（runbook 双工具挂真语料执行器） */
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
                "control-app", "http://docker-engine:2375", "control-app",
                store(), CORPUS_DIGEST, "control-app",
                java.nio.file.Path.of("src/test/resources").toAbsolutePath().toString(),
                "checkout=control-app@probe");

        assertThat(registry.find(DirectReadToolCatalog.TOOL_DOCKER_PS,
                DirectReadToolCatalog.VERSION)).as("docker.ps 已注册").isPresent();
        assertThat(registry.find(DirectReadToolCatalog.TOOL_DOCKER_INSPECT,
                DirectReadToolCatalog.VERSION)).as("docker.inspect 已注册").isPresent();
        assertThat(registry.find(DirectReadToolCatalog.TOOL_RUNBOOK_CATALOG,
                DirectReadToolCatalog.VERSION).orElseThrow().executor())
                .as("runbook.catalog 已注册")
                .isInstanceOf(
                        com.objwww.pr.control.infrastructure.rag.RunbookCatalogSearchExecutor.class);
        assertThat(registry.find(DirectReadToolCatalog.TOOL_RUNBOOK_FETCH,
                DirectReadToolCatalog.VERSION).orElseThrow().executor())
                .as("runbook.fetch 已注册")
                .isInstanceOf(
                        com.objwww.pr.control.infrastructure.rag.FetchRunbookExecutor.class);

        // R7-X10 条件件正向钉：checkout 根 + 映射配置齐 → code 双工具注册
        assertThat(registry.find(DirectReadToolCatalog.TOOL_CODE_SEARCH,
                DirectReadToolCatalog.VERSION)).as("code.search 已注册").isPresent();
        assertThat(registry.find(DirectReadToolCatalog.TOOL_CODE_READ,
                DirectReadToolCatalog.VERSION)).as("code.read 已注册").isPresent();
    }

    /** P1-03 面收官钉：生产镜像 main 资源零 am4 fixture 文件（logs 全删/change 迁 test） */
    @Test
    void mainResourcesCarryNoAm4Fixtures() {
        assertThat(java.nio.file.Path.of("src/main/resources/am4").toFile().exists())
                .as("main 资源零 am4 假件目录（logs 已删、change 已迁 test 资源）").isFalse();
    }

    /** BA-184/BA-185 钉版：缺省 prompt 升 am4-native-v11——v10 静默故障双源口径
     *  （alert_event）+ 零数据不原地重试指引保留；v11 写类工具段补"处置落地形态"
     *  （文字建议不进审批链；变更回归正确处置=调用 service.rollback）；
     *  协议键一字未动，只钉版本键与新增表述 */
    @Test
    void defaultPromptCarriesV10SilentFaultClause() throws Exception {
        java.lang.reflect.Field versionKey =
                AlertAm4Config.class.getDeclaredField("PROMPT_VERSION_KEY");
        versionKey.setAccessible(true);
        assertThat((String) versionKey.get(null))
                .as("prompt 版本键缺省值升 v11").contains("am4-native-v11");

        java.lang.reflect.Field prompt =
                AlertAm4Config.class.getDeclaredField("R7_PRIMARY_DEFAULT_PROMPT");
        prompt.setAccessible(true);
        String text = (String) prompt.get(null);
        assertThat(text).as("收敛标准补静默故障双源口径（v10 保留）").contains("静默故障口径");
        assertThat(text).as("静默口径点名 alert_event 第二源（v10 保留）").contains("alert_event");
        assertThat(text).as("调查路径补零数据不原地重试指引（v10 保留）")
                .contains("日志零数据时不要原地重试同参查询");
        assertThat(text).as("v11 处置落地形态：文字建议不进审批链")
                .contains("不会进入审批链，等于没有处置");
        assertThat(text).as("v11 变更回归正确处置=调用 service.rollback")
                .contains("正确处置=调用 service.rollback 回滚该发布");
    }
}
