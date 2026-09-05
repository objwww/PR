package com.objwww.pr.control.alert.domain.claim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UT-AM4-21：Claim 契约校验——空/blank claimKey 与 source、空 evidenceRefs、
 * 负数 observedGeneration 一律拒绝；snapshotDigest 可为 null。
 */
class ClaimTest {

    private static Claim valid() {
        return new Claim("k", ClaimStatus.TRUE, "r", "scope", "2026-09-05/1h", 1L,
                List.of("ev-1"), "metrics-agent", null);
    }

    @Test
    void validClaimConstructs() {
        assertThatCode(ClaimTest::valid).doesNotThrowAnyException();
    }

    @Test
    void blankClaimKeyRejected() {
        assertThatThrownBy(() -> new Claim(" ", ClaimStatus.TRUE, "r", "s", "t", 0,
                List.of("ev"), "src", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Claim(null, ClaimStatus.TRUE, "r", "s", "t", 0,
                List.of("ev"), "src", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyEvidenceRefsRejected() {
        // 无证据不成断言
        assertThatThrownBy(() -> new Claim("k", ClaimStatus.TRUE, "r", "s", "t", 0,
                List.of(), "src", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidenceRefs");
        assertThatThrownBy(() -> new Claim("k", ClaimStatus.TRUE, "r", "s", "t", 0,
                null, "src", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void negativeObservedGenerationRejected() {
        assertThatThrownBy(() -> new Claim("k", ClaimStatus.TRUE, "r", "s", "t", -1,
                List.of("ev"), "src", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("observedGeneration");
        assertThatCode(() -> new Claim("k", ClaimStatus.TRUE, "r", "s", "t", 0,
                List.of("ev"), "src", null))
                .as("generation 0 合法")
                .doesNotThrowAnyException();
    }

    @Test
    void blankSourceRejected() {
        assertThatThrownBy(() -> new Claim("k", ClaimStatus.TRUE, "r", "s", "t", 0,
                List.of("ev"), "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Claim("k", ClaimStatus.TRUE, "r", "s", "t", 0,
                List.of("ev"), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullMandatoryFieldsRejected() {
        assertThatThrownBy(() -> new Claim("k", null, "r", "s", "t", 0,
                List.of("ev"), "src", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Claim("k", ClaimStatus.TRUE, null, "s", "t", 0,
                List.of("ev"), "src", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Claim("k", ClaimStatus.TRUE, "r", null, "t", 0,
                List.of("ev"), "src", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Claim("k", ClaimStatus.TRUE, "r", "s", null, 0,
                List.of("ev"), "src", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void snapshotDigestIsNullable() {
        assertThatCode(() -> new Claim("k", ClaimStatus.TRUE, "r", "s", "t", 0,
                List.of("ev"), "src", "snap-1"))
                .doesNotThrowAnyException();
    }
}
