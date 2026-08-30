package com.objwww.pr.publisher.infrastructure.credential;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * GitHub App JWT 工厂（RS256，纯 JDK 实现，不加 jwt 库依赖）：
 * header {alg:RS256, typ:JWT}，payload {iss=appId, iat=now-60s（时钟偏移容忍）, exp=now+600s（≤10min 上限）}。
 *
 * <p>私钥纪律：只接受 PKCS#8 PEM（{@code -----BEGIN PRIVATE KEY-----}），
 * 从配置路径读入内存后不出进程；任何异常消息只带路径不带内容。
 */
public class AppJwtFactory {

    /** GitHub App JWT 有效期上限 10 分钟；iat 回拨 60s 容忍时钟偏移（GitHub 官方建议） */
    private static final long JWT_TTL_SECONDS = 600;
    private static final long IAT_BACKDATE_SECONDS = 60;

    private final long appId;
    private final RSAPrivateKey privateKey;

    public AppJwtFactory(long appId, Path privateKeyPemPath) {
        this(appId, loadPem(privateKeyPemPath));
    }

    /** 测试可直接注入 key */
    public AppJwtFactory(long appId, RSAPrivateKey privateKey) {
        this.appId = appId;
        this.privateKey = Objects.requireNonNull(privateKey);
    }

    /** 签发当前时刻的 App JWT */
    public String createJwt(Instant now) {
        Objects.requireNonNull(now, "now");
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes());
        String payloadJson = "{\"iss\":" + appId
                + ",\"iat\":" + (now.getEpochSecond() - IAT_BACKDATE_SECONDS)
                + ",\"exp\":" + (now.getEpochSecond() + JWT_TTL_SECONDS) + "}";
        String payload = b64.encodeToString(payloadJson.getBytes());
        String signingInput = header + "." + payload;
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(signingInput.getBytes());
            return signingInput + "." + b64.encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("App JWT 签名失败", e);
        }
    }

    private static RSAPrivateKey loadPem(Path path) {
        Objects.requireNonNull(path, "privateKeyPemPath");
        String pem;
        try {
            pem = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("App 私钥读取失败: " + path, e);
        }
        String body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        if (body.isEmpty() || !pem.contains("BEGIN PRIVATE KEY")) {
            throw new IllegalArgumentException("App 私钥须为 PKCS#8 PEM（BEGIN PRIVATE KEY）: " + path);
        }
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return (RSAPrivateKey) keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("App 私钥解析失败（不含内容，仅路径）: " + path, e);
        }
    }
}
