package com.objwww.pr.duty.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.objwww.pr.duty.http.JsonHttp;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 快照拉取器（M7-17）：60s 经 WG 隧道窄代理拉 195 /api/duty/schedule/snapshot，
 * 内存缓存 + 磁盘副本（/data/snapshot-last.json）——195 不可达时用最后已知排班，
 * 这是外部腿本职（不变量 5：195 全挂通知也必须能发）。
 */
@Component
public class SnapshotRefresher {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRefresher.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final JsonHttp http;
    private final String snapshotUrl;
    private final String bearer;
    private final Path diskCopy;

    private volatile SnapshotState current;

    public SnapshotRefresher(JsonHttp http,
                             @Value("${app.snapshot-url}") String snapshotUrl,
                             @Value("${app.operator-bearer}") String bearer,
                             @Value("${app.data-dir}") String dataDir) {
        this.http = http;
        this.snapshotUrl = snapshotUrl;
        this.bearer = bearer;
        this.diskCopy = Path.of(dataDir, "snapshot-last.json");
    }

    @PostConstruct
    void init() {
        loadDisk();
        refresh();
    }

    @Scheduled(fixedDelayString = "${app.pull-interval-ms:60000}")
    void refresh() {
        try {
            SnapshotState pulled = parse(http.getJson(snapshotUrl, bearer));
            current = pulled;
            persist(pulled);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 保旧缓存照发（外部腿语义）；持续失败由运维面观察日志
            log.warn("DUTY_SNAPSHOT_PULL_FAILED url={} cached_version={} error={}",
                    snapshotUrl,
                    current == null ? "none" : current.scheduleVersion(),
                    String.valueOf(e));
        }
    }

    /** 当前快照（null=从未成功且无磁盘副本） */
    public SnapshotState current() {
        return current;
    }

    private SnapshotState parse(JsonNode root) {
        List<SnapshotState.Channel> channels = new ArrayList<>();
        for (JsonNode c : root.path("channels")) {
            channels.add(new SnapshotState.Channel(
                    text(c, "name"), text(c, "platform"),
                    c.path("priority").asInt(99), c.path("isFallback").asBoolean(false),
                    text(c, "envKeyWebhook"), text(c, "envKeySecret")));
        }
        return new SnapshotState(
                root.path("onCall").isMissingNode() || root.path("onCall").isNull()
                        ? null : root.path("onCall").asText(),
                root.path("viaFallback").asBoolean(false),
                root.path("scheduleVersion").asLong(0),
                Instant.parse(root.path("generatedAt").asText("1970-01-01T00:00:00Z")),
                Instant.parse(root.path("validUntil").asText("1970-01-01T00:00:00Z")),
                List.copyOf(channels));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isEmpty() ? null : v.asText();
    }

    private void persist(SnapshotState snapshot) {
        try {
            Files.writeString(diskCopy, toJson(snapshot), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("DUTY_SNAPSHOT_PERSIST_FAILED path={} error={}", diskCopy,
                    String.valueOf(e));
        }
    }

    private void loadDisk() {
        if (!Files.exists(diskCopy)) {
            return;
        }
        try {
            current = parse(mapper.readTree(Files.readString(diskCopy, StandardCharsets.UTF_8)));
            log.info("DUTY_SNAPSHOT_LOADED_FROM_DISK version={} channels={}",
                    current.scheduleVersion(), current.channels().size());
        } catch (IOException | RuntimeException e) {
            log.warn("DUTY_SNAPSHOT_DISK_COPY_UNREADABLE path={} error={}", diskCopy,
                    String.valueOf(e));
        }
    }

    private String toJson(SnapshotState s) {
        ObjectNode root = mapper.createObjectNode();
        root.put("onCall", s.onCall());
        root.put("viaFallback", s.viaFallback());
        root.put("scheduleVersion", s.scheduleVersion());
        root.put("generatedAt", s.generatedAt().toString());
        root.put("validUntil", s.validUntil().toString());
        ArrayNode channels = root.putArray("channels");
        s.channels().forEach(c -> {
            ObjectNode n = channels.addObject();
            n.put("name", c.name());
            n.put("platform", c.platform());
            n.put("priority", c.priority());
            n.put("isFallback", c.fallback());
            n.put("envKeyWebhook", c.envKeyWebhook());
            n.put("envKeySecret", c.envKeySecret());
        });
        return root.toString();
    }
}
