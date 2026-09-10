package com.objwww.pr.duty.writeback;

import com.objwww.pr.duty.http.JsonHttp;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 台账回写锚（M7-17）：195 不可达 → spool 落盘；恢复 → 重放清空；服务端按行重放
 * （幂等去重归 195 侧 fingerprint，本面只管不丢行）。
 */
class WriteBackClientTest {

    private HttpServer server;
    private final AtomicInteger posts = new AtomicInteger();
    private final AtomicReference<String> lastPayload = new AtomicReference<>();

    @TempDir
    Path dataDir;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            posts.incrementAndGet();
            lastPayload.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/duty/notifications";
    }

    /** 必然拒绝连接的本地端口（无监听） */
    private static String deadUrl() {
        return "http://127.0.0.1:1/api/duty/notifications";
    }

    static WriteBackClient.Event gatusEvent() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("endpoint", "RCA_SYSTEM_control_health");
        labels.put("group", "control-plane");
        return new WriteBackClient.Event("firing", null,
                "探针告警 RCA_SYSTEM_control_health [firing]",
                "group=control-plane description=3 连败", "gatus/control-plane",
                Instant.parse("2026-09-10T08:00:00Z").toString(), labels);
    }

    @Test
    @DisplayName("195 在场：POST 2xx → 不落 spool；payload 含契约字段")
    void liveServerDrainsNothing() throws IOException {
        String url = startServer(200);
        WriteBackClient client = new WriteBackClient(new JsonHttp(), url, "tok",
                dataDir.toString());

        client.push(gatusEvent());

        assertThat(posts.get()).isEqualTo(1);
        // source=GATUS 在服务端铸造（身份单源）；adapter 只传事件字段
        assertThat(lastPayload.get()).doesNotContain("\"source\"");
        assertThat(lastPayload.get()).contains("RCA_SYSTEM_control_health")
                .contains("\"triggeredAt\":\"2026-09-10T08:00:00Z\"")
                .contains("\"groupKey\":\"gatus/control-plane\"");
        assertThat(spoolFile()).doesNotExist();
    }

    @Test
    @DisplayName("195 不可达：spool 落盘一行；服务恢复后重放清空且不丢行")
    void deadServerSpoolsThenReplayDrains() throws IOException {
        WriteBackClient dead = new WriteBackClient(new JsonHttp(), deadUrl(), "tok",
                dataDir.toString());
        dead.push(gatusEvent());
        dead.push(gatusEvent());

        Path spool = spoolFile();
        assertThat(spool).exists();
        assertThat(Files.readAllLines(spool)).hasSize(2);

        String url = startServer(200);
        WriteBackClient alive = new WriteBackClient(new JsonHttp(), url, "tok",
                dataDir.toString());
        alive.replaySpool();

        assertThat(posts.get()).isEqualTo(2);   // 两行都重放
        assertThat(Files.readAllLines(spool)).isEmpty();
    }

    @Test
    @DisplayName("服务端 4xx：不重放成功的行也不无限重写（保留行待人工/后续窗口）")
    void rejectedLinesStaySpooled() throws IOException {
        String url = startServer(400);
        WriteBackClient client = new WriteBackClient(new JsonHttp(), url, "tok",
                dataDir.toString());
        client.push(gatusEvent());       // 400 → spool
        client.replaySpool();            // 仍 400 → 行保留

        assertThat(Files.readAllLines(spoolFile())).hasSize(1);
    }

    private Path spoolFile() {
        return dataDir.resolve("spool").resolve("writeback.jsonl");
    }
}
