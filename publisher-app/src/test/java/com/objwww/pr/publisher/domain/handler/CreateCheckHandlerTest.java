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
 * CreateCheckHandler 翻译逻辑：请求装配 / 响应解释 / probe 构造（纯函数，不触网）。
 */
class CreateCheckHandlerTest {

    private final CreateCheckHandler handler = new CreateCheckHandler();

    private ClaimedCommand command() {
        return TestFixtures.command(CommandType.CREATE_CHECK, 1, 1, OutboxState.PENDING, 0, 3);
    }

    @Test
    void buildRequestAssemblesTypedCheckRun() {
        ClaimedCommand cmd = command();
        TypedWriteRequest request = handler.buildRequest(cmd, TestFixtures.checkPayload(cmd));

        assertEquals(GitHubOperation.CREATE_CHECK_RUN, request.operation());
        assertEquals("octo/demo", request.repositoryFullName());
        assertEquals(cmd.operationId().toString(), request.parameters().get("external_id"));
        assertEquals("ai-code-review", request.parameters().get("name"));
        assertEquals("completed", request.parameters().get("status"));
        // conclusion 映射：finding_count=2 → neutral
        assertEquals("neutral", request.parameters().get("conclusion"));
    }

    @Test
    void zeroFindingsMapsToSuccess() {
        ClaimedCommand cmd = command();
        Map<String, Object> payload = TestFixtures.checkPayload(cmd);
        payload.put("finding_count", 0);
        assertEquals("success", handler.buildRequest(cmd, payload).parameters().get("conclusion"));
    }

    @Test
    void interpretCreated() {
        TypedOutcome outcome = handler.interpret(
                TypedResponse.ofObject(201, Map.of("id", 42, "html_url", "http://x/42")));
        assertEquals(TypedOutcome.Kind.CONFIRMED, outcome.kind());
        assertEquals("42", outcome.remoteId());
        assertEquals("http://x/42", outcome.remoteUrl());
    }

    @Test
    void interpretClassificationTable() {
        assertEquals(TypedOutcome.Kind.SERVER_RETRYABLE,
                handler.interpret(TypedResponse.ofStatus(502)).kind());
        assertEquals(TypedOutcome.Kind.AUTH_FAILED,
                handler.interpret(TypedResponse.ofStatus(401)).kind());
        assertEquals(TypedOutcome.Kind.AUTH_FAILED,
                handler.interpret(TypedResponse.ofStatus(403)).kind());
        // 422 head 不匹配类 → STALE_HEAD；其他 422 → FAILED_TERMINAL（EX-02）
        assertEquals(TypedOutcome.Kind.STALE_HEAD_SUPERSEDED, handler.interpret(
                TypedResponse.ofObject(422, Map.of("message", "No commit found for SHA: abc"))).kind());
        assertEquals(TypedOutcome.Kind.FAILED_TERMINAL, handler.interpret(
                TypedResponse.ofObject(422, Map.of("message", "Invalid parameter: name"))).kind());
    }

    @Test
    void buildProbeListsChecksForHeadSha() {
        ClaimedCommand cmd = command();
        TypedReadRequest probe = handler.buildProbe(cmd, TestFixtures.checkPayload(cmd));

        assertEquals(GitHubOperation.LIST_CHECKS_FOR_SHA, probe.operation());
        assertEquals("0123456789abcdef0123456789abcdef01234567", probe.parameters().get("sha"));
        assertEquals(100, probe.parameters().get("per_page"));
    }

    @Test
    void interpretProbeMatchesExternalId() {
        ClaimedCommand cmd = command();
        TypedResponse hit = TypedResponse.ofObject(200, Map.of("check_runs", List.of(
                Map.of("id", 9, "external_id", cmd.operationId().toString()))));
        assertTrue(handler.interpretProbe(hit, cmd) instanceof ProbeResult.FoundNoContent);

        TypedResponse miss = TypedResponse.ofObject(200, Map.of("check_runs",
                List.of(Map.of("id", 9, "external_id", "someone-else"))));
        assertTrue(handler.interpretProbe(miss, cmd) instanceof ProbeResult.NotFound);

        assertTrue(handler.interpretProbe(TypedResponse.ofStatus(500), cmd)
                instanceof ProbeResult.Unknown);
    }

    @Test
    void interpretProbeAmbiguousExternalIdIsUnknown() {
        // EX-27：同 SHA 下两个 check run 撞同一 external_id = 多对象歧义，
        // 绝不认领首个命中，fail-closed 归 UNKNOWN
        ClaimedCommand cmd = command();
        TypedResponse ambiguous = TypedResponse.ofObject(200, Map.of("check_runs", List.of(
                Map.of("id", 9, "external_id", cmd.operationId().toString()),
                Map.of("id", 10, "external_id", cmd.operationId().toString()))));
        assertTrue(handler.interpretProbe(ambiguous, cmd) instanceof ProbeResult.Unknown);
    }

    @Test
    void resourceIdentity() {
        assertEquals(CommandType.CREATE_CHECK, handler.commandType());
        assertTrue(CreateCheckHandler.ALLOWED_CHECK_NAMES.contains("ai-code-review"));
    }
}
