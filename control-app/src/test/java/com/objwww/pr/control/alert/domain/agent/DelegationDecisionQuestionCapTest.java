package com.objwww.pr.control.alert.domain.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MC21/P0-1 question 长度闸：>512 截断并带定长标注（裁定=截断非拒绝——question 是
 * 审计台账字段不是执行面输入，超限审计文案不打断整委派批；标注保截断可辨识）。
 */
class DelegationDecisionQuestionCapTest {

    private static final Instant NOW = Instant.parse("2026-09-12T13:00:00Z");

    private static DelegationDecision decision(String question) {
        return new DelegationDecision(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, 0, "g-logs", "logs", "1", question,
                DelegationDecision.Status.APPROVED, null, UUID.randomUUID(), NOW);
    }

    @Test
    @DisplayName("限内 question 原样保留（逐字节不变）")
    void withinCapKeptVerbatim() {
        String question = "查 checkout 网关 5xx 日志";

        assertThat(decision(question).question()).isEqualTo(question);
    }

    @Test
    @DisplayName("超 512 截断 + 定长标注：总长不超限、原文长度可辨识、不以标注冒充原文")
    void overCapTruncatedWithMarker() {
        String huge = "查".repeat(2000);

        String stored = decision(huge).question();

        assertThat(stored.length())
                .as("截断后含标注仍 ≤ 上限").isLessThanOrEqualTo(
                        DelegationDecision.MAX_QUESTION_CHARS);
        assertThat(stored).contains("超长截断").contains("2000 字符");
        assertThat(stored).as("截断体保留前缀（非空壳）").startsWith("查");
        assertThat(stored).as("不以标注冒充原文原文").endsWith("字符]");
    }

    @Test
    @DisplayName("恰好 512（上限值）不触发截断")
    void exactlyAtCapUntouched() {
        String exact = "q".repeat(DelegationDecision.MAX_QUESTION_CHARS);

        assertThat(decision(exact).question()).hasSize(512).doesNotContain("超长");
    }
}
