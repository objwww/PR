package com.objwww.pr.duty.snapshot;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快照拉取锚（M7-17）：成功→内存+磁盘副本；195 不可达→保旧缓存；冷启动→磁盘副本
 * 兜底（外部腿本职：195 挂了排班仍已知）。
 */
class SnapshotRefresherTest {

    private HttpServer server;

    @TempDir
    Path dataDir;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static final String SNAPSHOT_JSON = """
            {"scheduleId":"s1","scheduleVersion":7,
             "generatedAt":"2026-09-10T08:00:00Z","validUntil":"2026-09-10T08:01:30Z",
             "onCall":"alice","viaOverride":false,"viaFallback":false,
             "escalationChain":["alice","bob"],
             "channels":[
               {"id":"c1","name":"primary-bot","platform":"DINGTALK","priority":1,
                "isFallback":false,"envKeyWebhook":"K_WEBHOOK","envKeySecret":"K_SECRET"},
               {"id":"c2","name":"fallback-bot","platform":"WECOM","priority":9,
                "isFallback":true,"envKeyWebhook":"K_WEBHOOK_FB","envKeySecret":""}],
             "layers":[{"layerIndex":0,"members":["alice","bob"]}]}
            """;

    private String startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] out = SNAPSHOT_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/snapshot";
    }

    @Test
    @DisplayName("成功拉取：内存缓存（onCall/通道链 env 键名序）+ 磁盘副本写入")
    void pullsAndPersists() throws IOException {
        SnapshotRefresher refresher = new SnapshotRefresher(new JsonHttp(), startServer(),
                "tok", dataDir.toString());

        refresher.refresh();

        SnapshotState snapshot = refresher.current();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.onCall()).isEqualTo("alice");
        assertThat(snapshot.scheduleVersion()).isEqualTo(7);
        assertThat(snapshot.channels()).hasSize(2);
        assertThat(snapshot.channels().get(0).envKeyWebhook()).isEqualTo("K_WEBHOOK");
        assertThat(snapshot.channels().get(1).fallback()).isTrue();
        assertThat(Files.readString(disk(), StandardCharsets.UTF_8))
                .contains("\"onCall\":\"alice\"").contains("K_WEBHOOK_FB");
    }

    @Test
    @DisplayName("195 不可达：保旧缓存不炸（外部腿语义）")
    void deadServerKeepsStaleCache() throws IOException {
        SnapshotRefresher refresher = new SnapshotRefresher(new JsonHttp(), startServer(),
                "tok", dataDir.toString());
        refresher.refresh();
        server.stop(0);
        server = null;

        refresher.refresh();   // 服务已停

        assertThat(refresher.current()).isNotNull();
        assertThat(refresher.current().onCall()).isEqualTo("alice");
    }

    @Test
    @DisplayName("冷启动 + 拉取失败：磁盘副本兜底加载")
    void coldBootLoadsDiskCopy() throws IOException {
        SnapshotRefresher first = new SnapshotRefresher(new JsonHttp(), startServer(),
                "tok", dataDir.toString());
        first.refresh();
        server.stop(0);
        server = null;

        SnapshotRefresher cold = new SnapshotRefresher(new JsonHttp(),
                "http://127.0.0.1:1/snapshot", "tok", dataDir.toString());
        cold.init();   // loadDisk + refresh（两者都失败面：disk 在，pull 拒连）

        assertThat(cold.current()).isNotNull();
        assertThat(cold.current().scheduleVersion()).isEqualTo(7);
        assertThat(cold.current().channels()).hasSize(2);
    }

    private Path disk() {
        return dataDir.resolve("snapshot-last.json");
    }
}
