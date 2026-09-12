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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R7-X10 code.read L0 面：行窗形状闸、沙箱缺席/越界 NO_DATA、二进制 NO_DATA、
 * 行窗切片与行号、秘密词形脱敏、单行截断、字节上限截断如实标注。
 */
class CodeReadExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    private CodeReadExecutor executor() {
        return new CodeReadExecutor(root,
                CodeSourceBinding.parse("svc-a=repo-a@c1"));
    }

    private static ToolExecution call(Map<String, Object> args) {
        return new ToolExecution(args, 0L, 65536L);
    }

    @Test
    @DisplayName("行窗形状闸：未授权 service/缺 path/窗幅超限/倒窗全拒")
    void parseArgsGate() {
        Set<String> allowed = CodeSourceBinding.parse("svc-a=repo-a@c1").keySet();
        assertThatThrownBy(() -> CodeReadExecutor.parseArgs(
                Map.of("service", "svc-x", "path", "a.java"), allowed))
                .isInstanceOfSatisfying(ToolControlPlaneException.class, e ->
                        assertThat(e.reason()).isEqualTo(ToolControlReason.INVALID_ARGS));
        assertThatThrownBy(() -> CodeReadExecutor.parseArgs(
                Map.of("service", "svc-a"), allowed))
                .isInstanceOf(ToolControlPlaneException.class);
        assertThatThrownBy(() -> CodeReadExecutor.parseArgs(Map.of(
                "service", "svc-a", "path", "a.java",
                "line_start", "1", "line_end", "999999"), allowed))
                .isInstanceOf(ToolControlPlaneException.class);
        assertThatThrownBy(() -> CodeReadExecutor.parseArgs(Map.of(
                "service", "svc-a", "path", "a.java",
                "line_start", "5", "line_end", "2"), allowed))
                .isInstanceOf(ToolControlPlaneException.class);
    }

    @Test
    @DisplayName("读取面：行窗切片+行号、脱敏、单行截断、total_lines 溯源与绑定块")
    void readWindowSlice() throws Exception {
        Files.createDirectories(root.resolve("repo-a/src"));
        Files.writeString(root.resolve("repo-a/src/App.java"),
                "line one\n    String token = \"aaaaaaaaaaaaaaaa\";\nline three\n");
        byte[] out = executor().execute(call(Map.of(
                "service", "svc-a", "path", "src/App.java",
                "line_start", "2", "line_end", "3")));
        JsonNode data = JSON.readTree(out).get("data");
        assertThat(data.get("total_lines").asLong()).isEqualTo(3L);
        assertThat(data.get("truncated").asBoolean()).isFalse();
        assertThat(data.get("result")).hasSize(2);
        assertThat(data.get("result").get(0).get("line").asLong()).isEqualTo(2L);
        assertThat(data.get("result").get(0).get("text").asText())
                .contains("[REDACTED]").doesNotContain("aaaaaaaaaaaaaaaa");
        assertThat(data.get("result").get(1).get("text").asText()).isEqualTo("line three");
        assertThat(data.get("binding").get("commit").asText()).isEqualTo("c1");

        // 窄于文件的行窗不算截断；越窗请求按文件末收口
        byte[] wide = executor().execute(call(Map.of(
                "service", "svc-a", "path", "src/App.java",
                "line_start", "1", "line_end", "200")));
        assertThat(JSON.readTree(wide).get("data").get("result")).hasSize(3);
        assertThat(JSON.readTree(wide).get("data").get("truncated").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("诚实面：缺席/越界 NO_DATA；二进制 NO_DATA；超长行截断标注")
    void noDataAndClip() throws Exception {
        assertThatThrownBy(() -> executor().execute(call(
                Map.of("service", "svc-a", "path", "src/Missing.java"))))
                .isInstanceOfSatisfying(ToolModelVisibleException.class, e ->
                        assertThat(e.reason()).isEqualTo(ToolModelVisibleReason.NO_DATA));

        assertThatThrownBy(() -> executor().execute(call(
                Map.of("service", "svc-a", "path", "../outside.txt"))))
                .isInstanceOfSatisfying(ToolModelVisibleException.class, e ->
                        assertThat(e.reason()).isEqualTo(ToolModelVisibleReason.NO_DATA));

        Files.createDirectories(root.resolve("repo-a"));
        Files.write(root.resolve("repo-a/blob.bin"),
                new byte[]{'a', 0, 'b'});
        assertThatThrownBy(() -> executor().execute(call(
                Map.of("service", "svc-a", "path", "blob.bin"))))
                .isInstanceOfSatisfying(ToolModelVisibleException.class, e ->
                        assertThat(e.reason()).isEqualTo(ToolModelVisibleReason.NO_DATA));

        StringBuilder longLine = new StringBuilder("x".repeat(500));
        Files.writeString(root.resolve("repo-a/Long.java"), longLine + "\n",
                StandardCharsets.UTF_8);
        byte[] out = executor().execute(call(Map.of(
                "service", "svc-a", "path", "Long.java")));
        String text = JSON.readTree(out).get("data").get("result").get(0)
                .get("text").asText();
        assertThat(text.length()).isLessThanOrEqualTo(CodeReadExecutor.LINE_CLIP);
        assertThat(text).endsWith("…");
    }
}
