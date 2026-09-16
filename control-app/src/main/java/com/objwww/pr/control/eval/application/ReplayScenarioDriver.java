package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.objwww.pr.control.eval.domain.GoldenCase;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * 回放场景驱动器（P2 执行集接通；OpenRCA/Meta point-in-time 回放形态）：
 * activate = 从 alert_inbox 取含目标 alertname 的**冻结 firing 载荷原文**重投
 * /webhooks/alertmanager（bearer 机器线，env 注入 fail-closed——INV-AM3-3 同律）；
 * 管线照常铸新 episode → 调查 → resolver 认终态 run。deactivate = 同载荷的
 * resolved 变体（alerts[].status=resolved + endsAt=now，labels/fingerprint 原样），
 * 让回放 episode 闭环——不残留 firing 现场污染下一轮（prev_episode_resolved 门）。
 *
 * <p>回放 ≠ 注入：不构造任何新告警语义，只重放真实历史载荷（构造 = 编造，
 * 冻结原文重投 = 回放）。HMAC nonce 防重放不阻碍本面——带签名头才走 HMAC 过滤，
 * 本面走 bearer 机器线（AM 兼容硬约束同一豁免面）。
 */
public class ReplayScenarioDriver implements ScenarioDriver {

    public static final String DRIVER_NAME = "ReplayScenarioDriver";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FrozenPayloadReader payloadReader;
    private final WebhookClient webhook;
    private final java.util.function.Supplier<Instant> clock;

