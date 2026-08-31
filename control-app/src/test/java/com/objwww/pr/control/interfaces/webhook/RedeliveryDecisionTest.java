package com.objwww.pr.control.interfaces.webhook;

import com.objwww.pr.control.domain.model.InboxState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT：重投应答决策（方案 §4.2 主键冲突分支，I9/I16）——六态全映射。
 */
class RedeliveryDecisionTest {

    @Test
    void terminalWithOutcomeReplaysDuplicate() {
        assertThat(RedeliveryDecision.of(InboxState.PROCESSED)).isEqualTo(RedeliveryDecision.DUPLICATE);
        assertThat(RedeliveryDecision.of(InboxState.IGNORED)).isEqualTo(RedeliveryDecision.DUPLICATE);
    }

    @Test
    void inFlightStatesAnswerProcessing() {
        assertThat(RedeliveryDecision.of(InboxState.RECEIVED)).isEqualTo(RedeliveryDecision.PROCESSING);
        assertThat(RedeliveryDecision.of(InboxState.PROCESSING)).isEqualTo(RedeliveryDecision.PROCESSING);
        assertThat(RedeliveryDecision.of(InboxState.RETRY_WAIT)).isEqualTo(RedeliveryDecision.PROCESSING);
    }

    @Test
    void deadLetterAnswersDeadLetterWithoutWaking() {
        assertThat(RedeliveryDecision.of(InboxState.DEAD_LETTER)).isEqualTo(RedeliveryDecision.DEAD_LETTER);
    }

    @Test
    void allSixStatesAreMapped() {
        // 防漏态：枚举扩态时本测试迫使决策者显式表态
        for (InboxState state : InboxState.values()) {
            assertThat(RedeliveryDecision.of(state)).isNotNull();
        }
        assertThat(InboxState.values()).hasSize(6);
    }
}
