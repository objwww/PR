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
        ReconcileVerdict verdict = handler.interpretProbe(hit, cmd);
        assertEquals(ReconcileVerdict.Kind.FOUND, verdict.kind());
        assertEquals("2", verdict.remoteId());

        assertEquals(ReconcileVerdict.Kind.NOT_FOUND, handler.interpretProbe(
                TypedResponse.ofArray(200, List.of(Map.of("id", 1, "body", "x"))), cmd).kind());
        assertEquals(ReconcileVerdict.Kind.UNKNOWN,
                handler.interpretProbe(TypedResponse.ofStatus(403), cmd).kind());
    }
}
