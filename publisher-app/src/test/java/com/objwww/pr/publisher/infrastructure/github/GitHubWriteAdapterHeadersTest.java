package com.objwww.pr.publisher.infrastructure.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.publisher.infrastructure.credential.CredentialBroker;
import com.objwww.pr.publisher.infrastructure.credential.TokenScope;
import com.objwww.pr.shared.GitHubOperation;
import com.objwww.pr.shared.TypedReadRequest;
import com.objwww.pr.shared.TypedResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class GitHubWriteAdapterHeadersTest {

    private WireMockServer wiremock;

    @AfterEach
    void stop() {
        if (wiremock != null) {
            wiremock.stop();
        }
    }

    @Test
    void exposesOnlyNormalizedRateLimitHeaders() {
        wiremock = new WireMockServer(wireMockConfig().dynamicPort());
        wiremock.start();
        wiremock.stubFor(get(urlEqualTo("/repos/o/r"))
                .willReturn(aResponse().withStatus(403)
                        .withHeader("Retry-After", "120")
                        .withHeader("X-RateLimit-Remaining", "0")
                        .withHeader("X-RateLimit-Reset", "1999999999")));

        CredentialBroker broker = new CredentialBroker() {
            @Override public String token(TokenScope scope) { return "test-token"; }
            @Override public String token(long installationId, TokenScope scope) { return "test-token"; }
        };
        GitHubWriteAdapter adapter = new GitHubWriteAdapter(
                broker, wiremock.baseUrl(), new ObjectMapper(), Duration.ofSeconds(2));

        TypedResponse response = adapter.executeRead(new TypedReadRequest(
                GitHubOperation.GET_REPO, "o/r", Map.of()));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.retryAfterSeconds()).isEqualTo(120L);
        assertThat(response.rateLimitRemaining()).isZero();
        assertThat(response.rateLimitResetEpochSec()).isEqualTo(1999999999L);
    }

    /**
     * EX-20 适配器边界（I23）：非法/零值/负值/过去时/超 long 溢出的 Retry-After 头
     * 一律解析为 null（= 无头语义），不崩溃不抛出；分流下限由 RetryDirective 层兜底。
     */
    @Test
    void malformedRetryAfterHeadersParseAsAbsent() {
        wiremock = new WireMockServer(wireMockConfig().dynamicPort());
        wiremock.start();
        CredentialBroker broker = new CredentialBroker() {
            @Override public String token(TokenScope scope) { return "test-token"; }
            @Override public String token(long installationId, TokenScope scope) { return "test-token"; }
        };
        GitHubWriteAdapter adapter = new GitHubWriteAdapter(
                broker, wiremock.baseUrl(), new ObjectMapper(), Duration.ofSeconds(2));

        for (String headerValue : java.util.List.of("bogus", "0", "-30",
                "Sun, 31 Aug 2025 00:00:00 GMT", "99999999999999999999")) {
            wiremock.stubFor(get(urlEqualTo("/repos/o/r"))
                    .willReturn(aResponse().withStatus(429)
                            .withHeader("Retry-After", headerValue)));

            TypedResponse response = adapter.executeRead(new TypedReadRequest(
                    GitHubOperation.GET_REPO, "o/r", Map.of()));

            assertThat(response.status()).isEqualTo(429);
            assertThat(response.retryAfterSeconds())
                    .as("Retry-After: %s 必须解析为 null（无头语义）", headerValue)
                    .isNull();
        }
    }
}
