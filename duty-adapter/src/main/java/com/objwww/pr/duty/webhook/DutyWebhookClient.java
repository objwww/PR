package com.objwww.pr.duty.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.duty.http.JsonHttp;
import com.objwww.pr.duty.snapshot.SnapshotState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 企微/钉钉直发客户端（M7-17）。业务码纪律（E-21，语义与 notify-app F20 分类器同律）：
 * <b>HTTP 200 不算成功</b>——必须读响应体 errcode（0=送达；≠0=失败，45009/310000 均此面）；
 * HTTP≠2xx / IO 异常 = 失败。无重试：降级（下一优先级通道）由调用侧迭代通道链完成。
 *
 * <p>钉钉加签：URL 追加 timestamp+sign（HMAC-SHA256(key=secret, data=ts+"\n"+secret)）；
 * 企微 webhook 密钥即 URL，无加签面（secret 键名空=不加签）。
 */
@Component
public class DutyWebhookClient {

    private static final Logger log = LoggerFactory.getLogger(DutyWebhookClient.class);
    private static final int MAX_TEXT = 4000;

    private final JsonHttp http;
    private final ObjectMapper mapper = new ObjectMapper();

    public DutyWebhookClient(JsonHttp http) {
        this.http = http;
    }

    public record SendResult(boolean ok, String channelName, String detail) {
    }

    /** 通道链逐条尝试由调用侧驱动；本方法只发一条。env 缺失=确定性失败（不跳闸重试）。 */
    public SendResult send(SnapshotState.Channel channel, String text) {
        String url = env(channel.envKeyWebhook());
        if (url == null || url.isBlank()) {
            return new SendResult(false, channel.name(),
                    "env_missing:" + channel.envKeyWebhook());
        }
        String secret = channel.envKeySecret() == null ? null : env(channel.envKeySecret());
        String target = "DINGTALK".equalsIgnoreCase(channel.platform()) && secret != null
                ? signedUrl(url, secret, System.currentTimeMillis()) : url;
        try {
            JsonHttp.PostResult response = http.postWebhook(target, body(text));
            if (response.status() / 100 != 2) {
                return new SendResult(false, channel.name(),
                        "http_" + response.status());
            }
            JsonNode ack = mapper.readTree(response.body());
            int errcode = ack.path("errcode").asInt(-1);
            if (errcode != 0) {
                return new SendResult(false, channel.name(),
                        "errcode_" + errcode + ":" + ack.path("errmsg").asText(""));
            }
            return new SendResult(true, channel.name(), "ok");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new SendResult(false, channel.name(), "io:" + e.getClass().getSimpleName());
        }
    }

    /** env 提取面（protected=测试 seam：JDK 无 setenv） */
    protected String env(String key) {
        return key == null ? null : System.getenv(key);
    }

    /** 钉钉加签 URL（独立可测：时间戳注入） */
    static String signedUrl(String url, String secret, long timestampMillis) {
        try {
            String data = timestampMillis + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sign = Base64.getEncoder().encodeToString(
                    mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
            return url + (url.contains("?") ? "&" : "?")
                    + "timestamp=" + timestampMillis
                    + "&sign=" + URLEncoder.encode(sign, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("dingtalk sign failed", e);
        }
    }

    private static String body(String text) {
        String safe = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        return "{\"msgtype\":\"text\",\"text\":{\"content\":"
                + quote(safe) + "}}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }
}
