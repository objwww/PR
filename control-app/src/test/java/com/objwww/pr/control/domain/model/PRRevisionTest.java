package com.objwww.pr.control.domain.model;

import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.RevisionFingerprint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 评审修正 #3 / I12：PRRevision 构造时 digest 与 fingerprint 必须已就绪。
 */
class PRRevisionTest {

    private static final Digest DIFF = Digest.sha256Of("diff");
    private static final RevisionFingerprint FP = new RevisionFingerprint(Digest.sha256Of("fp").value());

    @Test
    void constructionRequiresDigestAndFingerprint() {
        assertThrows(NullPointerException.class, () -> new PRRevision(
                UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(40), "main", "b".repeat(40), null,
                null, null, FP, Instant.now(), Instant.now()));
        assertThrows(NullPointerException.class, () -> new PRRevision(
                UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(40), "main", "b".repeat(40), null,
                DIFF, null, null, Instant.now(), Instant.now()));
    }

    @Test
    void validConstruction() {
        assertDoesNotThrow(() -> new PRRevision(
                UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(40), "main", "b".repeat(40), null,
                DIFF, null, FP, Instant.now(), Instant.now()));
    }
}
