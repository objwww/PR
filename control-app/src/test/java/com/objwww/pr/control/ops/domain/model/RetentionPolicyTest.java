package com.objwww.pr.control.ops.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * M5-18 RetentionPolicy 版本化模型（月 RANGE 分区、hot retention 天数、冷层位置、
 * legal hold 标志；insert-only 版本链由仓储层保证）。非法策略一律出生即拒。
 */
class RetentionPolicyTest {

    private static final Instant FIXED = Instant.parse("2026-09-07T10:00:00Z");

    private static RetentionPolicy policy(int version, long hotDays, String cold) {
        return new RetentionPolicy(UUID.randomUUID(), version, hotDays, cold, false, FIXED);
    }

    @Test
    void validPolicyCarriesItsFaces() {
        RetentionPolicy p = policy(2, 90, "file:///var/archive");
        assertThat(p.policyVersion()).isEqualTo(2);
        assertThat(p.hotRetentionDays()).isEqualTo(90);
        assertThat(p.legalHold()).isFalse();
    }

    @Test
    void rejectsNonPositivePolicyVersion() {
        assertThatIllegalArgumentException().isThrownBy(() -> policy(0, 90, "file:///a"));
        assertThatIllegalArgumentException().isThrownBy(() -> policy(-1, 90, "file:///a"));
    }

    @Test
    void rejectsNonPositiveHotRetention() {
        assertThatIllegalArgumentException().isThrownBy(() -> policy(1, 0, "file:///a"));
        assertThatIllegalArgumentException().isThrownBy(() -> policy(1, -5, "file:///a"));
    }

    @Test
    void rejectsBlankColdLocation() {
        assertThatIllegalArgumentException().isThrownBy(() -> policy(1, 90, " "));
    }

    @Test
    void legalHoldActiveFaceIsReleasedAtNull() {
        LegalHold active = new LegalHold(UUID.randomUUID(), "rca_event", "司法取证",
                "ops-1", FIXED, null);
        LegalHold released = new LegalHold(UUID.randomUUID(), "rca_event", "司法取证",
                "ops-1", FIXED, FIXED.plusSeconds(3600));
        assertThat(active.isActive()).isTrue();
        assertThat(released.isActive()).isFalse();
    }
}
