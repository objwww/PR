package com.objwww.pr.control.infrastructure.github;

import com.objwww.pr.control.domain.port.CredentialTokenPort;
import com.objwww.pr.control.domain.port.GitHubSourcePort;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * GitHub 只读适配器（JDK HttpClient）：tarball / compare diff 两个 GET。
 * token 经 CredentialTokenPort 窄接口按 installation 申请，只存内存、不落日志。
 *
 * <p>防线：响应字节流限额读取（压缩后的炸弹也拦一道，解压侧另有 SafeTarExtractor 限额）。
 * 刻意不加 Spring 注解：默认 profile 空跑不装配，接线属后续任务。
 */
public class GitHubReadAdapter implements GitHubSourcePort {

    /** 压缩后响应上限（解压限额由 SafeTarExtractor 兜底） */
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 200L * 1024 * 1024;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final CredentialTokenPort tokenPort;
    private final HttpClient http;
    private final String baseUrl;
    private final long maxResponseBytes;

    public GitHubReadAdapter(CredentialTokenPort tokenPort) {
        this(tokenPort, "https://api.github.com");
    }

    /** docker 栈可注入 GitHub baseUrl（T18 stub 模式指向 compose 内网 github-stub） */
    public GitHubReadAdapter(CredentialTokenPort tokenPort, String baseUrl) {
        this(tokenPort, HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL) // api.github.com → codeload
                        .build(),
                baseUrl, DEFAULT_MAX_RESPONSE_BYTES);
    }

    /** 测试可注入 baseUrl / HttpClient / 限额 */
    public GitHubReadAdapter(CredentialTokenPort tokenPort, HttpClient http,
                             String baseUrl, long maxResponseBytes) {
        this.tokenPort = Objects.requireNonNull(tokenPort);
        this.http = Objects.requireNonNull(http);
        this.baseUrl = Objects.requireNonNull(baseUrl);
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public byte[] fetchTarball(long installationId, String repoFullName, String sha) {
        HttpRequest request = authed(installationId, baseUrl + "/repos/" + repoFullName + "/tarball/" + sha)
                .header("Accept", "application/vnd.github+json")
                .build();
        return readLimited(send(request), "tarball");
    }

    @Override
    public String fetchDiff(long installationId, String repoFullName, String baseSha, String headSha) {
        HttpRequest request = authed(installationId,
                baseUrl + "/repos/" + repoFullName + "/compare/" + baseSha + "..." + headSha)
                .header("Accept", "application/vnd.github.diff")
                .build();
        return new String(readLimited(send(request), "diff"), StandardCharsets.UTF_8);
    }

    private HttpRequest.Builder authed(long installationId, String url) {
        // token 只进请求头、不进异常消息与日志
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + tokenPort.requestReadOnlyToken(installationId))
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET();
    }

    private HttpResponse<InputStream> send(HttpRequest request) {
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException("GitHub 读请求失败: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub 读请求被中断: " + request.uri(), e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "GitHub 读请求非 200: " + response.statusCode() + " " + request.uri());
        }
        return response;
    }

    /** 限额读取响应体：超限即断流抛错（压缩层炸弹防线） */
    private byte[] readLimited(HttpResponse<InputStream> response, String what) {
        try (InputStream in = response.body()) {
            byte[] chunk = new byte[64 * 1024];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int n;
            long total = 0;
            while ((n = in.read(chunk)) != -1) {
                total += n;
                if (total > maxResponseBytes) {
                    throw new IllegalStateException(
                            "GitHub " + what + " 响应超过上限 " + maxResponseBytes + " 字节");
                }
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("读取 GitHub 响应失败: " + what, e);
        }
    }
}
