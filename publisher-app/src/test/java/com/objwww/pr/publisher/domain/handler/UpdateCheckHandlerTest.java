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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UpdateCheckHandler 翻译逻辑：remote_identity=CHECK_RUN_ID；404 策略 = MANUAL（§6.3）。
 */
class UpdateCheckHandlerTest {

    private final UpdateCheckHandler handler = new UpdateCheckHandler();

    private ClaimedCommand command() {
        return TestFixtures.command(CommandType.UPDATE_CHECK, 1, 1, OutboxState.PENDING, 0, 3);
    }

    @Test
    void buildRequestCarriesCheckRunId() {
        ClaimedCommand cmd = command();
        TypedWriteRequest request = handler.buildRequest(cmd, TestFixtures.updatePayload(cmd));

        assertEquals(GitHubOperation.UPDATE_CHECK_RUN, request.operation());
        assertEquals("998877", request.parameters().get("check_run_id"));
        assertEquals("neutral", request.parameters().get("conclusion"));
    }

    @Test
    void interpretOk() {
        TypedOutcome outcome = handler.interpret(
                TypedResponse.ofObject(200, Map.of("id", 998877, "html_url", "http://x/c")));
        assertEquals(TypedOutcome.Kind.CONFIRMED, outcome.kind());
        assertEquals("998877", outcome.remoteId());
    }

    @Test
    void interpret404IsManualNotRetry() {
        // 远端对象消失：M0 不自动重建
        TypedOutcome outcome = handler.interpret(TypedResponse.ofStatus(404));
        assertEquals(TypedOutcome.Kind.MANUAL, outcome.kind());
        assertEquals("REMOTE_NOT_FOUND", outcome.errorCode());
    }

    @Test
    void buildProbeGetsCheckRunById() {
        ClaimedCommand cmd = command();
        TypedReadRequest probe = handler.buildProbe(cmd, TestFixtures.updatePayload(cmd));

        assertEquals(GitHubOperation.GET_CHECK_RUN, probe.operation());
        assertEquals("998877", probe.parameters().get("check_run_id"));
    }

    @Test
    void interpretProbeBranches() {
        ClaimedCommand cmd = command();
        assertTrue(handler.interpretProbe(TypedResponse.ofObject(200, Map.of("id", 1)), cmd)
                instanceof ProbeResult.FoundNoContent);
        assertTrue(handler.interpretProbe(TypedResponse.ofStatus(404), cmd)
                instanceof ProbeResult.NotFound);
        assertTrue(handler.interpretProbe(TypedResponse.ofStatus(500), cmd)
                instanceof ProbeResult.Unknown);
    }
}
