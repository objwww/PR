package com.objwww.pr.control.infrastructure.github;

import com.objwww.pr.control.domain.port.CredentialTokenPort;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort.FetchResult;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort.SanityResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GitHubPrMetadataAdapter 单测（mock HttpClient）：状态码 → 结果类型映射逐格
 * （200 解析 / 404 / 403 / 429+Retry-After / 5xx / 网络异常），sanity 读两态。
 * 防什么：把 404/403/429/5xx 吞成同一个"失败"——五种状态是五种运维事实，路由决策各异。
 */
class GitHubPrMetadataAdapterTest {

    private HttpClient http;
    private GitHubPrMetadataAdapter adapter;

    @BeforeEach
    void setUp() {
        http = mock(HttpClient.class);
        CredentialTokenPort tokenPort = installationId -> "test-token";
        adapter = new GitHubPrMetadataAdapter(tokenPort, http, "http://stub.local");
    }

    @SuppressWarnings("unchecked")
    private void givenResponse(int status, String body, Map<String, List<String>> headers)
            throws Exception {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (a, b) -> true));
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    private void givenResponse(int status, String body) throws Exception {
        givenResponse(status, body, Map.of());
    }

    @Test
    void ok200ParsesFound() throws Exception {
        givenResponse(200, """
                {"state":"open","draft":false,"merged":false,
                 "updated_at":"2025-06-01T12:00:00Z",
                 "head":{"sha":"headabc","ref":"feature"},
                 "base":{"sha":"baseabc","ref":"main"}}
                """);

        FetchResult result = adapter.fetchPullRequest(77L, "org/repo", 7);

        assertThat(result).isEqualTo(new FetchResult.Found("open", false, false,
                "headabc", "main", "baseabc", Instant.parse("2025-06-01T12:00:00Z")));
    }

    @Test
    void notFound404() throws Exception {
        givenResponse(404, "{\"message\":\"Not Found\"}");

        assertThat(adapter.fetchPullRequest(77L, "org/repo", 7))
                .isInstanceOf(FetchResult.NotFound.class);
    }

    @Test
    void forbidden403() throws Exception {
        givenResponse(403, "{\"message\":\"Forbidden\"}");

        assertThat(adapter.fetchPullRequest(77L, "org/repo", 7))
                .isInstanceOf(FetchResult.Forbidden.class);
    }

    @Test
    void rateLimited429CarriesRetryAfter() throws Exception {
        // EX-16：Retry-After 秒数头必须解析透出
        givenResponse(429, "{\"message\":\"rate limit\"}", Map.of("Retry-After", List.of("120")));

        FetchResult result = adapter.fetchPullRequest(77L, "org/repo", 7);

        assertThat(result).isEqualTo(new FetchResult.RateLimited(Duration.ofSeconds(120)));
    }

    @Test
    void rateLimited429WithoutHeaderHasNullRetryAfter() throws Exception {
        givenResponse(429, "{}");

        assertThat(adapter.fetchPullRequest(77L, "org/repo", 7))
                .isEqualTo(new FetchResult.RateLimited(null));
    }

    @Test
    void serverError5xxIsUnavailable() throws Exception {
        givenResponse(502, "bad gateway");

        assertThat(adapter.fetchPullRequest(77L, "org/repo", 7))
                .isEqualTo(new FetchResult.Unavailable("http_502"));
    }

    @Test
    void networkErrorIsUnavailable() throws Exception {
        when(http.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("connection reset"));

        assertThat(adapter.fetchPullRequest(77L, "org/repo", 7))
                .isEqualTo(new FetchResult.Unavailable("network_error"));
    }

    @Test
    void unparseable200IsUnavailableNotGuessed() throws Exception {
        // 200 但 body 不是预期结构：事实不明 → Unavailable，不猜（EX-18 精神）
        givenResponse(200, "<html>oops</html>");

        assertThat(adapter.fetchPullRequest(77L, "org/repo", 7))
                .isEqualTo(new FetchResult.Unavailable("unparseable_response"));
    }

    @Test
    void sanityReadDistinguishesReadableFromNot() throws Exception {
        givenResponse(200, "{\"id\":12345}");
        assertThat(adapter.checkRepoReadable(77L, "org/repo")).isEqualTo(SanityResult.READABLE);

        givenResponse(404, "{}");
        assertThat(adapter.checkRepoReadable(77L, "org/repo")).isEqualTo(SanityResult.UNREADABLE);

        when(http.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("timeout"));
        assertThat(adapter.checkRepoReadable(77L, "org/repo")).isEqualTo(SanityResult.UNREADABLE);
    }
}
