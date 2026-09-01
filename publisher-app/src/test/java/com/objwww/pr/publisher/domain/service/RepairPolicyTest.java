package com.objwww.pr.publisher.domain.service;

import com.objwww.pr.shared.PublicationResourceType;
import com.objwww.pr.shared.RepairPolicyTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepairPolicyTest {
    private final RepairPolicy policy = new RepairPolicy();

    @Test
    void onlyCheckRunIsAutomaticAndUnknownFailsClosed() {
        assertThat(policy.decide(PublicationResourceType.CHECK_RUN)).isEqualTo(RepairPolicyTier.AUTO);
        assertThat(policy.decide(PublicationResourceType.REVIEW)).isEqualTo(RepairPolicyTier.MANUAL);
        assertThat(policy.decide("FUTURE_TYPE")).isEqualTo(RepairPolicyTier.MANUAL);
    }
}
