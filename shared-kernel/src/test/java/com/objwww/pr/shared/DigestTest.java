package com.objwww.pr.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DigestTest {

    private static final String HEX64 = "a".repeat(64);

    @Test
    void acceptsLowercaseHex64() {
        assertDoesNotThrow(() -> new Digest(HEX64));
        assertEquals(HEX64, new Digest(HEX64).value());
    }

    @Test
    void rejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> new Digest(null));
        assertThrows(IllegalArgumentException.class, () -> new Digest("a".repeat(63)));
        assertThrows(IllegalArgumentException.class, () -> new Digest("a".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> new Digest("A".repeat(64))); // 仅小写
        assertThrows(IllegalArgumentException.class, () -> new Digest("g".repeat(64)));
    }

    @Test
    void sha256OfIsDeterministicLowercaseHex() {
        Digest d = Digest.sha256Of("hello");
        assertEquals(64, d.value().length());
        assertEquals(d, Digest.sha256Of("hello"));
    }

    @Test
    void revisionFingerprintReusesDigestValidation() {
        assertDoesNotThrow(() -> new RevisionFingerprint(HEX64));
        assertThrows(IllegalArgumentException.class, () -> new RevisionFingerprint("xyz"));
    }
}
