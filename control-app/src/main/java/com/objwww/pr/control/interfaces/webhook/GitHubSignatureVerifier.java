package com.objwww.pr.control.interfaces.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * GitHub webhook 签名验证（纯函数，可单测）：X-Hub-Signature-256 = "sha256=" + HMAC-SHA256(secret, body)。
 * 常量时间比较防时序侧信道；任何异常/格式不符都返回 false（验签失败 = 401，EX-08）。
 */
public final class GitHubSignatureVerifier {

    private static final String PREFIX = "sha256=";

    private final byte[] secret;

    public GitHubSignatureVerifier(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("webhook secret 不能为空");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** 验签：header 缺失/格式错/算法不支持/摘要不匹配均 false */
    public boolean verify(byte[] body, String signatureHeader) {
        if (body == null || signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        String hex = signatureHeader.substring(PREFIX.length());
        if (hex.length() != 64) {
            return false;
        }
        final byte[] expected;
        try {
            expected = HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            return false; // 非 hex 字符
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(body), expected);
        } catch (Exception e) {
            return false;
        }
    }

    /** 测试与联调辅助：计算合法签名头 */
    public String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
