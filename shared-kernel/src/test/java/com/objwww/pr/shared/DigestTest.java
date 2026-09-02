package com.objwww.pr.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Digest 单元测试（M4-A 验证）。
 */
class DigestTest {

    @Test
    void testConstructor_validHex() {
        String validHex = "a".repeat(64);
        Digest digest = new Digest(validHex);
        assertEquals(validHex, digest.value());
        assertEquals(validHex, digest.hex());
    }

    @Test
    void testConstructor_nullValue() {
        assertThrows(IllegalArgumentException.class, () -> {
            new Digest(null);
        });
    }

    @Test
    void testConstructor_invalidLength() {
        assertThrows(IllegalArgumentException.class, () -> {
            new Digest("abc");
        });
        // M0 既有用例：63/65 长度拒绝
        assertThrows(IllegalArgumentException.class, () -> {
            new Digest("a".repeat(63));
        });
        assertThrows(IllegalArgumentException.class, () -> {
            new Digest("a".repeat(65));
        });
    }

    @Test
    void testConstructor_invalidCharacters() {
        assertThrows(IllegalArgumentException.class, () -> {
            new Digest("g".repeat(64)); // 'g' 不是十六进制字符
        });
        // M0 既有用例：大写 hex 拒绝（仅小写）
        assertThrows(IllegalArgumentException.class, () -> {
            new Digest("A".repeat(64));
        });
    }

    @Test
    void testSha256Of_string() {
        String input = "hello";
        Digest digest = Digest.sha256Of(input);

        assertNotNull(digest);
        assertEquals(64, digest.value().length());
        // SHA-256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                     digest.value());
    }

    @Test
    void testSha256Of_emptyString() {
        Digest digest = Digest.sha256Of("");

        assertNotNull(digest);
        assertEquals(64, digest.value().length());
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                     digest.value());
    }

    @Test
    void testSha256Of_bytes() {
        byte[] input = "hello".getBytes();
        Digest digest = Digest.sha256Of(new String(input));

        assertNotNull(digest);
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                     digest.value());
    }

    @Test
    void testEquals_sameValue() {
        Digest d1 = new Digest("a".repeat(64));
        Digest d2 = new Digest("a".repeat(64));

        assertEquals(d1, d2);
        assertEquals(d1.hashCode(), d2.hashCode());
    }

    @Test
    void testEquals_differentValue() {
        Digest d1 = new Digest("a".repeat(64));
        Digest d2 = new Digest("b".repeat(64));

        assertNotEquals(d1, d2);
    }

    @Test
    void testToString() {
        String hex = "a".repeat(64);
        Digest digest = new Digest(hex);

        assertTrue(digest.toString().contains(hex));
    }

    @Test
    void testHex() {
        String hex = "abc123".repeat(10) + "abcd";
        Digest digest = new Digest(hex);

        assertEquals(hex, digest.hex());
    }

    @Test
    void revisionFingerprintReusesDigestValidation() {
        // M0 既有用例：RevisionFingerprint 集成验证
        String validHex = "a".repeat(64);
        assertDoesNotThrow(() -> new RevisionFingerprint(validHex));
        assertThrows(IllegalArgumentException.class, () -> new RevisionFingerprint("xyz"));
    }
}
