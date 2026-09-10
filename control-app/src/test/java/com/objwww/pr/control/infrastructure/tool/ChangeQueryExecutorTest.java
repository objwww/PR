package com.objwww.pr.control.infrastructure.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EX-B1 change.query 语义面 L0（真 PG 查询路径与 NO_DATA 归 ExB1ChangeEventIT）：
 * 参数校验（窗幅 ≤900s / ISO-8601 / service allowlist fail-closed）、
 * 响应形状契约、200 行截断标记、字节流式截断（RESULT_OVERSIZE）。
 */
class ChangeQueryExecutorTest {

    private static final Set<String> ALLOWLIST = Set.of("control-app", "order-svc");

    private static Map<String, Object> args(String since, String until, String service) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("since", since);
        args.put("until", until);
        if (service != null) {
            args.put("service", service);
        }
        return args;
    }

    private static Map<String, Object> row(String deployId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("deploy_id", deployId);
        row.put("action", "ACTIVATE");
        row.put("service", "control-app");
        row.put("effective_at", "2026-09-10T00:00:00Z");
        return row;
    }

    // ---------------------------------------------------------------- 参数语义

    @Test
    @DisplayName("参数合法：service 缺省 control-app；显式 service 落 allowlist")
    void parseArgsDefaultsAndAcceptsAllowlistedService() {
        Instant since = Instant.parse("2026-09-10T00:00:00Z");
        Instant until = Instant.parse("2026-09-10T00:05:00Z");

        ChangeQueryExecutor.Query q1 =
                ChangeQueryExecutor.parseArgs(args(
                        "2026-09-10T00:00:00Z", "2026-09-10T00:05:00Z", null), ALLOWLIST);
        assertThat(q1.service()).isEqualTo("control-app");

        ChangeQueryExecutor.Query q2 =
                ChangeQueryExecutor.parseArgs(args(
                        since.toString(), until.toString(), "order-svc"), ALLOWLIST);
        assertThat(q2.since()).isEqualTo(since);
        assertThat(q2.until()).isEqualTo(until);
        assertThat(q2.service()).isEqualTo("order-svc");
    }

    @Test
    @DisplayName("违约面：窗幅 >900s / 负窗 / 非 ISO-8601 / 缺参 / service 越出 allowlist → INVALID_ARGS")
    void parseArgsRejectsSemanticsViolations() {
        String base = "2026-09-10T00:00:00Z";
        assertThatThrownBy(() -> ChangeQueryExecutor.parseArgs(args(base,
                "2026-09-10T00:15:01Z", null), ALLOWLIST))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("INVALID_ARGS");
        assertThatThrownBy(() -> ChangeQueryExecutor.parseArgs(args(
                "2026-09-10T00:05:00Z", base, null), ALLOWLIST))
                .isInstanceOf(ToolControlPlaneException.class);
        assertThatThrownBy(() -> ChangeQueryExecutor.parseArgs(args(
                "not-an-instant", base, null), ALLOWLIST))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("since");
        assertThatThrownBy(() -> ChangeQueryExecutor.parseArgs(args(null, base, null), ALLOWLIST))
                .isInstanceOf(ToolControlPlaneException.class);
        assertThatThrownBy(() -> ChangeQueryExecutor.parseArgs(args(
                base, "2026-09-10T00:01:00Z", "ghost-svc"), ALLOWLIST))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    @DisplayName("构造 fail-closed：空 allowlist 拒绝装配")
    void emptyAllowlistRejected() {
        assertThatThrownBy(() -> new ChangeQueryExecutor(null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- 序列化面

    @Test
    @DisplayName("响应形状：status=success + data.truncated + data.result 序列（Agent 统一解析面）")
    void renderProducesAgentParseableShape() throws Exception {
        byte[] bytes = ChangeQueryExecutor.render(
                List.of(row("d-1"), row("d-2")), false, 65_536);

        Map<?, ?> payload = new ObjectMapper().readValue(bytes, Map.class);
        assertThat(payload.get("status")).isEqualTo("success");
        Map<?, ?> data = (Map<?, ?>) payload.get("data");
        assertThat(data.get("truncated")).isEqualTo(Boolean.FALSE);
        assertThat((List<?>) data.get("result")).hasSize(2);
    }

    @Test
    @DisplayName("字节上限：流式逐行探针超限 → RESULT_OVERSIZE（同 F17 即断语义）")
    void renderThrowsResultOversizeBeyondByteCap() {
        List<Map<String, Object>> rows = List.of(row("d-1"), row("d-2"));
        assertThatThrownBy(() -> ChangeQueryExecutor.render(rows, false, 32))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("RESULT_OVERSIZE");
    }

    @Test
    @DisplayName("truncated 标记随行截断透传（200 行硬顶在 execute 面，此处验序列化承载）")
    void renderCarriesTruncatedFlag() throws Exception {
        byte[] bytes = ChangeQueryExecutor.render(List.of(row("d-1")), true, 65_536);
        Map<?, ?> data = (Map<?, ?>) new ObjectMapper().readValue(bytes, Map.class).get("data");
        assertThat(data.get("truncated")).isEqualTo(Boolean.TRUE);
    }
}
