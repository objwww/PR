package com.objwww.pr.publisher.domain.handler;

import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.fakes.TestFixtures;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.GitHubOperation;
import com.objwww.pr.shared.OutboxState;
import com.objwww.pr.shared.TypedOutcome;
import com.objwww.pr.shared.TypedReadRequest;
import com.objwww.pr.shared.TypedResponse;
import com.objwww.pr.shared.TypedWriteRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * PublishReviewHandler 翻译逻辑：body 内置隐藏 marker + commit_id 绑 head_sha（B-1 缓解）。
 */
class PublishReviewHandlerTest {

    private final PublishReviewHandler handler = new PublishReviewHandler();

    private ClaimedCommand command() {
        return TestFixtures.command(CommandType.PUBLISH_REVIEW, 1, 1, OutboxState.PENDING, 0, 3);
    }

    @Test
    void buildRequestBindsCommitAndEmbedsMarker() {
        ClaimedCommand cmd = command();
        TypedWriteRequest request = handler.buildRequest(cmd, TestFixtures.reviewPayload(cmd));

        assertEquals(GitHubOperation.CREATE_REVIEW, request.operation());
        assertEquals("octo/demo", request.repositoryFullName());
        assertEquals("0123456789abcdef0123456789abcdef01234567",
                request.parameters().get("commit_id")); // head_sha 绑定
        assertEquals(42, request.parameters().get("pr_number"));
        assertEquals("COMMENT", request.parameters().get("event"));
        String body = (String) request.parameters().get("body");
        assertTrue(body.contains(PublishReviewHandler.markerOf(cmd.operationId()))); // 隐藏 marker
        assertTrue(body.contains("src/A.java")); // findings 摘要
    }

    @Test
    void interpretCreated() {
        TypedOutcome outcome = handler.interpret(
                TypedResponse.ofObject(200, Map.of("id", 7, "html_url", "http://x/r7")));
        assertEquals(TypedOutcome.Kind.CONFIRMED, outcome.kind());
        assertEquals("7", outcome.remoteId());
    }

    @Test
    void interpret422Classification() {
        // Reviews API commit_id 与 head 不匹配 → STALE_HEAD（确定性否定，EX-02）
        assertEquals(TypedOutcome.Kind.STALE_HEAD_SUPERSEDED, handler.interpret(
                TypedResponse.ofObject(422, Map.of("message", "commit_id is not associated with the head"))).kind());
        assertEquals(TypedOutcome.Kind.FAILED_TERMINAL, handler.interpret(
                TypedResponse.ofObject(422, Map.of("message", "Body is too long"))).kind());
        assertEquals(TypedOutcome.Kind.SERVER_RETRYABLE,
                handler.interpret(TypedResponse.ofStatus(500)).kind());
    }

    @Test
    void buildProbeListsReviews() {
        ClaimedCommand cmd = command();
        TypedReadRequest probe = handler.buildProbe(cmd, TestFixtures.reviewPayload(cmd));

        assertEquals(GitHubOperation.LIST_REVIEWS, probe.operation());
        assertEquals(42, probe.parameters().get("pr_number"));
    }

    @Test
    void interpretProbeMatchesMarker() {
        ClaimedCommand cmd = command();
        TypedResponse hit = TypedResponse.ofArray(200, List.of(
                Map.of("id", 1, "body", "no marker here"),
                Map.of("id", 2, "body", "text " + PublishReviewHandler.markerOf(cmd.operationId()))));
        ProbeResult.FoundWithContent verdict = (ProbeResult.FoundWithContent) handler.interpretProbe(hit, cmd);
        assertEquals("2", verdict.remoteId());
        assertTrue(verdict.contentDigest() != null);

        assertTrue(handler.interpretProbe(
                TypedResponse.ofArray(200, List.of(Map.of("id", 1, "body", "x"))), cmd)
                instanceof ProbeResult.NotFound);
        assertTrue(handler.interpretProbe(TypedResponse.ofStatus(403), cmd)
                instanceof ProbeResult.Unknown);
    }

    /**
     * UT-25（M2 方案 §11/L1，§4.4）：digest 逐字节语义——CRLF vs LF/尾空格/Unicode 逐字节
     * 不等即漂移；marker 剥除走 NotFound（不走进 content 比对）；重复 marker（body 内两次
     * 或跨对象双命中）→ 歧义 UNKNOWN。首段断言同时钉 EX-23：期望 digest（expectedContentDigest）
     * 与探针实测 digest（interpretProbe）算法同源——同字节输入必须产出同 digest。
     */
    @Test
    void contentDigestUsesExactBytesAndMarkerAmbiguityFailsClosed() {
        ClaimedCommand cmd = command();
        Map<String, Object> payload = TestFixtures.reviewPayload(cmd);
        String expectedBody = (String) handler.buildRequest(cmd, payload).parameters().get("body");
        assertEquals(handler.expectedContentDigest(cmd, payload),
                ((ProbeResult.FoundWithContent) handler.interpretProbe(
                        TypedResponse.ofArray(200, List.of(Map.of("id", 2, "body", expectedBody))), cmd))
                        .contentDigest());

        for (String changed : List.of(expectedBody.replace("\n", "\r\n"),
                expectedBody + " ", expectedBody.replace("AI Code Review", "AI 代码评审"))) {
            ProbeResult.FoundWithContent hit = (ProbeResult.FoundWithContent) handler.interpretProbe(
                    TypedResponse.ofArray(200, List.of(Map.of("id", 2, "body", changed))), cmd);
            assertNotEquals(handler.expectedContentDigest(cmd, payload), hit.contentDigest());
        }

        String marker = PublishReviewHandler.markerOf(cmd.operationId());
        assertTrue(handler.interpretProbe(TypedResponse.ofArray(200,
                List.of(Map.of("id", 2, "body", expectedBody.replace(marker, "")))), cmd)
                instanceof ProbeResult.NotFound);
        assertTrue(handler.interpretProbe(TypedResponse.ofArray(200,
                List.of(Map.of("id", 2, "body", expectedBody + marker))), cmd)
                instanceof ProbeResult.Unknown);
        assertTrue(handler.interpretProbe(TypedResponse.ofArray(200, List.of(
                Map.of("id", 2, "body", expectedBody), Map.of("id", 3, "body", expectedBody))), cmd)
                instanceof ProbeResult.Unknown);
    }

    @Test
    void unicodeNormalizationFormsAreByteDistinct() {
        // UT-25 Unicode 补强：视觉同形的 é（NFC U+00E9 vs NFD e+U+0301 组合序列）UTF-8 字节
        // 不同 → digest 必须不等；digest 层不做任何归一化迁就（期望/实测同源于 sha256Hex）
        String nfc = "café"; // NFC
        String nfd = "café"; // NFD（与上行视觉同形）
        assertEquals(nfc, java.text.Normalizer.normalize(nfd, java.text.Normalizer.Form.NFC));
        assertNotEquals(com.objwww.pr.shared.Digests.sha256Hex(nfc),
                com.objwww.pr.shared.Digests.sha256Hex(nfd));
    }
}
