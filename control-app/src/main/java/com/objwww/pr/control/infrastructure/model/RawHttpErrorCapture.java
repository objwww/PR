package com.objwww.pr.control.infrastructure.model;

import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 原始 HTTP 错误捕获器（§4.10/T00）：装在 OpenAiApi 的 RestClient 错误处理链上，
 * 把错误响应的 status/headers/body 原样装进 {@link RawHttpErrorException} 抛出——
 * 替代 Spring AI 1.0.0 默认 error handler（F-17 实证：它把所有 4xx 一律归入
 * NonTransientAiException，429 与参数错误不分，粒度不足，永不驱动我们的决策）。
 */
public final class RawHttpErrorCapture implements ResponseErrorHandler {

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(ClientHttpResponse response) throws IOException {
        byte[] bodyBytes = response.getBody() == null ? null : response.getBody().readAllBytes();
        String body = bodyBytes == null ? null : new String(bodyBytes, StandardCharsets.UTF_8);
        throw new RawHttpErrorException(response.getStatusCode().value(),
                response.getHeaders(), body);
    }
}
