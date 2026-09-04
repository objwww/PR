package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.AlertIntakeLimits;
import com.objwww.pr.control.alert.application.AlertIntakeService;
import com.objwww.pr.control.alert.domain.model.InboxState;
import com.objwww.pr.control.alert.domain.repository.AlertInboxRepository;
import com.objwww.pr.control.alert.domain.model.AlertInbox;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L4 入口边界（EX-A01~A03/A09/A10；§6.4 四类状态码语义，standalone MockMvc + InMemory fake）。
 * 真 PG 落库路径由 CT/DP 阶段覆盖。
 */
class AlertWebhookControllerTest {

    private static final String BEARER = "test-bearer-token";
    private static final Instant FIXED = Instant.parse("2026-09-03T10:00:00Z");

    private AlertInMemoryStores stores;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        stores = new AlertInMemoryStores();
        mvc = build(stores.inbox);
    }

    private MockMvc build(AlertInboxRepository inbox) {
        AlertIntakeService intake = new AlertIntakeService(inbox, AlertIntakeLimits.defaults(),
                () -> FIXED);
        return MockMvcBuilders.standaloneSetup(new AlertWebhookController(intake, BEARER)).build();
    }

    private static String validBody() {
        return """
                {
                  "version": "4",
                  "receiver": "control-app",
                  "groupKey": "g:HighErrorRate:checkout",
                  "groupLabels": {"alertname": "HighErrorRate"},
                  "commonLabels": {"alertname": "HighErrorRate", "service": "checkout", "severity": "critical"},
                  "commonAnnotations": {"summary": "错误率超阈值"},
                  "externalURL": "http://am.local",
                  "status": "firing",
                  "alerts": [
                    {
                      "status": "firing",
                      "labels": {"alertname": "HighErrorRate", "service": "checkout", "severity": "critical"},
                      "annotations": {"summary": "错误率超阈值", "runbook": "rb-1"},
                      "startsAt": "2026-09-03T09:00:00Z",
                      "endsAt": "0001-01-01T00:00:00Z",
                      "generatorURL": "http://prom.local/graph",
                      "fingerprint": "fp-checkout-1"
                    }
                  ],
                  "truncatedAlerts": 0
                }
                """;
    }

    // ---------------- EX 前置：合法整组 → 202 落库 ----------------

    @Test
    void validGroupIsAcceptedAndPersisted() throws Exception {
        mvc.perform(post("/webhooks/alertmanager")
                        .header("Authorization", "Bearer " + BEARER)
                        .contentType("application/json")
                        .content(validBody()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));

        assertThat(stores.inbox.all()).hasSize(1);
        AlertInbox row = stores.inbox.all().get(0);
        assertThat(row.state()).isEqualTo(InboxState.RECEIVED);
        assertThat(row.envelope().groupKey()).isEqualTo("g:HighErrorRate:checkout");
        assertThat(row.envelope().alertCount()).isEqualTo(1);
        assertThat(row.envelope().payloadDigest().value()).hasSize(64);
    }

    // ---------------- EX-A01：伪 bearer → 401 零落库 ----------------

    @Test
    void exA01ForgedBearerIs401WithZeroPersistence() throws Exception {
        mvc.perform(post("/webhooks/alertmanager")
                        .header("Authorization", "Bearer wrong-token")
                        .contentType("application/json")
                        .content(validBody()))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/webhooks/alertmanager")
                        .contentType("application/json")
                        .content(validBody()))
                .andExpect(status().isUnauthorized());

        assertThat(stores.inbox.all()).isEmpty();   // INV-AM1-1：未验签零落库
    }

    // ---------------- EX-A02：畸形 JSON → 400 零落库 ----------------

    @Test
    void exA02MalformedJsonIs400WithZeroPersistence() throws Exception {
        mvc.perform(post("/webhooks/alertmanager")
                        .header("Authorization", "Bearer " + BEARER)
                        .contentType("application/json")
                        .content("{\"version\":\"4\",\"receiver\":"))
                .andExpect(status().isBadRequest());

        // 缺 envelope 必填字段的合法 JSON 同样 400
        mvc.perform(post("/webhooks/alertmanager")
                        .header("Authorization", "Bearer " + BEARER)
                        .contentType("application/json")
                        .content("{\"version\":\"4\"}"))
                .andExpect(status().isBadRequest());

        assertThat(stores.inbox.all()).isEmpty();
    }

    // ---------------- EX-A03：超大/超条/超长/超深/gzip 炸弹 ----------------

    @Test
    void exA03OversizeBodyIs413() throws Exception {
        AlertIntakeLimits tight = new AlertIntakeLimits(200, 200, 2_000, 32_000, 32, 2 * 1024 * 1024);
        AlertIntakeService intake = new AlertIntakeService(stores.inbox, tight, () -> FIXED);
        MockMvc tightMvc = MockMvcBuilders
                .standaloneSetup(new AlertWebhookController(intake, BEARER)).build();

        tightMvc.perform(post("/webhooks/alertmanager")
                        .header("Authorization", "Bearer " + BEARER)
                        .contentType("application/json")
                        .content(validBody()))
                .andExpect(status().isPayloadTooLarge());

        assertThat(stores.inbox.all()).isEmpty();
    }

    @Test
    void exA03TooManyAlertsIs413() throws Exception {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 201; i++) {
            if (i > 0) {
                many.append(',');
            }
            many.append("{\"status\":\"firing\",\"labels\":{\"alertname\":\"A\"},\"annotations\":{},")
                    .append("\"startsAt\":\"2026-09-03T09:00:00Z\",\"fingerprint\":\"fp-").append(i).append("\"}");
        }
        String body = "{\"version\":\"4\",\"receiver\":\"r\",\"groupKey\":\"g\",\"status\":\"firing\","
                + "\"alerts\":[" + many + "]}";

        mvc.perform(post("/webhooks/alertmanager")
                        .header("Authorization", "Bearer " + BEARER)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isPayloadTooLarge());
        assertThat(stores.inbox.all()).isEmpty();
    }

    @Test
    void exA03OverlongAnnotationIs400() throws Exception {
        String body = validBody().replace("\"runbook\": \"rb-1\"",
                "\"runbook\": \"" + "x".repeat(3_000) + "\"");
        mvc.perform(post("/webhooks/alertmanager")
                        .header("Authorization", "Bearer " + BEARER)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
        assertThat(stores.inbox.all()).isEmpty();
    }

    @Test
    void exA03DeepNestingIsRejected() throws Exception {
        AlertIntakeLimits shallow = new AlertIntakeLimits(512 * 1024, 200, 2_000, 32_000, 8,
                2 * 1024 * 1024);
        AlertIntakeService intake = new AlertIntakeService(stores.inbox, shallow, () -> FIXED);
        MockMvc shallowMvc = MockMvcBuilders
                .standaloneSetup(new AlertWebhookController(intake, BEARER)).build();

        String deep = "{\"version\":\"4\",\"receiver\":\"r\",\"groupKey\":\"g\",\"status\":\"firing\","
                + "\"deep\":" + "[".repeat(64) + "]".repeat(64) + ",\"alerts\":[]}";
        shallowMvc.perform(post("/webhooks/alertmanager")
                        .header("Authorization", "Bearer " + BEARER)
                        .contentType("application/json")
                        .content(deep))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exA03GzipBombIs413() throws Exception {
        String padded = validBody().replace("\"runbook\": \"rb-1\"",
                "\"runbook\": \"" + "y".repeat(64 * 1024) + "\"");
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(padded.getBytes(StandardCharsets.UTF_8));
        }
        // 压缩后体积小于 body 上限，解压后 64KB 也小于 gzip 上限 → 用紧口径验证解压门
        AlertIntakeLimits tight = new AlertIntakeLimits(512 * 1024, 200, 2_000, 32_000, 32, 16 * 1024);
        AlertIntakeService intake = new AlertIntakeService(stores.inbox, tight, () -> FIXED);
        MockMvc tightMvc = MockMvcBuilders
                .standaloneSetup(new AlertWebhookController(intake, BEARER)).build();

        tightMvc.perform(post("/webhooks/alertmanager")
                        .header("Authorization", "Bearer " + BEARER)
                        .header("Content-Encoding", "gzip")
                        .contentType("application/json")
                        .content(gz.toByteArray()))
                .andExpect(status().isPayloadTooLarge());
        assertThat(stores.inbox.all()).isEmpty();
    }

    @Test
    void gzipEncodedValidBodyIsAccepted() throws Exception {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(validBody().getBytes(StandardCharsets.UTF_8));
        }
        mvc.perform(post("/webhooks/alertmanager")
                        .header("Authorization", "Bearer " + BEARER)
                        .header("Content-Encoding", "gzip")
                        .contentType("application/json")
                        .content(gz.toByteArray()))
                .andExpect(status().isAccepted());
        assertThat(stores.inbox.all()).hasSize(1);
    }

    // ---------------- EX-A09：DB 故障 → 503 整组可重试 ----------------

    @Test
    void exA09DbFailureIs503() throws Exception {
        AlertInboxRepository broken = new AlertInboxRepository() {
            @Override
            public void insert(AlertInbox row) {
                throw new DataAccessResourceFailureException("db down");
            }

            @Override
            public Optional<AlertInbox> claimNext(String owner, java.time.Instant now,
                                                  java.time.Duration lease) {
                return Optional.empty();
            }

            @Override
            public boolean complete(UUID id, long leaseEpoch,
                    com.objwww.pr.control.alert.domain.model.InboxDecision decision,
                    java.time.Instant now) {
                return false;
            }

            @Override
            public boolean scheduleRetry(UUID id, long leaseEpoch,
                    com.objwww.pr.control.alert.domain.model.InboxDecision decision,
                    String lastError,
                    java.time.Instant nextRetryAt, java.time.Instant now) {
                return false;
            }

            @Override
            public boolean markDeadLetter(UUID id, long leaseEpoch, String lastError,
                                          java.time.Instant now) {
                return false;
            }

            @Override
            public boolean markIgnored(UUID id, long leaseEpoch, java.time.Instant now) {
                return false;
            }

            @Override
            public long reclaimExpired(java.time.Instant now) {
                return 0;
            }

            @Override
            public Optional<AlertInbox> findById(UUID id) {
                return Optional.empty();
            }
        };
        MockMvc brokenMvc = build(broken);

        brokenMvc.perform(post("/webhooks/alertmanager")
                        .header("Authorization", "Bearer " + BEARER)
                        .contentType("application/json")
                        .content(validBody()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("storage unavailable"));
    }

    // ---------------- EX-A10：alerts[] 空组 → 202 + IGNORED 行 ----------------

    @Test
    void exA10EmptyAlertsGroupIsIgnored() throws Exception {
        String emptyGroup = """
                {
                  "version": "4", "receiver": "control-app",
                  "groupKey": "g:HighErrorRate:checkout",
                  "groupLabels": {}, "commonLabels": {}, "commonAnnotations": {},
                  "status": "firing", "alerts": [], "truncatedAlerts": 0
                }
                """;

        mvc.perform(post("/webhooks/alertmanager")
                        .header("Authorization", "Bearer " + BEARER)
                        .contentType("application/json")
                        .content(emptyGroup))
                .andExpect(status().isAccepted());

        assertThat(stores.inbox.all()).hasSize(1);
        assertThat(stores.inbox.all().get(0).state()).isEqualTo(InboxState.IGNORED);
    }
}
