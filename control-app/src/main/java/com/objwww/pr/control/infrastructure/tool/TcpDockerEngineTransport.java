package com.objwww.pr.control.infrastructure.tool;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * Docker Engine TCP 端点传输（生产装配面）：GET {base}{path}，短超时；引擎鉴权
 * （TLS/凭据）归部署面。unix socket 形态待部署窗按宿主实情补，不硬造。
 */
public class TcpDockerEngineTransport implements DockerEngineTransport {

    private static final long TIMEOUT_MILLIS = 5_000;

    private final String baseUrl;
    private final HttpClient http;

    public TcpDockerEngineTransport(String baseUrl) {
        this(baseUrl, HttpClient.newHttpClient());
    }

    public TcpDockerEngineTransport(String baseUrl, HttpClient http) {
        if (baseUrl == null || baseUrl.isBlank() || baseUrl.endsWith("/")) {
            throw new IllegalArgumentException("docker base-url 不得为空或以 / 结尾");
        }
        this.baseUrl = baseUrl;
        this.http = Objects.requireNonNull(http);
    }

    @Override
    public byte[] get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(TIMEOUT_MILLIS))
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("docker 引擎调用被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("docker 引擎不可达", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("docker 引擎拒绝查询（HTTP "
                    + response.statusCode() + "）");
        }
        return response.body();
    }
}
