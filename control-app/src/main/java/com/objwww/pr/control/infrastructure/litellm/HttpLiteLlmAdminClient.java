package com.objwww.pr.control.infrastructure.litellm;

import com.objwww.pr.control.eval.domain.litellm.LiteLlmAdminPort;
import com.objwww.pr.control.eval.domain.litellm.SpendRecord;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * LiteLLM 管理面默认 HTTP 实现（M3-25；RestClient，与 FlagAdminClient.Http 同款家规）。
 *
 * <p>非 2xx 一律 {@link IllegalStateException}，只带状态码不带响应体——错误体可能回显
 * 请求面（master key 域），不入日志。无重试：对账/铸 key 是评测工具面，失败落
 * 降级台账（UNMATCHED/BEST_EFFORT）而非掩盖。
 */
public final class HttpLiteLlmAdminClient implements LiteLlmAdminPort {

    private final RestClient rest;

    public HttpLiteLlmAdminClient(String baseUrl, String masterKey) {
        Objects.requireNonNull(baseUrl);
        if (baseUrl.isBlank()) {
            throw new IllegalArgumentException("LiteLLM base-url 不得为空");
        }
        if (masterKey == null || masterKey.isBlank()) {
            // INV-AM3-3：master key 仅 env 注入；配置了 base-url 却无 key = 装配错误，fail-fast
            throw new IllegalArgumentException("LiteLLM master key 缺失（仅 env 注入，不得落配置文件）");
        }
        this.rest = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + masterKey)
                .build();
    }

    @Override
    public String mintRunKey(String keyAlias, BigDecimal maxBudgetUsd) {
        Objects.requireNonNull(keyAlias);
        Map<String, Object> body = maxBudgetUsd == null
                ? Map.of("key_alias", keyAlias,
                        "metadata", Map.of("purpose", "am3-eval-run"))
                : Map.of("key_alias", keyAlias,
                        "max_budget", maxBudgetUsd,
                        "metadata", Map.of("purpose", "am3-eval-run"));
        try {
            Map<?, ?> response = rest.post().uri("/key/generate")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            Object key = response == null ? null : response.get("key");
            if (key == null || key.toString().isBlank()) {
                throw new IllegalStateException("LiteLLM /key/generate 响应缺 key 字段");
            }
            return key.toString();
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("LiteLLM /key/generate 调用失败: " + safeReason(e), e);
        }
    }

    @Override
    public List<SpendRecord> spendLogs() {
        try {
            String body = rest.get().uri("/spend/logs")
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                return List.of();
            }
            return SpendRecord.listFromJson(
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(body));
        } catch (RuntimeException e) {
            throw new IllegalStateException("LiteLLM /spend/logs 调用失败: " + safeReason(e), e);
        } catch (Exception e) {
            throw new IllegalStateException("LiteLLM /spend/logs 解析失败", e);
        }
    }

    /** 错误面只出状态码——RestClientResponseException 的 message 可能携带响应体（master key 域） */
    private static String safeReason(RuntimeException e) {
        if (e instanceof org.springframework.web.client.RestClientResponseException rce) {
            return "HTTP " + rce.getStatusCode().value();
        }
        return e.getMessage();
    }
}
