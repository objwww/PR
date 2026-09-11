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
 * EN-05（§一 change/部署面 P0：change_event_diff）语义面 L0（真 PG 查询路径归
 * En05ToolSourcesIT，本机无 docker 如实 NOT_RUN）：窗内变更行 + 窗前基线（最近一行），
 * 变更前后 diff 一次给出（T08：config 激活/回滚的生效事实可查、rollback_of 引用正确）。
 * 参数纪律同 ChangeQueryExecutor（窗幅 ≤900s / ISO-8601 / service allowlist fail-closed）。
 */
class ChangeDiffExecutorTest {

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

    private static Map<String, Object> row(String deployId, String action) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("deploy_id", deployId);
        row.put("action", action);
        row.put("service", "control-app");
        row.put("image_digest", "a".repeat(64));
        row.put("rollback_of", action.equals("ROLLBACK") ? "d-1" : null);
        row.put("effective_at", "2026-09-10T00:01:00Z");
        return row;
    }

    @Test
    @DisplayName("参数合法：service 缺省 control-app；显式 service 落 allowlist")
    void parseArgsDefaultsAndAcceptsAllowlistedService() {
        Instant since = Instant.parse("2026-09-10T00:00:00Z");
        Instant until = Instant.parse("2026-09-10T00:05:00Z");

        ChangeDiffExecutor.Query q1 = ChangeDiffExecutor.parseArgs(
                args(since.toString(), until.toString(), null), ALLOWLIST);
        assertThat(q1.service()).isEqualTo("control-app");
        assertThat(q1.since()).isEqualTo(since);
        assertThat(q1.until()).isEqualTo(until);

        ChangeDiffExecutor.Query q2 = ChangeDiffExecutor.parseArgs(
                args(since.toString(), until.toString(), "order-svc"), ALLOWLIST);
        assertThat(q2.service()).isEqualTo("order-svc");
    }

    @Test
    @DisplayName("违约面：窗幅 >900s / 负窗 / 非 ISO / 缺参 / 越权 service → INVALID_ARGS")
    void parseArgsRejectsSemanticsViolations() {
        String base = "2026-09-10T00:00:00Z";
        assertThatThrownBy(() -> ChangeDiffExecutor.parseArgs(
                args(base, "2026-09-10T00:15:01Z", null), ALLOWLIST))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("INVALID_ARGS");
        assertThatThrownBy(() -> ChangeDiffExecutor.parseArgs(
                args("2026-09-10T00:05:00Z", base, null), ALLOWLIST))
                .isInstanceOf(ToolControlPlaneException.class);
        assertThatThrownBy(() -> ChangeDiffExecutor.parseArgs(
                args("not-an-instant", base, null), ALLOWLIST))
                .isInstanceOf(ToolControlPlaneException.class);
        assertThatThrownBy(() -> ChangeDiffExecutor.parseArgs(
                args(base, base, "ghost-svc"), ALLOWLIST))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    @DisplayName("构造 fail-closed：空 allowlist 拒绝装配")
    void emptyAllowlistRejected() {
        assertThatThrownBy(() -> new ChangeDiffExecutor(null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("响应形状：data.baseline（窗前基线，可 null）+ data.result + truncated（T08 diff 面）")
    void renderCarriesBaselineAndDiffRows() throws Exception {
        Map<String, Object> baseline = row("d-0", "ACTIVATE");
        byte[] bytes = ChangeDiffExecutor.render(
                List.of(row("d-1", "ACTIVATE"), row("d-2", "ROLLBACK")),
                baseline, false, 65_536);

        Map<?, ?> payload = new ObjectMapper().readValue(bytes, Map.class);
        assertThat(payload.get("status")).isEqualTo("success");
        Map<?, ?> data = (Map<?, ?>) payload.get("data");
        assertThat(((Map<?, ?>) data.get("baseline")).get("deploy_id")).isEqualTo("d-0");
        assertThat((List<?>) data.get("result")).hasSize(2);
        assertThat(data.get("truncated")).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("无窗前基线（首次部署）：baseline=null 如实呈现")
    void renderWithoutBaselineIsNull() throws Exception {
        byte[] bytes = ChangeDiffExecutor.render(
                List.of(row("d-1", "ACTIVATE")), null, false, 65_536);

        Map<?, ?> data = (Map<?, ?>) new ObjectMapper().readValue(bytes, Map.class).get("data");
        assertThat((List<?>) data.get("result")).hasSize(1);
        assertThat(data.get("baseline")).isNull();
    }
}
