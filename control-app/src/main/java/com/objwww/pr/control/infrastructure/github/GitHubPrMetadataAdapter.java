package com.objwww.pr.control.infrastructure.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.domain.port.CredentialTokenPort;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * GitHubPrMetadataPort 的 HTTP 实现（M1-T05）：GET /repos/{repo}/pulls/{n} 权威读
 * + GET /repos/{repo} sanity 读。token 经 CredentialTokenPort 窄接口按 installation
 * 申请，只存内存、不落日志（零凭证持有；token 只进请求头、不进异常消息与日志）。
 *
 * <p>状态码映射（与 port 契约一一对应，决策权在 application 层，本类不猜）：
 * 200 → Found；404/410 → NotFound；403 → Forbidden；429 → RateLimited（Retry-After
 * 头解析为 Duration，EX-16）；5xx/超时/网络错误 → Unavailable。
 *
 * <p>刻意不加 Spring 注解：默认 profile 空跑不装配，接线在 infrastructure/config。
 */
public class GitHubPrMetadataAdapter implements GitHubPrMetadataPort {

    private static final Logger log = LoggerFactory.getLogger(GitHubPrMetadataAdapter.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** 响应体上限：PR 元数据 JSON 正常 < 1MB，限额防异常大响应拖垮内存 */
    private static final long MAX_RESPONSE_BYTES = 4L * 1024 * 1024;

    private final CredentialTokenPort tokenPort;
    private final HttpClient http;
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public GitHubPrMetadataAdapter(CredentialTokenPort tokenPort) {
        this(tokenPort, "https://api.github.com");
    }

    /** docker 栈可注入 GitHub baseUrl（stub 模式指向 compose 内网 github-stub） */
    public GitHubPrMetadataAdapter(CredentialTokenPort tokenPort, String baseUrl) {
        this(tokenPort, HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                baseUrl);
    }

    /** 测试可注入 HttpClient / baseUrl */
    public GitHubPrMetadataAdapter(CredentialTokenPort tokenPort, HttpClient http, String baseUrl) {
        this.tokenPort = Objects.requireNonNull(tokenPort);
        this.http = Objects.requireNonNull(http);
        this.baseUrl = Objects.requireNonNull(baseUrl);
    }

    @Override
    public FetchResult fetchPullRequest(long installationId, String repoFullName, int prNumber) {
        HttpRequest request = authed(installationId,
                baseUrl + "/repos/" + repoFullName + "/pulls/" + prNumber)
                .header("Accept", "application/vnd.github+json")
                .build();
        RawResponse response = send(request);
        if (response == null) {
            return new FetchResult.Unavailable("network_error");
        }
        return switch (response.status()) {
            case 200 -> parseFound(response.body());
            case 404, 410 -> new FetchResult.NotFound();
            case 403 -> new FetchResult.Forbidden();
            case 429 -> new FetchResult.RateLimited(parseRetryAfterSeconds(response.retryAfter()));
            default -> response.status() >= 500
                    ? new FetchResult.Unavailable("http_" + response.status())
                    // 其余 4xx（如 422）：非预期客户端错误，按不可用退避而非静默
                    : new FetchResult.Unavailable("http_" + response.status());
        };
    }

    @Override
    public SanityResult checkRepoReadable(long installationId, String repoFullName) {
        HttpRequest request = authed(installationId, baseUrl + "/repos/" + repoFullName)
                .header("Accept", "application/vnd.github+json")
                .build();
        RawResponse response = send(request);
        return response != null && response.status() == 200
                ? SanityResult.READABLE : SanityResult.UNREADABLE;
    }

    private FetchResult parseFound(String body) {
        try {
            JsonNode pr = mapper.readTree(body);
            return new FetchResult.Found(
                    pr.path("state").asText("open"),
                    pr.path("draft").asBoolean(false),
                    pr.path("merged").asBoolean(false),
                    pr.path("head").path("sha").asText(),
                    pr.path("base").path("ref").asText(),
                    pr.path("base").path("sha").asText(),
                    instantOrNull(pr.get("updated_at")));
        } catch (Exception e) {
            // 200 但响应不可解析：按不可用退避（事实不明，不猜）
            log.warn("GitHub PR 元数据响应解析失败: {}", e.getMessage());
            return new FetchResult.Unavailable("unparseable_response");
        }
    }

    /** ISO-8601 宽松解析：缺失/非法 → null（EX-18：不猜，水印推进随之跳过） */
    private static Instant instantOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return Instant.parse(node.asText());
        } catch (Exception e) {
            return null;
        }
    }

    /** Retry-After 秒数头 → Duration；缺头/非法/非正值 → null（EX-16：有则尊重，无则由调用方退避） */
    static Duration parseRetryAfterSeconds(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(headerValue.trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            return null; // HTTP-date 形式暂不解析，退避由调用方兜底
        }
    }

    private HttpRequest.Builder authed(long installationId, String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + tokenPort.requestReadOnlyToken(installationId))
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET();
    }

    /** 发送并限额读尽响应体；超时/IO 错误 → null（由调用方归类 Unavailable） */
    private RawResponse send(HttpRequest request) {
        try {
            HttpResponse<InputStream> raw = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body = readLimited(raw.body());
            return new RawResponse(raw.statusCode(),
                    raw.headers().firstValue("Retry-After").orElse(null),
                    new String(body, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("GitHub 元数据读请求失败 {}: {}", request.uri().getPath(), e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("GitHub 元数据读请求被中断 {}", request.uri().getPath());
            return null;
        }
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        try (in) {
            byte[] chunk = new byte[64 * 1024];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int n;
            long total = 0;
            while ((n = in.read(chunk)) != -1) {
                total += n;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("GitHub 元数据响应超过上限 " + MAX_RESPONSE_BYTES + " 字节");
                }
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** 已读尽的响应快照（状态码 + Retry-After 头 + 文本体） */
    private record RawResponse(int status, String retryAfter, String body) {
    }
}
