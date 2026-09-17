package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.application.ReplayScenarioDriver;
import com.objwww.pr.shared.Digest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ReplayScenarioDriver.FrozenPayloadReader} 的 Postgres 实现（P2；V138
 * eval_app 只读授权）：LIKE 粗筛含目标 alertname 的最近组载荷（上限 100 行），
 * Java 侧按 AM 协议精验——labels.alertname 精确等于目标 **且** status=firing
 * 才算回放锚；按 payload_digest 去重后取第 roundNo 个（P3 修正：同载荷重投
 * 的事故材料哈希相同，管线按"无新证据不重查"正确拒绝——多轮回放必须轮换
 * 不同历史载荷）。原文（payload_raw bytea）整组返回：重投即整组重放，零改写。
 */
public class PostgresFrozenPayloadReader implements ReplayScenarioDriver.FrozenPayloadReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcClient jdbc;

    public PostgresFrozenPayloadReader(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Optional<FrozenPayload> firingFor(String alertname, int roundNo) {
        if (alertname == null || alertname.isBlank() || roundNo < 1) {
            return Optional.empty();
        }
        List<byte[]> candidates = jdbc.sql("""
                        select payload_raw from alert_inbox
                         where state in ('PROCESSED', 'IGNORED', 'RECEIVED')
                           and convert_from(payload_raw, 'UTF8') like :pattern
                         order by received_at desc
                         limit 100
                        """)
                .param("pattern", "%\"alertname\":\"" + alertname + "\"%")
                .query((rs, i) -> rs.getBytes("payload_raw"))
                .list();
        // digest 去重保持倒序（同字节多次重投只算一个历史材料）
        Map<String, byte[]> distinct = new LinkedHashMap<>();
        for (byte[] body : candidates) {
            if (!containsFiringAlert(body, alertname)) {
                continue;
            }
            String digest = Digest.sha256Of(new String(body, StandardCharsets.UTF_8)).value();
            distinct.putIfAbsent(digest, body);
        }
        List<Map.Entry<String, byte[]>> ordered = new ArrayList<>(distinct.entrySet());
        if (ordered.size() < roundNo) {
            return Optional.empty();
        }
        Map.Entry<String, byte[]> chosen = ordered.get(roundNo - 1);
        return Optional.of(new FrozenPayload(chosen.getValue(), chosen.getKey()));
    }

    /** AM 协议精验：alerts[] 内存在 labels.alertname == 目标 且 status == firing 的条目 */
    private static boolean containsFiringAlert(byte[] body, String alertname) {
        JsonNode root;
        try {
            root = MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
        JsonNode alerts = root.path("alerts");
        if (!alerts.isArray()) {
            return false;
        }
        for (JsonNode alert : alerts) {
            String name = alert.path("labels").path("alertname").asText(null);
            String status = alert.path("status").asText(null);
            if (alertname.equals(name) && "firing".equals(status)) {
                return true;
            }
        }
        return false;
    }
}
