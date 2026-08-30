package com.objwww.pr.control.interfaces.webhook;

import com.objwww.pr.control.application.IntakeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EX-08 + 事件过滤语义（MockMvc standalone + mock IntakeService）：
 * 签名错 401 / 畸形 400 / 不处理的事件与 action 200 忽略 / 合法事件 202 并异步派发。
 */
class WebhookControllerTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String URL = "/webhooks/github";

    private final GitHubSignatureVerifier verifier = new GitHubSignatureVerifier(SECRET);
    private IntakeService intakeService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        intakeService = mock(IntakeService.class);
        mvc = MockMvcBuilders.standaloneSetup(new WebhookController(SECRET, intakeService)).build();
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

        verify(intakeService, never()).accept(any(), any());
    }

    @Test
    void malformedPayloadReturns400() throws Exception {
        byte[] body = "这不是 JSON".getBytes(StandardCharsets.UTF_8);

        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", verifier.sign(body))
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "d-2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_payload"));

        verify(intakeService, never()).accept(any(), any());
    }

    @Test
    void missingRequiredFieldReturns400() throws Exception {
        // 合法 JSON 且是要处理的事件，但缺 pull_request.head.sha
        byte[] body = """
                {"action":"opened","number":7,
                 "pull_request":{"state":"open","head":{"ref":"f"},"base":{"sha":"b","ref":"main"}},
                 "repository":{"id":12345,"full_name":"org/repo"},
                 "installation":{"id":987}}
                """.getBytes(StandardCharsets.UTF_8);

        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", verifier.sign(body))
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "d-3"))
                .andExpect(status().isBadRequest());

        verify(intakeService, never()).accept(any(), any());
    }

    @Test
    void unhandledEventTypeIgnoredWith200() throws Exception {
        byte[] body = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);

        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", verifier.sign(body))
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "d-4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));

        verify(intakeService, never()).accept(any(), any());
    }

    @Test
    void unhandledActionIgnoredWith200() throws Exception {
        byte[] body = prPayload("closed").getBytes(StandardCharsets.UTF_8);

        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", verifier.sign(body))
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "d-5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));

        verify(intakeService, never()).accept(any(), any());
    }

    @Test
    void validPullRequestOpenedAcceptedAndDispatched() throws Exception {
        byte[] body = prPayload("opened").getBytes(StandardCharsets.UTF_8);

        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", verifier.sign(body))
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "d-6"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));

        verify(intakeService).accept(any(PullRequestEvent.class), eq(body));
    }

    @Test
    void synchronizeAndReopenedAreHandled() throws Exception {
        for (String action : new String[]{"synchronize", "reopened"}) {
            byte[] body = prPayload(action).getBytes(StandardCharsets.UTF_8);
            mvc.perform(post(URL).content(body)
                            .header("X-Hub-Signature-256", verifier.sign(body))
                            .header("X-GitHub-Event", "pull_request")
                            .header("X-GitHub-Delivery", "d-" + action))
                    .andExpect(status().isAccepted());
        }
    }
}
