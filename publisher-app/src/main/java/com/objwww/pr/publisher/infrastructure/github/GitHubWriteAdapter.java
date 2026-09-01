package com.objwww.pr.publisher.infrastructure.github;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.publisher.infrastructure.credential.CredentialBroker;
import com.objwww.pr.publisher.infrastructure.credential.TokenScope;
import com.objwww.pr.shared.GitHubOperation;
import com.objwww.pr.shared.RetryAfterParser;
import com.objwww.pr.shared.TypedReadRequest;
import com.objwww.pr.shared.TypedResponse;
import com.objwww.pr.shared.TypedWriteRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * GitHub 唯一写出口（B27）：全代码库唯一持有写 token 的 HTTP 客户端，也承载 reconcile
 * 探测读。只被 FencedPublicationExecutor 引用（I4/AFT-07 结构封死）。
 *
 * <p>封闭契约：方法签名只接受 {@link TypedWriteRequest}/{@link TypedReadRequest}
 * （AFT-04：操作枚举 + 仓库 + 参数）；HTTP 动词与路径的拼装——即 raw url/method——
 * 只存在于本类内部。
 *
 * <p>纪律：HTTP 状态码不抛异常（归 Handler 解释）；只有传输层失败（超时/连接断/
 * 响应丢失）抛 {@link GitHubTransportException}，由执行器归 OUTCOME_UNKNOWN（EX-03）。
 */
public class GitHubWriteAdapter {

    private static final String API_VERSION = "2022-11-28";

    private final CredentialBroker credentialBroker;
    private final String apiBaseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public GitHubWriteAdapter(CredentialBroker credentialBroker, String apiBaseUrl,
                              ObjectMapper objectMapper, Duration requestTimeout) {
        this.credentialBroker = credentialBroker; // 允许 null：测试子类整体覆写 execute，不触网
        this.apiBaseUrl = apiBaseUrl;
        this.objectMapper = objectMapper;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .build();
    }

    // ---------- 写 ----------

    public TypedResponse execute(TypedWriteRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(credentialBroker, "credentialBroker");
        Map<String, Object> params = request.parameters();
        return switch (request.operation()) {
            case CREATE_CHECK_RUN -> send("POST",
                    "/repos/" + request.repositoryFullName() + "/check-runs",
                    params, TokenScope.CHECKS_WRITE);
            case UPDATE_CHECK_RUN -> send("PATCH",
                    "/repos/" + request.repositoryFullName()
                            + "/check-runs/" + required(params, "check_run_id"),
                    without(params, "check_run_id"), TokenScope.CHECKS_WRITE);
            case CREATE_REVIEW -> send("POST",
                    "/repos/" + request.repositoryFullName()
                            + "/pulls/" + required(params, "pr_number") + "/reviews",
                    without(params, "pr_number"), TokenScope.PULL_REQUESTS_WRITE);
            default -> throw new IllegalArgumentException("非写操作: " + request.operation());
        };
    }

    // ---------- 探测读（reconcile） ----------

    public TypedResponse executeRead(TypedReadRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(credentialBroker, "credentialBroker");
        Map<String, Object> params = request.parameters();
        return switch (request.operation()) {
            case GET_CHECK_RUN -> send("GET",
                    "/repos/" + request.repositoryFullName()
                            + "/check-runs/" + required(params, "check_run_id"),
                    null, TokenScope.READ);
            case LIST_CHECKS_FOR_SHA -> send("GET",
                    "/repos/" + request.repositoryFullName()
                            + "/commits/" + required(params, "sha") + "/check-runs"
                            + paging(params),
                    null, TokenScope.READ);
            case LIST_REVIEWS -> send("GET",
                    "/repos/" + request.repositoryFullName()
                            + "/pulls/" + required(params, "pr_number") + "/reviews"
                            + paging(params),
                    null, TokenScope.READ);
            case GET_REPO -> send("GET",
                    "/repos/" + request.repositoryFullName(),
                    null, TokenScope.READ);
            default -> throw new IllegalArgumentException("非读操作: " + request.operation());
        };
    }

    // ---------- 内部：raw HTTP 的唯一存在地 ----------

    private TypedResponse send(String method, String pathAndQuery, Map<String, Object> jsonBody,
                               TokenScope scope) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + pathAndQuery))
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + credentialBroker.token(scope))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", API_VERSION);
            if (jsonBody == null) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(jsonBody)));
            }
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            return parse(response.statusCode(), response.body())
                    .withRateLimitHeaders(
                            RetryAfterParser.parseSeconds(
                                    response.headers().firstValue("Retry-After").orElse(null), Instant.now()),
                            longHeader(response, "X-RateLimit-Remaining"),
                            longHeader(response, "X-RateLimit-Reset"));
        } catch (IOException e) {
            throw new GitHubTransportException(method + " " + pathAndQuery + " 传输失败: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubTransportException(method + " " + pathAndQuery + " 被中断", e);
        }
    }

    private TypedResponse parse(int status, String body) {
        if (body == null || body.isBlank()) {
            return TypedResponse.ofStatus(status);
        }
        try {
            if (body.stripLeading().startsWith("[")) {
                List<Map<String, Object>> array = objectMapper.readValue(body, new TypeReference<>() {
                });
                return TypedResponse.ofArray(status, array);
            }
            Map<String, Object> object = objectMapper.readValue(body, new TypeReference<>() {
            });
            return TypedResponse.ofObject(status, object);
        } catch (IOException e) {
            // 非 JSON 响应（网关错误页等）：只保留状态码，归类由 Handler 按 status 处理
            return TypedResponse.ofStatus(status);
        }
    }

    private static Long longHeader(HttpResponse<?> response, String name) {
        try {
            return response.headers().firstValue(name).map(String::trim).map(Long::valueOf).orElse(null);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String required(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            throw new IllegalArgumentException("TypedRequest 缺参数: " + key);
        }
        return value.toString();
    }

    private static Map<String, Object> without(Map<String, Object> params, String key) {
        Map<String, Object> body = new java.util.HashMap<>(params);
        body.remove(key);
        return body;
    }

    private static String paging(Map<String, Object> params) {
        return "?per_page=" + params.getOrDefault("per_page", 100)
                + "&page=" + params.getOrDefault("page", 1);
    }
}
