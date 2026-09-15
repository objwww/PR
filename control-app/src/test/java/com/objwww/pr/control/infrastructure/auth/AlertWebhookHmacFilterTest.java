package com.objwww.pr.control.infrastructure.auth;

import com.objwww.pr.shared.Digests;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AlertWebhookHmacFilter 穷举单测（PA-A7）：有效签名放行且下游 body 可读（PA-BUG
 * 回归钉）、篡改 body 拒、时间窗外拒、nonce 重放拒、未知 keyId 拒、无签名头直通、
 * 未配置密钥直通、畸形签名 hex 拒。
 */
class AlertWebhookHmacFilterTest {

    private static final String KEY_ID = "ops-ci";
    private static final String SECRET = "unit-test-secret";
    private static final String PATH = "/webhooks/alertmanager";
    private static final String BODY = "{\"alerts\":[]}";

    /** 捕捉下游视角：认证对象 + 可读 body 长度（过滤器消费 body 后下游仍须可读） */
    private record Downstream(Authentication auth, int bodyLength) {
    }

    private static AlertWebhookHmacFilter filter() {
        return new AlertWebhookHmacFilter(Map.of(KEY_ID, SECRET));
    }

    private static String sign(String keyId, long timestamp, String nonce, String body) {
        try {
            String canonical = keyId + "\nPOST\n" + PATH + "\n" + timestamp + "\n" + nonce
                    + "\n" + Digests.sha256Hex(body.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }

    private static MockHttpServletRequest signedRequest(long timestamp, String nonce,
            String signature, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.addHeader(AlertWebhookHmacFilter.HEADER_KEY_ID, KEY_ID);
        request.addHeader(AlertWebhookHmacFilter.HEADER_TIMESTAMP, String.valueOf(timestamp));
        request.addHeader(AlertWebhookHmacFilter.HEADER_NONCE, nonce);
        request.addHeader(AlertWebhookHmacFilter.HEADER_SIGNATURE, signature);
        return request;
    }

    /** 执行过滤器 + 下游捕捉链，返回下游视角（null = 链未到达下游） */
    private static Downstream doFilter(AlertWebhookHmacFilter filter,
            MockHttpServletRequest request) {
        AtomicReference<Authentication> authRef = new AtomicReference<>();
        AtomicInteger bodyLen = new AtomicInteger(-1);
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            filter.doFilter(request, response, (servletRequest, servletResponse) -> {
                if (servletRequest instanceof HttpServletRequest httpRequest) {
                    authRef.set(SecurityContextHolder.getContext().getAuthentication());
                    bodyLen.set(httpRequest.getInputStream().readAllBytes().length);
                }
            });
        } catch (ServletException | IOException e) {
            throw new IllegalStateException(e);
        } finally {
            SecurityContextHolder.clearContext();
        }
        return new Downstream(authRef.get(), bodyLen.get());
    }

    @Test
    void utH01_有效签名放行_下游body完整可读_授MACHINE_WEBHOOK角色() {
        long now = System.currentTimeMillis() / 1000L;
        Downstream downstream = doFilter(filter(),
                signedRequest(now, "nonce-h01", sign(KEY_ID, now, "nonce-h01", BODY), BODY));
        assertThat(downstream.bodyLength()).isEqualTo(BODY.length());
        assertThat(downstream.auth()).isNotNull();
        assertThat(downstream.auth().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_MACHINE_WEBHOOK");
    }

    @Test
    void utH02_body篡改_401_链未到下游() {
        long now = System.currentTimeMillis() / 1000L;
        Downstream downstream = doFilter(filter(), signedRequest(now, "nonce-h02",
                sign(KEY_ID, now, "nonce-h02", BODY), "{\"alerts\":[],\"injected\":true}"));
        assertThat(downstream.auth()).isNull();
        assertThat(downstream.bodyLength()).isEqualTo(-1);
    }

    @Test
    void utH03_时间窗外_401() {
        long stale = System.currentTimeMillis() / 1000L
                - AlertWebhookHmacFilter.TIMESTAMP_WINDOW_SECONDS - 1;
        Downstream downstream = doFilter(filter(),
                signedRequest(stale, "nonce-h03", sign(KEY_ID, stale, "nonce-h03", BODY), BODY));
        assertThat(downstream.auth()).isNull();
    }

    @Test
    void utH04_nonce重放_第二问401() {
        long now = System.currentTimeMillis() / 1000L;
        String nonce = "nonce-h04";
        AlertWebhookHmacFilter filter = filter(); // 同一实例——nonce 防重放缓存在过滤器内
        Downstream first = doFilter(filter,
                signedRequest(now, nonce, sign(KEY_ID, now, nonce, BODY), BODY));
        Downstream replay = doFilter(filter,
                signedRequest(now, nonce, sign(KEY_ID, now, nonce, BODY), BODY));
        assertThat(first.auth()).isNotNull();
        assertThat(replay.auth()).isNull();
    }

    @Test
    void utH05_未知keyId_401() {
        long now = System.currentTimeMillis() / 1000L;
        MockHttpServletRequest request = signedRequest(now, "nonce-h05",
                sign("rogue-key", now, "nonce-h05", BODY), BODY);
        Downstream downstream = doFilter(filter(), request);
        assertThat(downstream.auth()).isNull();
    }

    @Test
    void utH06_无签名头_直通bearer链_过滤面不介入() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer am-token");
        AlertWebhookHmacFilter filter = filter();
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void utH07_未配置密钥_即便带签名头也直通() {
        long now = System.currentTimeMillis() / 1000L;
        AlertWebhookHmacFilter unconfigured = new AlertWebhookHmacFilter(Map.of());
        assertThat(unconfigured.configured()).isFalse();
        assertThat(unconfigured.shouldNotFilter(
                signedRequest(now, "nonce-h07", sign(KEY_ID, now, "nonce-h07", BODY), BODY)))
                .isTrue();
    }

    @Test
    void utH08_畸形签名hex_401_不抛异常() {
        long now = System.currentTimeMillis() / 1000L;
        Downstream downstream = doFilter(filter(),
                signedRequest(now, "nonce-h08", "not-hex!", BODY));
        assertThat(downstream.auth()).isNull();
    }
}
