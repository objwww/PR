package com.objwww.pr.control.eval.application;

import com.objwww.pr.shared.Digest;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * chaos 管理面 HTTP 客户端的窄接口（eval-mgmt 私网 ChaosController，M2-17 契约）。
 * 抽出接口便于 driver 契约测试用假件替换（真栈行为归 195 门）。
 */
public interface ChaosAdminClient {

    /** POST /chaos/{faultType}/on → 201 {sessionId, scenarioId, generation, alertFingerprint} */
    Activation activate(String faultType, Map<String, Object> body);

    /** POST /chaos/{faultType}/off → 202 {state:RECOVERING}；409 = CAS 未中 */
    boolean deactivate(String faultType, Map<String, Object> body);

    /** GET /chaos/status?scenarioId= → {session:{state, generation, ...}}；404 = 未知场景 */
    SessionStatus status(String scenarioId);

    record Activation(String sessionId, String scenarioId, long generation,
                      String alertFingerprint) {
    }

    record SessionStatus(String state, long generation) {
    }

    /** 默认实现：RestClient + X-Admin-Token（常量时间校验在服务端；token 仅 env 注入） */
    final class Http implements ChaosAdminClient {

        private final RestClient rest;
        private final String adminToken;

        public Http(String baseUrl, String adminToken) {
            Objects.requireNonNull(baseUrl);
            this.adminToken = adminToken == null ? "" : adminToken.trim();
            this.rest = RestClient.builder().baseUrl(baseUrl).build();
        }

        @Override
        public Activation activate(String faultType, Map<String, Object> body) {
            if (adminToken.isEmpty()) {
                throw new IllegalStateException(
                        "CHAOS_ADMIN_TOKEN 未注入（INV-AM3-3 fail-closed），拒绝注入");
            }
            Response r = rest.post().uri("/chaos/{faultType}/on", faultType)
                    .header("X-Admin-Token", adminToken)
                    .body(body)
                    .retrieve().body(Response.class);
            return new Activation(r.sessionId(), r.scenarioId(), r.generation(),
                    r.alertFingerprint());
        }

        @Override
        public boolean deactivate(String faultType, Map<String, Object> body) {
            if (adminToken.isEmpty()) {
                throw new IllegalStateException(
                        "CHAOS_ADMIN_TOKEN 未注入（INV-AM3-3 fail-closed），拒绝注入操作");
            }
            try {
                rest.post().uri("/chaos/{faultType}/off", faultType)
                        .header("X-Admin-Token", adminToken)
                        .body(body)
                        .retrieve().toBodilessEntity();
                return true;
            } catch (org.springframework.web.client.HttpClientErrorException.Conflict e) {
                return false;
            }
        }

        @Override
        public SessionStatus status(String scenarioId) {
            StatusResponse r = rest.get()
                    .uri(b -> b.path("/chaos/status").queryParam("scenarioId", scenarioId).build())
                    .header("X-Admin-Token", adminToken)
                    .retrieve().body(StatusResponse.class);
            return new SessionStatus(r.session().state(), r.session().generation());
        }

        private record Response(String sessionId, String scenarioId, long generation,
                                String alertFingerprint) {
        }

        private record StatusResponse(Session session) {
            private record Session(String state, long generation) {
            }
        }
    }

    /** 激活请求体（ChaosAdminController.ActivationRequest 的最小面） */
    static Map<String, Object> activationBody(String scenarioId, String target, int ttlSeconds,
                                              String configDigest, String datasetVersion,
                                              String payloadDigest,
                                              Map<String, String> alertLabels,
                                              String ruleDigest) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scenarioId", scenarioId);
        body.put("target", target);
        body.put("ttlSeconds", ttlSeconds);
        body.put("operator", "eval-runner");
        body.put("configDigest", configDigest);
        body.put("groundTruth", Map.of(
                "schemaVersion", 1,
                "datasetVersion", datasetVersion,
                "payloadDigest", payloadDigest,
                "applicableScope", "arena"));
        body.put("alertLabels", alertLabels);
        body.put("ruleDigest", ruleDigest);
        return body;
    }

    /** 规范化动作摘要（回执 actionDigest：同动作同 digest，供审计对账） */
    static Digest actionDigest(String action, String scenarioId, Object... parts) {
        StringBuilder canonical = new StringBuilder(action).append(':').append(scenarioId);
        for (Object part : parts) {
            canonical.append('|').append(part);
        }
        return Digest.sha256Of(canonical.toString());
    }
}
