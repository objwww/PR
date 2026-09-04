package com.objwww.pr.control.alert.domain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UT-A07：DeferredPolicy 阈值三态（§6.4 评审 #4 选项一）。
 */
class DeferredPolicyTest {

    @Test
    void thresholdThreeStates() {
        DeferredPolicy policy = new DeferredPolicy(10);

        // 低于阈值：正常受理
        assertThat(policy.decide(3, 4)).isEqualTo(DeferredPolicy.Decision.IMMEDIATE);
        // 恰在阈值：仍受理（严格大于才 DEFERRED——边界锚点）
        assertThat(policy.decide(5, 5)).isEqualTo(DeferredPolicy.Decision.IMMEDIATE);
        // 超过阈值：逐条 DEFERRED（行本身即审计）
        assertThat(policy.decide(5, 6)).isEqualTo(DeferredPolicy.Decision.DEFERRED);
        assertThat(policy.decide(11, 0)).isEqualTo(DeferredPolicy.Decision.DEFERRED);
    }

    @Test
    void negativeThresholdRejected() {
        assertThatThrownBy(() -> new DeferredPolicy(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
