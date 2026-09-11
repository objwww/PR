package com.objwww.pr.control.infrastructure.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EN-05（§一 alert/记忆类 P0：alert_history_query——"是否复发/抖动"时间线）语义面 L0
 * （真 PG 查询路径归 En05ToolSourcesIT）：alert_event 只读、窗幅 ≤72h（复发判断需要
 * 天级窗，比查询面 900s 放宽并显式钉死）、行数 200 探针截断、空窗 NO_DATA。
 */
class AlertHistoryExecutorTest {

    /** 复发/抖动判断需要天级窗：72h 上界（硬门显式钉，超界 INVALID_ARGS） */
    static final long MAX_WINDOW_SECONDS = 259_200;

    private static Map<String, Object> args(String alertname, String since, String until) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("alertname", alertname);
        args.put("since", since);
        args.put("until", until);
        return args;
    }

    private static Map<String, Object> timelineRow(String fingerprint, String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("fingerprint", fingerprint);
        row.put("alertname", "HighErrorRate");
        row.put("status", status);
        row.put("starts_at", "2026-09-09T23:55:00Z");
        row.put("ends_at", status.equals("resolved") ? "2026-09-10T00:01:00Z" : null);
        row.put("recorded_at", "2026-09-10T00:00:30Z");
        return row;
    }

    @Test
    @DisplayName("参数合法：alertname+双时间戳齐备即通过（window ≤72h）")
    void parseArgsAcceptsWellFormedWindow() {
        AlertHistoryExecutor.Query q = AlertHistoryExecutor.parseArgs(args(
                "HighErrorRate", "2026-09-08T00:00:00Z", "2026-09-10T00:00:00Z"));
        assertThat(q.alertname()).isEqualTo("HighErrorRate");
        assertThat(q.since()).isEqualTo(Instant.parse("2026-09-08T00:00:00Z"));
    }

    @Test
    @DisplayName("违约面：缺 alertname / 非 ISO / 负窗 / 窗幅 >72h → INVALID_ARGS")
    void parseArgsRejectsViolations() {
        String since = "2026-09-08T00:00:00Z";
        String until = "2026-09-10T00:00:00Z";
        assertThatThrownBy(() -> AlertHistoryExecutor.parseArgs(
                args(" ", since, until)))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("alertname");
        assertThatThrownBy(() -> AlertHistoryExecutor.parseArgs(
                args("HighErrorRate", "not-an-instant", until)))
                .isInstanceOf(ToolControlPlaneException.class);
        assertThatThrownBy(() -> AlertHistoryExecutor.parseArgs(
                args("HighErrorRate", until, since)))
                .isInstanceOf(ToolControlPlaneException.class);
        assertThatThrownBy(() -> AlertHistoryExecutor.parseArgs(args("HighErrorRate",
                "2026-09-01T00:00:00Z", until)))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("INVALID_ARGS");
    }

    @Test
    @DisplayName("响应形状：status=success + data.result 时间线行（触发/恢复交替可辨）")
    void renderProducesTimelineShape() throws Exception {
        byte[] bytes = AlertHistoryExecutor.render(
                List.of(timelineRow("fp-1", "firing"), timelineRow("fp-1", "resolved")),
                false, 65_536);

        Map<?, ?> payload = new ObjectMapper().readValue(bytes, Map.class);
        assertThat(payload.get("status")).isEqualTo("success");
        Map<?, ?> data = (Map<?, ?>) payload.get("data");
        List<?> rows = (List<?>) data.get("result");
        assertThat(rows).hasSize(2);
        assertThat(data.get("truncated")).isEqualTo(Boolean.FALSE);
    }
}