    public ReplayScenarioDriver(FrozenPayloadReader payloadReader, WebhookClient webhook,
            java.util.function.Supplier<Instant> clock) {
        this.payloadReader = Objects.requireNonNull(payloadReader);
        this.webhook = Objects.requireNonNull(webhook);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 冻结载荷读面（Postgres 实现：alert_inbox 最新含 alertname 的 firing 组载荷） */
    public interface FrozenPayloadReader {

        record FrozenPayload(byte[] body, String sha256Hex) {
        }

        /** 最新含该 alertname 且 status=firing 的组载荷原文；无 → empty（不猜） */
        java.util.Optional<FrozenPayload> latestFiring(String alertname);
    }

    /** webhook 重投面（bearer env 注入；blank = fail-closed 拒绝发信） */
    public interface WebhookClient {

        /** 返回 HTTP 状态码；传输层异常原样上抛 */
        int post(byte[] body) throws Exception;
    }

    @Override
    public ActivationReceipt activate(GoldenCase golden, int roundNo) {
        String alertname = alertnameOf(golden);
        FrozenPayloadReader.FrozenPayload payload = payloadReader.latestFiring(alertname)
                .orElseThrow(() -> new IllegalStateException(
                        "回放锚缺失：alert_inbox 无含 alertname=" + alertname
                                + " 的历史 firing 载荷（零伪造，不现编载荷）"));
        int status;
        try {
            status = postWithRetry(payload.body());
        } catch (java.lang.InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("回放重投被中断: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("回放重投失败: " + describe(e), e);
        }
        if (status != 200 && status != 202) {
            throw new IllegalStateException(
                    "回放重投被拒（http=" + status + "，AM 整组语义 2xx 才算受理）");
        }
        return new ActivationReceipt(golden.scenarioId(),
                "replay:" + payload.sha256Hex(), 0, alertname);
    }

    /**
     * 有界重试（195 E2E 实证：LAUNCH 命令 5s 内被领取，常撞上 control-app
     * 例行重建窗口——ConnectException 竞态，非语义拒绝）：连接面异常 3 次 ×
     * 10s 退避；HTTP 4xx/5xx 是明确拒绝不重试（重试语义只对"没到达"）。
     * 重投重复面安全：同指纹重复载荷由管线 fingerprint 去重，不产生双事件。
     */
    private int postWithRetry(byte[] body) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return webhook.post(body);
            } catch (java.lang.InterruptedException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                if (attempt < 3) {
                    Thread.sleep(10_000);
                }
            }
        }
        throw last;
    }

    /**
     * 单行异常链摘要（195 E2E 诊断修正：JDK HttpClient 连接面会把真实原因包进
     * null-message IOException——失败样本必须可见到 cause 级事实，否则无法定谳）。
     */
    private static String describe(Exception e) {
        StringBuilder sb = new StringBuilder(e.getClass().getSimpleName()).append('/')
                .append(e.getMessage());
        Throwable cause = e.getCause();
        int depth = 0;
        while (cause != null && depth++ < 3) {
            sb.append(" <- ").append(cause.getClass().getSimpleName()).append('/')
                    .append(cause.getMessage());
            cause = cause.getCause();
        }
        return sb.toString();
    }

    @Override
    public RecoveryReceipt deactivate(GoldenCase golden, ActivationReceipt receipt) {
        String alertname = alertnameOf(golden);
        FrozenPayloadReader.FrozenPayload payload = payloadReader.latestFiring(alertname)
                .orElseThrow(() -> new IllegalStateException(
                        "回放清理失败：alert_inbox 已无 alertname=" + alertname
                                + " 的 firing 载荷（激活后数据面漂移）"));
        byte[] resolvedBody = buildResolvedVariant(payload.body(), clock.get());
        boolean ok;
        try {
            int status = postWithRetry(resolvedBody);
            ok = status == 200 || status == 202;
        } catch (java.lang.InterruptedException e) {
            Thread.currentThread().interrupt();
            ok = false;
        } catch (Exception e) {
            ok = false;
        }
        return new RecoveryReceipt(golden.scenarioId(), "replay-resolved", 0, ok, ok,
                ok ? List.of() : List.of("resolved_replay_not_accepted"));
    }

    /**
     * resolved 变体（纯函数，测试面直测）：每个 alert 置 status=resolved、
     * endsAt=now（ISO-8601 UTC），labels/annotations/fingerprint/startsAt 与
     * 顶层 common 字段原样——AM resolved 语义的确定性变换，零语义新增。
     */
    public static byte[] buildResolvedVariant(byte[] firingBody, Instant now) {
        JsonNode root;
        try {
            root = MAPPER.readTree(new String(firingBody, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException("冻结载荷 JSON 解析失败: " + e.getMessage(), e);
        }
        if (!(root instanceof ObjectNode rootObj) || !rootObj.has("alerts")
                || !rootObj.get("alerts").isArray()) {
            throw new IllegalArgumentException("冻结载荷缺少 alerts 数组");
        }
        String endsAt = DateTimeFormatter.ISO_INSTANT.format(now);
        // 根级组状态与逐告警状态同步置 resolved（AM 组语义；intake 按逐告警解析，
        // 根字段一并翻转是对真实 AM resolved 载荷形态的忠实变换）
        rootObj.put("status", "resolved");
        for (JsonNode alert : rootObj.get("alerts")) {
            if (alert instanceof ObjectNode a) {
                a.put("status", "resolved");
                a.put("endsAt", endsAt);
            }
        }
        try {
            return MAPPER.writeValueAsBytes(rootObj);
        } catch (IOException e) {
            throw new IllegalStateException("resolved 变体序列化失败", e);
        }
    }

    private static String alertnameOf(GoldenCase golden) {
        if (golden.expectedSymptomCodes().isEmpty()) {
            throw new IllegalStateException("回放场景缺重放锚 alertname: " + golden.scenarioId());
        }
        return golden.expectedSymptomCodes().getFirst();
    }

    /** HTTP 实现（bearer 机器线；token blank = fail-closed 拒发） */
    public static final class HttpWebhook implements WebhookClient {

        private final String url;
        private final String bearer;
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        public HttpWebhook(String url, String bearer) {
            this.url = Objects.requireNonNull(url);
            this.bearer = bearer == null ? "" : bearer.trim();
        }

        @Override
        public int post(byte[] body) throws Exception {
            if (bearer.isEmpty()) {
                throw new IllegalStateException(
                        "回放 webhook bearer 未配置（app.alert.eval.webhook-bearer，"
                                + "env 注入 fail-closed——INV-AM3-3 同律）");
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + bearer)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<Void> response = client.send(request,
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        }
    }
}
