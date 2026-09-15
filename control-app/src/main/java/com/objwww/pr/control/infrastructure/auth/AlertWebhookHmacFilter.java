package com.objwww.pr.control.infrastructure.auth;

import com.objwww.pr.shared.Digests;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机器面 HMAC-SHA256 入口验证（PA-A7，矩阵 L0-1 / 严格处置 D1/R4）：
 *
 * <ul>
 *   <li><b>签名覆盖</b>：{@code keyId + "\n" + method + "\n" + path + "\n" + timestamp
 *       + "\n" + nonce + "\n" + sha256hex(body)}——method/path 入签防同签名载荷搬运
 *       到其他端点重放（评审 R4）；body 以收到字节为准（gzip 不解包，完整性按线缆面）。</li>
 *   <li><b>HMAC 永远验证</b>（R4）：验签不依赖任何外部组件；nonce 防重放用进程内
 *       缓存（容量 1e4、TTL 2×时间窗），缓存满即清除最旧——多实例部署下本地缓存
 *       无法完全阻止跨实例重放，该残余风险随扩容引入共享存储时关闭（已登记）。</li>
 *   <li><b>双凭证兼容</b>：请求携带 X-PA-Signature 头才进入本过滤（无头 = 原 bearer
 *       链原样放行——Alertmanager webhook 无法计算 HMAC，机器兼容是硬约束）；
 *       携带头但验签失败/时间窗外/nonce 重放 = 401 直接终止（不回落 bearer，
 *       故障期安全等级不降，R4 纪律）。</li>
 *   <li><b>密钥管理</b>：{@code app.alert.webhook.hmac-keys = "keyId:secret,..."}，
 *       多 keyId 并行（轮换期双密钥同验）；未配置任何密钥 = 过滤器直通（未采纳
 *       HMAC 姿态，等价现 bearer-only）。</li>
 * </ul>
 */
public class AlertWebhookHmacFilter extends OncePerRequestFilter {

    static final String HEADER_KEY_ID = "X-PA-Key-Id";
    static final String HEADER_TIMESTAMP = "X-PA-Timestamp";
    static final String HEADER_NONCE = "X-PA-Nonce";
    static final String HEADER_SIGNATURE = "X-PA-Signature";

    /** 时间窗（秒）：|now - timestamp| 超窗即拒（防陈旧签名重放） */
    static final long TIMESTAMP_WINDOW_SECONDS = 300;

    private final Map<String, String> keysById;
    /** nonce 防重放：nonce → 到期时刻（容量超限整体清一次——2C4G 单实例足够） */
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    public AlertWebhookHmacFilter(Map<String, String> keysById) {
        this.keysById = Map.copyOf(Objects.requireNonNull(keysById, "keysById"));
    }

    public boolean configured() {
        return !keysById.isEmpty();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只在携带签名头时介入；纯 bearer 请求原样走既有链（AM 兼容硬约束）
        return request.getHeader(HEADER_SIGNATURE) == null || keysById.isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            // PA-BUG（195 部署前自检）：验签必须读 body——直接读原始流会把下游
            // @RequestBody 吃成空体；包一层缓存 body 的 wrapper 再放行
            var cached = new CachedBodyRequest(request);
            if (verify(cached)) {
                SecurityContextHolder.getContext().setAuthentication(authentication());
                chain.doFilter(cached, response);
            } else {
                reject(response, "hmac verification failed");
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** body 缓存 wrapper：验签读流与下游消费共用同一份字节（线缆面完整性） */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() {
            java.io.ByteArrayInputStream buffer = new java.io.ByteArrayInputStream(body);
            return new jakarta.servlet.ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return buffer.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(jakarta.servlet.ReadListener listener) {
                    throw new UnsupportedOperationException("sync-only cached body");
                }

                @Override
                public int read() {
                    return buffer.read();
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(
                    getInputStream(), StandardCharsets.UTF_8));
        }
    }

    /** 验签全序：keyId 在册 → 时间窗 → nonce 新鲜 → body 摘要 → HMAC 等值（常量时间） */
    private boolean verify(HttpServletRequest request) {
        String keyId = request.getHeader(HEADER_KEY_ID);
        String timestamp = request.getHeader(HEADER_TIMESTAMP);
        String nonce = request.getHeader(HEADER_NONCE);
        String signature = request.getHeader(HEADER_SIGNATURE);
        if (isBlank(keyId) || isBlank(timestamp) || isBlank(nonce) || isBlank(signature)) {
            return false;
        }
        String secret = keysById.get(keyId);
        if (secret == null) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        long now = System.currentTimeMillis() / 1000L;
        if (Math.abs(now - ts) > TIMESTAMP_WINDOW_SECONDS) {
            return false;
        }
        if (seenNonces.putIfAbsent(nonce, now + 2 * TIMESTAMP_WINDOW_SECONDS) != null) {
            return false; // 重放
        }
        if (seenNonces.size() > 10_000) {
            long cutoff = now;
            seenNonces.values().removeIf(expiry -> expiry < cutoff);
        }
        try {
            byte[] body = request.getInputStream().readAllBytes();
            String bodyDigest = Digests.sha256Hex(body);
            String canonical = keyId + "\n" + request.getMethod().toUpperCase(Locale.ROOT)
                    + "\n" + request.getRequestURI() + "\n" + timestamp.trim() + "\n"
                    + nonce.trim() + "\n" + bodyDigest;
            byte[] expected = hmacSha256(secret, canonical);
            byte[] provided = hexDecode(signature.trim());
            return expected.length == provided.length
                    && MessageDigest.isEqual(expected, provided);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] hmacSha256(String secret, String canonical) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] hexDecode(String hex) {
        int len = hex.length();
        if (len % 2 != 0) {
            return new byte[0];
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int high = Character.digit(hex.charAt(i), 16);
            int low = Character.digit(hex.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                return new byte[0];
            }
            out[i / 2] = (byte) ((high << 4) | low);
        }
        return out;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private org.springframework.security.core.Authentication authentication() {
        return UsernamePasswordAuthenticationToken.authenticated(
                "machine:hmac-webhook", null,
                List.of(new SimpleGrantedAuthority("ROLE_MACHINE_WEBHOOK")));
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"unauthorized\",\"reason\":\"" + message + "\"}");
    }
}
