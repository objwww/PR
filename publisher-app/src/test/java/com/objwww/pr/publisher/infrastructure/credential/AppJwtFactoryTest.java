package com.objwww.pr.publisher.infrastructure.credential;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * App JWT 工厂：RS256 结构（三段 base64url）、iss/iat/exp 语义（exp≤now+10min、
 * iat 回拨 60s）、签名可被公钥验过。测试用临时 RSA keypair 生成 PEM，不用真私钥。
 */
class AppJwtFactoryTest {

    private static KeyPair keyPair;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
    }

    private static Path writePem(Path dir) throws Exception {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(keyPair.getPrivate().getEncoded());
        Path pem = dir.resolve("app-key.pem");
        Files.writeString(pem, "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n");
        return pem;
    }

    @Test
    void createsVerifiableRs256Jwt(@TempDir Path dir) throws Exception {
        AppJwtFactory factory = new AppJwtFactory(123456L, writePem(dir));
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        String jwt = factory.createJwt(now);

        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        Base64.Decoder b64 = Base64.getUrlDecoder();
        String header = new String(b64.decode(parts[0]));
        assertThat(header).contains("\"alg\":\"RS256\"").contains("\"typ\":\"JWT\"");
        String payload = new String(b64.decode(parts[1]));
        assertThat(payload).contains("\"iss\":123456");
        assertThat(payload).contains("\"iat\":" + (now.getEpochSecond() - 60));
        assertThat(payload).contains("\"exp\":" + (now.getEpochSecond() + 600));

        // 公钥验签：签名结构真实有效
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(
                new X509EncodedKeySpec(keyPair.getPublic().getEncoded()));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update((parts[0] + "." + parts[1]).getBytes());
        assertThat(verifier.verify(b64.decode(parts[2]))).isTrue();
    }

    @Test
    void rejectsNonPkcs8Pem(@TempDir Path dir) throws Exception {
        Path pem = dir.resolve("legacy.pem");
        Files.writeString(pem, "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");

        assertThatThrownBy(() -> new AppJwtFactory(1L, pem))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PKCS#8")
                .hasMessageNotContaining("AAAA"); // 私钥内容不进异常消息
    }

    @Test
    void acceptsInjectedKeyForTests() {
        AppJwtFactory factory = new AppJwtFactory(7L, (RSAPrivateKey) keyPair.getPrivate());
        assertThat(factory.createJwt(Instant.now())).contains(".");
    }
}
