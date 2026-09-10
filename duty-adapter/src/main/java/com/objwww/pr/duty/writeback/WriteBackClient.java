package com.objwww.pr.duty.writeback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.objwww.pr.duty.http.JsonHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 台账回写（M7-17）：webhook 发出后 best-effort POST 195 /api/duty/notifications
 * （source=GATUS）。195 不可达 → 本地 spool（JSONL 逐行），60s 重放；服务端按
 * fingerprint 去重（身份单源在 control-app）——spool 重放/事件重发皆幂等。
 *
 * <p>诚实边界（AM7 §6 残余风险）：spool 随 127 磁盘故障可丢——列表是台账面不是通知面。
 */
@Component
public class WriteBackClient {

    private static final Logger log = LoggerFactory.getLogger(WriteBackClient.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final JsonHttp http;
    private final String writebackUrl;
    private final String bearer;
    private final Path spoolFile;

    public WriteBackClient(JsonHttp http,
                           @Value("${app.writeback-url}") String writebackUrl,
                           @Value("${app.writeback-bearer}") String bearer,
                           @Value("${app.data-dir}") String dataDir) {
        this.http = http;
        this.writebackUrl = writebackUrl;
        this.bearer = bearer;
        this.spoolFile = Path.of(dataDir, "spool", "writeback.jsonl");
    }

    /** 事件回写体（control-app DutyQueryController.writeBack 契约） */
    public record Event(String eventStatus, String severity, String title, String body,
                        String groupKey, String triggeredAt,
                        java.util.Map<String, String> labels) {
    }

    public void push(Event event) {
        String json = toJson(event);
        try {
            int status = http.postJson(writebackUrl, bearer, json);
            if (status / 100 == 2) {
                return;
            }
            log.warn("DUTY_WRITEBACK_REJECTED status={} body_head={}", status, head(json));
            spool(json);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            spool(json);
        }
    }

    @Scheduled(fixedDelayString = "${app.replay-interval-ms:60000}", initialDelay = 45000)
    void replaySpool() {
        if (!Files.exists(spoolFile)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(spoolFile, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return;
            }
            List<String> remaining = new ArrayList<>();
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    int status = http.postJson(writebackUrl, bearer, line);
                    if (status / 100 != 2) {
                        remaining.add(line);
                    }
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    remaining.add(line);
                }
            }
            rewrite(remaining);
            if (remaining.size() < lines.size()) {
                log.info("DUTY_SPOOL_REPLAYED total={} drained={}",
                        lines.size(), lines.size() - remaining.size());
            }
        } catch (IOException e) {
            log.warn("DUTY_SPOOL_REPLAY_FAILED error={}", String.valueOf(e));
        }
    }

    private void spool(String json) {
        try {
            Files.createDirectories(spoolFile.getParent());
            Files.writeString(spoolFile, json + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.warn("DUTY_WRITEBACK_SPOOLED file={} lines_now={}",
                    spoolFile, countLines());
        } catch (IOException e) {
            // 双重失败（195 不可达 + 本地磁盘不可写）：只余日志面——如实记录
            log.error("DUTY_SPOOL_WRITE_FAILED file={} error={}", spoolFile,
                    String.valueOf(e));
        }
    }

    private void rewrite(List<String> remaining) throws IOException {
        Path tmp = spoolFile.resolveSibling(spoolFile.getFileName() + ".tmp");
        Files.writeString(tmp, remaining.isEmpty() ? ""
                        : String.join("\n", remaining) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(tmp, spoolFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 文件系统不支持原子换名（Windows/部分网络卷）：退化为普通替换——重放幂等兜底
            Files.move(tmp, spoolFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private long countLines() {
        // try-with-resources 必须：Files.lines 句柄不关会锁文件（Windows 实测拦后续 rewrite）
        try (java.util.stream.Stream<String> lines = Files.lines(spoolFile)) {
            return lines.count();
        } catch (IOException e) {
            return -1;
        }
    }

    private String toJson(Event e) {
        ObjectNode root = mapper.createObjectNode();
        root.put("eventStatus", e.eventStatus());
        if (e.severity() != null) {
            root.put("severity", e.severity());
        }
        root.put("title", e.title());
        root.put("body", e.body());
        root.put("groupKey", e.groupKey());
        root.put("triggeredAt", e.triggeredAt());
        ObjectNode labels = root.putObject("labels");
        e.labels().forEach((k, v) -> labels.put(k, v == null ? "" : v));
        return root.toString();
    }

    private static String head(String json) {
        return json.length() > 200 ? json.substring(0, 200) + "…" : json;
    }
}
