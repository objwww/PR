package com.objwww.pr.control.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolExecutor.ToolExecution;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R7-X10 code.search L0 面：绑定授权先行（未授权 service 零触盘）、字面量行匹配
 * （大小写不敏感、无正则面）、三重上限触限 truncated 如实留痕、二进制跳过、秘密
 * 词形脱敏、NO_DATA 空结果诚实面。全部 @TempDir 零真源依赖；真树探针见
 * {@link #realTreeProbe()}（系统属性门控，195 部署面专用）。
 */
class CodeSearchExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    private CodeSearchExecutor executor() {
        return new CodeSearchExecutor(root,
                CodeSourceBinding.parse("svc-a=repo-a@c1,svc-b=repo-b@c2"));
    }

    private static ToolExecution call(Map<String, Object> args) {
        return new ToolExecution(args, 0L, 65536L);
    }

    @Test
    @DisplayName("参数语义面：未授权 service 零触盘即拒；query/path_prefix 形状闸")
    void parseArgsGate() {
        assertThatThrownBy(() -> CodeSearchExecutor.parseArgs(
                Map.of("service", "svc-x", "query", "q"), CodeSourceBinding.parse(
                        "svc-a=repo-a@c1").keySet()))
                .isInstanceOfSatisfying(ToolControlPlaneException.class, e ->
                        assertThat(e.reason()).isEqualTo(ToolControlReason.INVALID_ARGS));
        assertThatThrownBy(() -> CodeSearchExecutor.parseArgs(
                Map.of("service", "svc-a"), CodeSourceBinding.parse(
                        "svc-a=repo-a@c1").keySet()))
                .isInstanceOf(ToolControlPlaneException.class);
        assertThatThrownBy(() -> CodeSearchExecutor.parseArgs(
                Map.of("service", "svc-a", "query", "q", "path_prefix", "../x"),
                CodeSourceBinding.parse("svc-a=repo-a@c1").keySet()))
                .isInstanceOf(ToolControlPlaneException.class);
    }

    @Test
    @DisplayName("命中面：大小写不敏感字面量、路径/行号/截断文本、跳过依赖与二进制、脱敏")
    void scanMatchAndRedact() throws Exception {
        Files.createDirectories(root.resolve("repo-a/src/main"));
        Files.createDirectories(root.resolve("repo-a/node_modules/pkg"));
        Files.writeString(root.resolve("repo-a/src/main/App.java"),
                "public class App {\n    // password=supersecret99\n"
                        + "    // TODO fix Password Reset flow\n}\n");
        Files.writeString(root.resolve("repo-a/node_modules/pkg/index.js"),
                "fix password here\n");
        Files.write(root.resolve("repo-a/src/main/blob.bin"),
                new byte[]{'a', 0, 'b', 'p', 'a', 's', 's'});

        byte[] out = executor().execute(call(Map.of(
                "service", "svc-a", "query", "password")));
        JsonNode data = JSON.readTree(out).get("data");
        assertThat(data.get("truncated").asBoolean()).isFalse();
        JsonNode rows = data.get("result");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("path").asText()).isEqualTo("src/main/App.java");
        assertThat(rows.get(0).get("line").asLong()).isEqualTo(2L);
        assertThat(rows.get(0).get("text").asText())
                .contains("[REDACTED]").doesNotContain("supersecret99");
        assertThat(rows.get(1).get("line").asLong()).isEqualTo(3L);
        assertThat(data.get("binding").get("repo").asText()).isEqualTo("repo-a");
        assertThat(data.get("binding").get("commit").asText()).isEqualTo("c1");
        assertThat(data.get("binding").get("provenance").asText()).isEqualTo("host-declared");

        // node_modules 与二进制不在结果集（跳过面）
        String all = JSON.readTree(out).toString();
        assertThat(all).doesNotContain("node_modules").doesNotContain("blob.bin");
    }

    @Test
    @DisplayName("上限面：命中触 MAX_MATCHES 即停且 truncated=true；path_prefix 限定面")
    void scanTruncationAndPrefix() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            body.append("needle line ").append(i).append('\n');
        }
        Files.createDirectories(root.resolve("repo-a/src"));
        Files.writeString(root.resolve("repo-a/src/Big.java"), body.toString());
        Files.createDirectories(root.resolve("repo-a/lib"));
        Files.writeString(root.resolve("repo-a/lib/Other.java"), "needle elsewhere\n");

        byte[] out = executor().execute(call(Map.of(
                "service", "svc-a", "query", "needle")));
        JsonNode data = JSON.readTree(out).get("data");
        assertThat(data.get("result")).hasSize(CodeSearchExecutor.MAX_MATCHES);
        assertThat(data.get("truncated").asBoolean()).isTrue();

        byte[] prefixed = executor().execute(call(Map.of(
                "service", "svc-a", "query", "needle", "path_prefix", "lib")));
        JsonNode scoped = JSON.readTree(prefixed).get("data");
        assertThat(scoped.get("result")).hasSize(1);
        assertThat(scoped.get("result").get(0).get("path").asText())
                .isEqualTo("lib/Other.java");
    }

    @Test
    @DisplayName("诚实面：零命中 NO_DATA；绑定 repo 缺席 SOURCE_UNAVAILABLE")
    void noDataAndSourceUnavailable() throws Exception {
        Files.createDirectories(root.resolve("repo-a/src"));
        Files.writeString(root.resolve("repo-a/src/A.java"), "nothing here\n");
        assertThatThrownBy(() -> executor().execute(call(
                Map.of("service", "svc-a", "query", "absent-string"))))
                .isInstanceOfSatisfying(ToolModelVisibleException.class, e ->
                        assertThat(e.reason()).isEqualTo(ToolModelVisibleReason.NO_DATA));

        assertThatThrownBy(() -> executor().execute(call(
                Map.of("service", "svc-b", "query", "x"))))
                .isInstanceOfSatisfying(ToolModelVisibleException.class, e ->
                        assertThat(e.reason())
                                .isEqualTo(ToolModelVisibleReason.SOURCE_UNAVAILABLE));
    }

    @Test
    @DisplayName("真树探针（195 部署面专用，-Dr7x10.probe.root=/opt/build 触发）")
    void realTreeProbe() throws Exception {
        String probeRoot = System.getProperty("r7x10.probe.root", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(!probeRoot.isBlank(),
                "未设 r7x10.probe.root，跳过真树探针");
        String probeRepo = System.getProperty("r7x10.probe.repo", "pr");
        Path real = Path.of(probeRoot);
        CodeSearchExecutor executor = new CodeSearchExecutor(real,
                CodeSourceBinding.parse("probe=" + probeRepo + "@probe"));
        byte[] out = executor.execute(call(Map.of(
                "service", "probe", "query", "class ContextAssembler",
                "path_prefix", "control-app/src/main/java")));
        JsonNode data = JSON.readTree(out).get("data");
        assertThat(data.get("result").size()).isGreaterThanOrEqualTo(1);
        assertThat(data.get("result").get(0).get("text").asText())
                .contains("ContextAssembler");
    }
}
