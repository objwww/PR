package com.objwww.pr.control.interfaces.webhook;

import com.objwww.pr.control.domain.model.InboxState;
import com.objwww.pr.control.domain.model.WebhookInbox;
import com.objwww.pr.control.domain.repository.WebhookInboxRepository;
import com.objwww.pr.shared.Digests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1-T03 入口两段式语义（MockMvc standalone + mock WebhookInboxRepository）：
 * 401 不动；一切签名合法事件落 inbox 后 202（含畸形 JSON，E2E-22；含非处理事件，ST-16）；
 * 重投按原行 digest/state 应答（I9/I13/I16）；异 digest 409（EX-13）。
 *
 * <p>行为变更（相对 M0 EX-08）：畸形 JSON 400 → 202 + payload_json NULL 落库审计；
 * 非处理事件 200 ignored → 202 + RECEIVED 行（过滤后移 Processor）；缺必需字段 400 取消。
 */
class WebhookControllerTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String URL = "/webhooks/github";

    private final GitHubSignatureVerifier verifier = new GitHubSignatureVerifier(SECRET);
    private WebhookInboxRepository inbox;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        inbox = mock(WebhookInboxRepository.class);
        when(inbox.insertNew(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(new WebhookController(SECRET, inbox)).build();
    }

    private static String prPayload(String action) {
        return """
                {
                  "action": "%s",
                  "number": 7,
                  "pull_request": {
                    "state": "open",
                    "draft": false,
                    "merged": false,
                    "head": {"sha": "headsha123", "ref": "feature"},
                    "base": {"sha": "basesha456", "ref": "main"}
                  },
                  "repository": {"id": 12345, "full_name": "org/repo"},
                  "installation": {"id": 987}
                }
                """.formatted(action);
    }

    /** 构造一行既有 inbox 记录（重投冲突时 findByDeliveryId 的返回） */
    private static WebhookInbox row(String deliveryId, InboxState state, String payloadDigest) {
        return new WebhookInbox(deliveryId, "pull_request", "opened", 987L, 12345L, payloadDigest,
                state, null, null, 0, 0, 5, null, null,
                Instant.now(), Instant.now(), null);
    }

    @Test
    void invalidSignatureReturns401() throws Exception {
        byte[] body = prPayload("opened").getBytes(StandardCharsets.UTF_8);

        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", "sha256=" + "0".repeat(64))
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "d-1"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post(URL).content(body) // 缺签名头同样 401
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "d-1"))
                .andExpect(status().isUnauthorized());

        // 401 不落库（EX-08 不动）
        verify(inbox, never()).insertNew(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingDeliveryHeaderReturns400() throws Exception {
        byte[] body = prPayload("opened").getBytes(StandardCharsets.UTF_8);

        // delivery_id 是 inbox 主键：缺失则无从落库，400（不静默吞掉）
        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", verifier.sign(body))
                        .header("X-GitHub-Event", "pull_request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_delivery"));

        verify(inbox, never()).insertNew(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void malformedJsonAccepted202WithNullPayloadJson() throws Exception {
        // E2E-22：合法签名 + 畸形 JSON → 202 受理，payload_json/action 置 NULL，raw 落库审计
        byte[] body = "这不是 JSON".getBytes(StandardCharsets.UTF_8);

        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", verifier.sign(body))
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "d-2"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));

        verify(inbox).insertNew(eq("d-2"), eq("pull_request"), isNull(),
                isNull(), isNull(), eq(body), isNull(), eq(Digests.sha256Hex(body)));
    }

    @Test
    void unhandledEventTypeStillAcceptedAndRecorded() throws Exception {
        // ST-16/INC-16：非处理事件不再 200 无声忽略，签名合法即落 inbox（过滤后移 Processor）
        byte[] body = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);

        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", verifier.sign(body))
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "d-4"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));

        verify(inbox).insertNew(eq("d-4"), eq("push"), eq("opened"),
                isNull(), isNull(), eq(body), eq(new String(body, StandardCharsets.UTF_8)),
                eq(Digests.sha256Hex(body)));
    }

    @Test
    void validPullRequestAcceptedWithEntryMeta() throws Exception {
        byte[] body = prPayload("opened").getBytes(StandardCharsets.UTF_8);

        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", verifier.sign(body))
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "d-6"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));

        // 入口只提取落库元数据：action/installation/repository + raw + json + digest
        verify(inbox).insertNew(eq("d-6"), eq("pull_request"), eq("opened"),
                eq(987L), eq(12345L), eq(body), eq(new String(body, StandardCharsets.UTF_8)),
                eq(Digests.sha256Hex(body)));
    }

    @Test
    void redeliveryWithSameDigestReplaysOriginalOutcome() throws Exception {
        byte[] body = prPayload("opened").getBytes(StandardCharsets.UTF_8);
        String digest = Digests.sha256Hex(body);
        when(inbox.insertNew(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false); // 主键冲突 = 重投

        // PROCESSED → 200 duplicate（I9：回放原结果，不重派）
        when(inbox.findByDeliveryId("d-p")).thenReturn(Optional.of(row("d-p", InboxState.PROCESSED, digest)));
        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", verifier.sign(body))
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "d-p"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));

        // IGNORED → 200 duplicate（同样已有终态结论）
        when(inbox.findByDeliveryId("d-i")).thenReturn(Optional.of(row("d-i", InboxState.IGNORED, digest)));
        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", verifier.sign(body))
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "d-i"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));

        // 在途三态 → 202 processing
        for (InboxState state : new InboxState[]{InboxState.RECEIVED, InboxState.PROCESSING, InboxState.RETRY_WAIT}) {
            String id = "d-" + state;
            when(inbox.findByDeliveryId(id)).thenReturn(Optional.of(row(id, state, digest)));
            mvc.perform(post(URL).content(body)
                            .header("X-Hub-Signature-256", verifier.sign(body))
                            .header("X-GitHub-Event", "pull_request")
                            .header("X-GitHub-Delivery", id))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("processing"));
        }

        // DEAD_LETTER → 200 dead_letter，不唤醒（I16）
        when(inbox.findByDeliveryId("d-dl")).thenReturn(Optional.of(row("d-dl", InboxState.DEAD_LETTER, digest)));
        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", verifier.sign(body))
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "d-dl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("dead_letter"));
    }

    @Test
    void redeliveryWithDifferentDigestReturns409() throws Exception {
        // EX-13：同 delivery 异 digest = 重放/篡改嫌疑 → 409 + 安全告警，原行不覆盖
        byte[] body = prPayload("opened").getBytes(StandardCharsets.UTF_8);
        when(inbox.insertNew(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);
        when(inbox.findByDeliveryId("d-t")).thenReturn(Optional.of(
                row("d-t", InboxState.PROCESSED, Digests.sha256Hex("别的内容"))));

        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", verifier.sign(body))
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "d-t"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("digest_mismatch"));
    }
}
