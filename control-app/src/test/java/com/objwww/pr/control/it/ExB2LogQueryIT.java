package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolExecutor;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;
import com.objwww.pr.control.infrastructure.tool.LogQueryExecutor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * EX-B2 具名 IT（真 Loki，Testcontainers grafana/loki:3.4.2）：OTLP HTTP 灌真行 →
 * LogQueryExecutor query_range 真查回统一形状；无数据三态之 EMPTY(NO_DATA) 与
 * SOURCE_UNAVAILABLE（不可达）；201 行截 200+truncated。本机无 docker 自动跳过。
 * service_name 标签 = Loki OTLP 默认映射（resource.service.name → label），生产
 * 侧由 otelcol docker_logs move 算子补齐（L2 面另证）。
 */
@Testcontainers(disabledWithoutDocker = true)
class ExB2LogQueryIT {

    @Container
    static final GenericContainer<?> LOKI = new GenericContainer<>("grafana/loki:3.4.2")
            .withExposedPorts(3100)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("loki-test.yaml"),
                    "/etc/loki/local-config.yaml")
            .waitingFor(Wait.forHttp("/ready").forStatusCode(200));

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private LogQueryExecutor executor(Set<String> allowlist, String baseUrl) {
        return new LogQueryExecutor(baseUrl, allowlist);
    }

    private String lokiBase() {
        return "http://" + LOKI.getHost() + ":" + LOKI.getMappedPort(3100);
    }

    /** OTLP HTTP JSON 推行（ProtoJSON 面）：service.name 直进 resource attr */
    private void push(String service, List<Instant> timestamps, String line) throws Exception {
        List<Object> records = new ArrayList<>();
        for (Instant ts : timestamps) {
            records.add(Map.of(
                    "timeUnixNano", String.valueOf(ts.toEpochMilli() * 1_000_000L),
                    "observedTimeUnixNano", String.valueOf(ts.toEpochMilli() * 1_000_000L),
                    "severityText", "INFO",
                    "body", Map.of("stringValue", line)));
        }
        String payload = JSON.writeValueAsString(Map.of("resourceLogs", List.of(
                Map.of("resource", Map.of("attributes", List.of(Map.of(
                        "key", "service.name", "value", Map.of("stringValue", service)))),
                        "scopeLogs", List.of(Map.of("scope", Map.of("name", "exb2-it"),
                                "logRecords", records))))));
        HttpRequest request = HttpRequest.newBuilder(URI.create(lokiBase() + "/otlp/v1/logs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString());
        // OTLP/HTTP 成功族 200/202/204——真机 Loki 3.4.2 返回 204（B-34）
        assertThat(response.statusCode()).as("OTLP push 接受")
                .isIn(200, 202, 204);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> query(LogQueryExecutor executor, Instant since, Instant until,
            String service) throws Exception {
        byte[] bytes = executor.execute(new ToolExecutor.ToolExecution(
                Map.of("since", since.toString(), "until", until.toString(),
                        "service", service),
                System.currentTimeMillis() + 8_000, 1_048_576));
        return (Map<String, Object>) ((Map<String, Object>) new ObjectMapper()
                .readValue(bytes, Map.class)).get("data");
    }

    @Test
    void pushedRealRowIsQueryableInUnifiedShape() throws Exception {
        Instant now = Instant.now();
        push("checkout", List.of(now), "exb2 real log row order id=1");

        LogQueryExecutor executor = executor(Set.of("control-app", "checkout"), lokiBase());
        Map<String, Object> data = Map.of();
        // Loki 索引可见性有延迟：至多 ~10s 轮询至行出现
        for (int i = 0; i < 20 && !data.containsKey("result"); i++) {
            try {
                data = query(executor, now.minusSeconds(60), now.plusSeconds(60), "checkout");
            } catch (ToolModelVisibleException e) {
                if (e.reason() != ToolModelVisibleReason.NO_DATA) {
                    throw e;
                }
                Thread.sleep(500);
            }
        }
        List<?> result = (List<?>) data.get("result");
        assertThat(result).as("真行可查").hasSize(1);
        Map<String, Object> row = (Map<String, Object>) result.get(0);
        assertThat(row)
                .containsEntry("service", "checkout")
                .containsEntry("line", "exb2 real log row order id=1");
        assertThat(data.get("truncated")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void emptyFutureWindowIsModelVisibleNoData() {
        Instant now = Instant.now();
        LogQueryExecutor executor = executor(Set.of("control-app", "checkout"), lokiBase());
        ToolModelVisibleException e = catchThrowableOfType(
                () -> query(executor, now.plusSeconds(300), now.plusSeconds(310), "checkout"),
                ToolModelVisibleException.class);
        assertThat(e.reason()).isEqualTo(ToolModelVisibleReason.NO_DATA);
    }

    @Test
    void unreachableLokiIsSourceUnavailable() {
        LogQueryExecutor dead = executor(Set.of("control-app"),
                "http://127.0.0.1:1");
        ToolModelVisibleException e = catchThrowableOfType(
                () -> dead.execute(new ToolExecutor.ToolExecution(
                        Map.of("since", Instant.now().minusSeconds(60).toString(),
                                "until", Instant.now().toString()),
                        System.currentTimeMillis() + 4_000, 65_536)),
                ToolModelVisibleException.class);
        assertThat(e.reason()).isEqualTo(ToolModelVisibleReason.SOURCE_UNAVAILABLE);
    }

    @Test
    void bulk201RowsTruncateTo200WithFlag() throws Exception {
        Instant now = Instant.now();
        List<Instant> stamps = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            stamps.add(now.minusSeconds(400 - i));
        }
        push("frontend", stamps, "exb2 bulk row");

        LogQueryExecutor executor = executor(Set.of("frontend"), lokiBase());
        Map<String, Object> data = Map.of();
        for (int i = 0; i < 20 && !data.containsKey("result"); i++) {
            try {
                data = query(executor, now.minusSeconds(500), now.plusSeconds(60),
                        "frontend");
            } catch (ToolModelVisibleException e) {
                if (e.reason() != ToolModelVisibleReason.NO_DATA) {
                    throw e;
                }
                Thread.sleep(500);
            }
        }
        List<?> result = (List<?>) data.get("result");
        assertThat(result).hasSize(200);
        assertThat(data.get("truncated")).isEqualTo(Boolean.TRUE);
    }
}
