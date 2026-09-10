package com.objwww.pr.control.infrastructure.tool;

import com.objwww.pr.control.alert.application.tool.ToolExecutor;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * LogQueryExecutor L0（EX-B2）：allowlist fail-closed 次序（B-24 律）、参数语义、
 * 无数据三态（EMPTY=NO_DATA / SOURCE_UNAVAILABLE / QUERY_FAILED——卡面硬要求）、
 * 401/429/超大各确定结局、Loki 响应 → 统一形状 render + 201 截断（B-27 同纪律）。
 */
class LogQueryExecutorTest {

    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");

    private HttpServer loki;
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicReference<String> lastQuery = new AtomicReference<>("");

    @BeforeEach
    void setUp() throws Exception {
        loki = HttpServer.create(new InetSocketAddress(0), 0);
        loki.createContext("/loki/api/v1/query_range", exchange -> {
            lastQuery.set(exchange.getRequestURI().getQuery());
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        loki.start();
    }

    @AfterEach
    void tearDown() {
        loki.stop(0);
    }

    private LogQueryExecutor executor() {
        return new LogQueryExecutor("http://127.0.0.1:" + loki.getAddress().getPort(),
                Set.of("control-app", "checkout"));
    }

    private static ToolExecutor.ToolExecution args(Map<String, Object> validated) {
        return new ToolExecutor.ToolExecution(validated,
                System.currentTimeMillis() + 4_000, 65_536);
    }

    private static Map<String, Object> window() {
        return Map.of("since", NOW.minusSeconds(60).toString(),
                "until", NOW.toString());
    }

    /** Loki streams 形状：entries = [nsEpoch, line] */
    private static String lokiBody(String[][] entries) {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < entries.length; i++) {
            if (i > 0) {
                values.append(',');
            }
            values.append("[\"").append(entries[i][0]).append("\",\"")
                    .append(entries[i][1]).append("\"]");
        }
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\","
                + "\"result\":[{\"stream\":{\"service_name\":\"checkout\"},"
                + "\"values\":[" + values + "]}]}}";
    }

    @Test
    void emptyAllowlistFailsClosedBeforeDependencies() {
        assertThatThrownBy(() -> new LogQueryExecutor("http://loki:3100", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlist");
        assertThatThrownBy(() -> new LogQueryExecutor("http://loki:3100/", Set.of("control-app")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lokiBaseUrl");
    }

    @Test
    void argsDefaultsAllowlistAndViolations() {
        LogQueryExecutor executor = new LogQueryExecutor("http://loki:3100",
                Set.of("control-app", "checkout"));

        assertThat(executor.parseArgs(Map.of("since", NOW.minusSeconds(60).toString(),
                "until", NOW.toString())).service()).isEqualTo("control-app");
        assertThat(executor.parseArgs(Map.of("since", NOW.minusSeconds(60).toString(),
                "until", NOW.toString(), "service", "checkout")).service())
                .isEqualTo("checkout");

        List<Map<String, Object>> violations = List.of(
                Map.of("since", "not-a-time", "until", NOW.toString()),
                Map.of("since", NOW.toString(), "until", NOW.minusSeconds(1).toString()),
                Map.of("since", NOW.minusSeconds(901).toString(), "until", NOW.toString()),
                Map.of("since", NOW.minusSeconds(60).toString(), "until", NOW.toString(),
                        "service", "ghost-svc"));
        for (Map<String, Object> bad : violations) {
            assertThat(catchThrowableOfType(() -> executor.parseArgs(bad),
                            ToolControlPlaneException.class).reason())
                    .isEqualTo(ToolControlReason.INVALID_ARGS);
        }
    }

    @Test
    void lokiRowsMapToUnifiedShapeWithSelector() throws Exception {
        body.set(lokiBody(new String[][]{
                {String.valueOf(NOW.minusSeconds(30).toEpochMilli() * 1_000_000L),
                        "order placed id=1"},
                {String.valueOf(NOW.minusSeconds(10).toEpochMilli() * 1_000_000L),
                        "payment ok"}}));
        byte[] out = executor().execute(args(Map.of("since",
                NOW.minusSeconds(60).toString(), "until", NOW.toString(),
                "service", "checkout")));

        Map<String, Object> payload = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(out, Map.class);
        assertThat(payload.get("status")).isEqualTo("success");
        Map<String, Object> data = (Map<String, Object>) payload.get("data");
        assertThat(data.get("truncated")).isEqualTo(Boolean.FALSE);
        List<?> result = (List<?>) data.get("result");
        assertThat(result).hasSize(2);
        Map<String, Object> firstRow = (Map<String, Object>) result.get(0);
        assertThat(firstRow).containsEntry("line", "order placed id=1")
                .containsEntry("service", "checkout");
        assertThat(lastQuery.get()).contains("query={service_name=\"checkout\"}")
                .contains("limit=201").contains("direction=backward");
    }

    @Test
    void emptyResultIsModelVisibleNoData() {
        body.set("{\"status\":\"success\",\"data\":{\"resultType\":\"streams\","
                + "\"result\":[]}}");
        ToolModelVisibleException e = catchThrowableOfType(
                () -> executor().execute(args(window())), ToolModelVisibleException.class);
        assertThat(e.reason()).isEqualTo(ToolModelVisibleReason.NO_DATA);
    }

    @Test
    void statusFacesMapToDeterministicOutcomes() {
        // 429 → RATE_LIMITED
        status.set(429);
        assertThat(catchThrowableOfType(() -> executor().execute(args(window())),
                ToolModelVisibleException.class).reason())
                .isEqualTo(ToolModelVisibleReason.RATE_LIMITED);
        // 401 → AUTH_FAILED（控制面）
        status.set(401);
        assertThat(catchThrowableOfType(() -> executor().execute(args(window())),
                ToolControlPlaneException.class).reason())
                .isEqualTo(ToolControlReason.AUTH_FAILED);
        // 400 良构查询被拒 → QUERY_FAILED（控制面）
        status.set(400);
        assertThat(catchThrowableOfType(() -> executor().execute(args(window())),
                ToolControlPlaneException.class).reason())
                .isEqualTo(ToolControlReason.QUERY_FAILED);
        // 503 → SOURCE_UNAVAILABLE（模型可见可重试）
        status.set(503);
        assertThat(catchThrowableOfType(() -> executor().execute(args(window())),
                ToolModelVisibleException.class).reason())
                .isEqualTo(ToolModelVisibleReason.SOURCE_UNAVAILABLE);
    }

    @Test
    void oversizeResponseIsBoundedReadResultOversize() {
        status.set(200);
        body.set(lokiBody(new String[][]{{"1", "x".repeat(4_096)}}));
        assertThat(catchThrowableOfType(() -> executor().execute(
                        new ToolExecutor.ToolExecution(window(),
                                System.currentTimeMillis() + 4_000, 512)),
                ToolControlPlaneException.class).reason())
                .isEqualTo(ToolControlReason.RESULT_OVERSIZE);
    }

    @Test
    void render201EntriesYields200AndTruncatedFlag() throws Exception {
        String[][] entries = new String[201][2];
        for (int i = 0; i < 201; i++) {
            entries[i] = new String[]{String.valueOf(
                    NOW.minusSeconds(200 - i).toEpochMilli() * 1_000_000L), "line-" + i};
        }
        byte[] out = executor().render(lokiBody(entries).getBytes(StandardCharsets.UTF_8),
                "checkout", 65_536);
        Map<?, ?> data = (Map<?, ?>) new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(out, Map.class).get("data");
        assertThat((List<?>) data.get("result")).hasSize(200);
        assertThat(data.get("truncated")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void renderEmptyThrowsNoData() {
        assertThat(catchThrowableOfType(() -> executor().render(
                        "{\"status\":\"success\",\"data\":{\"result\":[]}}"
                                .getBytes(StandardCharsets.UTF_8), "control-app", 65_536),
                ToolModelVisibleException.class).reason())
                .isEqualTo(ToolModelVisibleReason.NO_DATA);
    }
}
