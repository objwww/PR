package com.objwww.pr.control.infrastructure.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolExecutor;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EN-05（§一 change/部署面 P0：docker_ps/docker_inspect）只读边界钉（T09 E 脸）：
 * 不挂 Docker 原始 socket/通用 shell——{@link DockerInspectExecutor} 经受限传输面
 * （{@link DockerEngineTransport}，只允许 GET 白名单 API 路径）采集；env <b>只回键名
 * 永不回值</b>（§一只读边界"env 默认不返回值"）；容器目标以<b>名称</b>表达且在
 * 发出前过 allowlist（越权目标零请求，T03/T09 同律）；ps 渲染只留 allowlist 容器。
 */
class DockerInspectExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 假传输：记录 GET 路径，按路径返回桩响应 */
    private static final class FakeTransport implements DockerEngineTransport {
        final List<String> paths = new ArrayList<>();
        private final Map<String, String> stubs;

        FakeTransport(Map<String, String> stubs) {
            this.stubs = stubs;
        }

        @Override
        public byte[] get(String path) {
            paths.add(path);
            String body = stubs.get(path);
            if (body == null) {
                throw new IllegalStateException("意外路径: " + path);
            }
            return body.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final String PS_BODY = """
            [{"Id":"abc123","Names":["/control-app"],"Image":"control:1.2.3",
              "State":"running","Status":"Up 2 hours"},
             {"Id":"def456","Names":["/secret-sidecar"],"Image":"sidecar:9",
              "State":"running","Status":"Up 2 hours"}]
            """;

    private static final String INSPECT_BODY = """
            {"Id":"abc123full","Name":"/control-app",
             "Config":{"Image":"control:1.2.3",
                       "Env":["SECRET_TOKEN=super-secret-value","PATH=/usr/bin"]},
             "State":{"Status":"running","Running":true,"RestartCount":2,
                      "OOMKilled":false,"StartedAt":"2026-09-10T00:00:00Z"}}
            """;

    private static ToolExecutor.ToolExecution exec(Map<String, Object> args) {
        return new ToolExecutor.ToolExecution(args,
                System.currentTimeMillis() + 10_000, 65_536);
    }

    @Test
    @DisplayName("构造 fail-closed：空容器 allowlist 拒绝装配")
    void emptyAllowlistRejected() {
        assertThatThrownBy(() -> new DockerInspectExecutor(
                new FakeTransport(Map.of()), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("docker.ps：只渲染 allowlist 容器（sidecar 越权目标不出现在输出）")
    void psRendersOnlyAllowlistedContainers() throws Exception {
        FakeTransport transport = new FakeTransport(Map.of(
                "/containers/json?all=1", PS_BODY));
        byte[] body = new DockerInspectExecutor(
                transport, Set.of("control-app")).listContainers(exec(Map.of()));

        String json = new String(body, StandardCharsets.UTF_8);
        assertThat(json).contains("control-app").doesNotContain("secret-sidecar");
        assertThat(transport.paths).containsExactly("/containers/json?all=1");
    }

    @Test
    @DisplayName("docker.ps 零 allowlist 容器：如实 NO_DATA（不伪造容器态）")
    void psWithNoAllowedContainerIsNoData() {
        FakeTransport transport = new FakeTransport(Map.of(
                "/containers/json?all=1", PS_BODY));
        assertThatThrownBy(() -> new DockerInspectExecutor(
                transport, Set.of("ghost-app")).listContainers(exec(Map.of())))
                .isInstanceOf(ToolModelVisibleException.class)
                .hasMessageContaining("NO_DATA");
    }

    @Test
    @DisplayName("docker.inspect：env 只回键名——secret 值零泄漏（T09 核心钉）")
    void inspectRedactsEnvValues() throws Exception {
        FakeTransport transport = new FakeTransport(Map.of(
                "/containers/control-app/json", INSPECT_BODY));
        byte[] body = new DockerInspectExecutor(transport, Set.of("control-app"))
                .inspectContainer(exec(Map.of("container", "control-app")));

        String json = new String(body, StandardCharsets.UTF_8);
        assertThat(json).contains("SECRET_TOKEN").contains("PATH")
                .doesNotContain("super-secret-value");
        Map<?, ?> payload = JSON.readValue(body, Map.class);
        Map<?, ?> data = (Map<?, ?>) payload.get("data");
        Map<?, ?> row = (Map<?, ?>) ((List<?>) data.get("result")).get(0);
        assertThat(row.get("envKeys")).asList()
                .containsExactly("SECRET_TOKEN", "PATH");
        assertThat(row.get("restartCount")).isEqualTo(2);
        assertThat(row.get("image")).isEqualTo("control:1.2.3");
    }

    @Test
    @DisplayName("T09/T03：越权容器名 → 发出前 INVALID_ARGS，传输零请求")
    void inspectDisallowedContainerRejectedBeforeRequest() {
        FakeTransport transport = new FakeTransport(Map.of());
        assertThatThrownBy(() -> new DockerInspectExecutor(
                transport, Set.of("control-app"))
                .inspectContainer(exec(Map.of("container", "secret-sidecar"))))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("allowlist");
        assertThat(transport.paths).as("目标端点计数零").isEmpty();
    }
}
