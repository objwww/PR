package com.objwww.pr.control.infrastructure.runner;

import com.objwww.pr.control.alert.application.mutation.ActionRunner;
import com.objwww.pr.control.alert.domain.mutation.RcaOperation;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 真执行 Runner（PD-D1，scoped mutation）：向配置端点 POST 派发载荷
 * （operation_id/action_id/digest/resource_uid/params）——真实外部副作用面。
 * 失败语义（§2.11）：网络 timeout / 5xx / 任何非 2xx ≠ failed——
 * 返回 TIMEOUT_UNKNOWN（Operation → UNKNOWN，锁保持 BUSY，reconcile 裁决），
 * 不猜 FAILED。
 */
public class HttpActionRunner implements ActionRunner {

    private final RestTemplate http;
    private final String endpoint;

    public HttpActionRunner(RestTemplate http, String endpoint) {
        this.http = Objects.requireNonNull(http);
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("真执行端点不得为空");
        }
        this.endpoint = endpoint;
    }

    @Override
    public Outcome run(RcaOperation operation) {
        if (operation.dryRun()) {
            throw new IllegalStateException("真执行 Runner 拒绝 dry_run operation");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation_id", operation.operationId().toString());
        payload.put("action_id", operation.actionId());
        payload.put("action_digest", operation.actionDigest());
        payload.put("resource_uid", operation.resourceUid());
        payload.put("params", operation.paramsJson());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            var response = http.postForEntity(endpoint, new HttpEntity<>(payload, headers),
                    String.class);
            return response.getStatusCode().is2xxSuccessful()
                    ? Outcome.EXECUTED
                    : Outcome.TIMEOUT_UNKNOWN;
        } catch (RuntimeException e) {
            // timeout/连接失败/5xx：真相未知——对账面裁决（不猜 FAILED）
            return Outcome.TIMEOUT_UNKNOWN;
        }
    }

    /** 便捷工厂（测试可替换 ClientHttpRequestFactory） */
    public static HttpActionRunner create(String endpoint, int connectTimeoutMillis,
            int readTimeoutMillis) {
        RestTemplate template = new RestTemplate();
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMillis);
        factory.setReadTimeout(readTimeoutMillis);
        template.setRequestFactory((ClientHttpRequestFactory) factory);
        return new HttpActionRunner(template, endpoint);
    }
}
