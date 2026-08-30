package com.objwww.pr.control.interfaces.webhook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 验签纯函数（X-Hub-Signature-256 = sha256=HMAC-SHA256(secret, body)） */
class GitHubSignatureVerifierTest {

    private final GitHubSignatureVerifier verifier = new GitHubSignatureVerifier("s3cret");
    private final byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

    @Test
    void validSignatureAccepted() {
        assertThat(verifier.verify(body, verifier.sign(body))).isTrue();
    }

    @Test
    void wrongSecretRejected() {
        String other = new GitHubSignatureVerifier("other-secret").sign(body);
        assertThat(verifier.verify(body, other)).isFalse();
    }

    @Test
    void tamperedBodyRejected() {
        String sig = verifier.sign(body);
        assertThat(verifier.verify("{\"a\":2}".getBytes(StandardCharsets.UTF_8), sig)).isFalse();
    }

    @Test
    void malformedHeadersRejected() {
        assertThat(verifier.verify(body, null)).isFalse();
        assertThat(verifier.verify(body, "")).isFalse();
        assertThat(verifier.verify(body, "sha1=abc")).isFalse();
        assertThat(verifier.verify(body, "sha256=zzzz")).isFalse(); // 非 hex
        assertThat(verifier.verify(body, "sha256=abcd")).isFalse(); // 长度不足
    }
}
