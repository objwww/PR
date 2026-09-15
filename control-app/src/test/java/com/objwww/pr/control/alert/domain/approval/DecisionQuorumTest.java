package com.objwww.pr.control.alert.domain.approval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DecisionQuorum 域规则单测（PC-C1，§2.6 R7）：双人 = two distinct principals 且
 * distinct roles；同人兼两角不算双人；两人同角色不算双人；任一 denied 即终态。
 */
class DecisionQuorumTest {

    private static DecisionQuorum.Decision approve(String id, String role) {
        return new DecisionQuorum.Decision(id, role, true);
    }

    @Test
    void pcQ01_双人distinct_principal_distinct_role_批准() {
        var outcome = DecisionQuorum.evaluate(2, List.of(
                approve("oncall-a", "ONCALL"), approve("sec-b", "SECURITY")));
        assertThat(outcome).isEqualTo(DecisionQuorum.Outcome.APPROVED);
    }

    @Test
    void pcQ02_同人兼两角色_不算双人() {
        // 理论上被 UNIQUE(request_id, approver_id) 结构性挡住；域规则同样拒绝
        var outcome = DecisionQuorum.evaluate(2, List.of(
                approve("oncall-a", "ONCALL"), approve("oncall-a", "SECURITY")));
        assertThat(outcome).isEqualTo(DecisionQuorum.Outcome.PENDING);
    }

    @Test
    void pcQ03_两人同角色_不算双人() {
        var outcome = DecisionQuorum.evaluate(2, List.of(
                approve("oncall-a", "ONCALL"), approve("oncall-b", "ONCALL")));
        assertThat(outcome).isEqualTo(DecisionQuorum.Outcome.PENDING);
    }

    @Test
    void pcQ04_任一denied_终态() {
        var outcome = DecisionQuorum.evaluate(2, List.of(
                approve("oncall-a", "ONCALL"),
                new DecisionQuorum.Decision("sec-b", "SECURITY", false)));
        assertThat(outcome).isEqualTo(DecisionQuorum.Outcome.DENIED);
    }

    @Test
    void pcQ05_单人决策_不足额_PENDING() {
        assertThat(DecisionQuorum.evaluate(2, List.of(approve("oncall-a", "ONCALL"))))
                .isEqualTo(DecisionQuorum.Outcome.PENDING);
        assertThat(DecisionQuorum.evaluate(2, List.of())).isEqualTo(DecisionQuorum.Outcome.PENDING);
    }

    @Test
    void pcQ06_R2单批_一人即批准() {
        assertThat(DecisionQuorum.evaluate(1, List.of(approve("oncall-a", "ONCALL"))))
                .isEqualTo(DecisionQuorum.Outcome.APPROVED);
    }

    @Test
    void pcQ07_非法required_拒绝() {
        assertThatThrownBy(() -> DecisionQuorum.evaluate(3, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
