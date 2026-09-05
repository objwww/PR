package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-16 评分对象选择规则（评审 P0-7）：多 attempt 场景只取状态机最终选定者
 * （created_at 最晚的 STRUCTURE_VALIDATED），禁止按内容挑最优；REJECTED_* 永不入选。
 */
class FinalReportSelectorTest {

    private static final FinalReportSelector SELECTOR = new FinalReportSelector();

    private static RcaReport report(Instant createdAt, ValidationStatus status) {
        return new RcaReport(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2,
                status, List.of(), "{}", "raw", "m", null, null, null, true, createdAt);
    }

    @Test
    @DisplayName("多 attempt：取 created_at 最晚的已验证报告（即使更早的报告内容'更优'）")
    void picksLatestValidatedMechanically() {
        RcaReport attempt1 = report(Instant.parse("2026-01-01T00:00:00Z"),
                ValidationStatus.STRUCTURE_VALIDATED);
        RcaReport attempt2 = report(Instant.parse("2026-01-01T00:05:00Z"),
                ValidationStatus.STRUCTURE_VALIDATED);

        Optional<RcaReport> selected = SELECTOR.select(List.of(attempt1, attempt2));
        assertThat(selected).contains(attempt2);
    }

    @Test
    @DisplayName("REJECTED_* 永不入选：更新的拒绝报告不挡更早的已验证报告")
    void rejectedReportsNeverSelected() {
        RcaReport validated = report(Instant.parse("2026-01-01T00:00:00Z"),
                ValidationStatus.STRUCTURE_VALIDATED);
        RcaReport rejectedLater = report(Instant.parse("2026-01-01T00:05:00Z"),
                ValidationStatus.REJECTED_SCHEMA_MISMATCH);

        assertThat(SELECTOR.select(List.of(validated, rejectedLater))).contains(validated);
        assertThat(SELECTOR.select(List.of(rejectedLater))).isEmpty();
    }

    @Test
    @DisplayName("同刻并列按 attempt_id 定序；空列表 = empty")
    void tieBreakAndEmpty() {
        RcaReport a = report(Instant.parse("2026-01-01T00:00:00Z"),
                ValidationStatus.STRUCTURE_VALIDATED);
        RcaReport b = report(Instant.parse("2026-01-01T00:00:00Z"),
                ValidationStatus.STRUCTURE_VALIDATED);

        assertThat(SELECTOR.select(List.of(a, b))).isPresent();
        assertThat(SELECTOR.select(List.of())).isEmpty();
        assertThat(FinalReportSelector.SELECTION_POLICY_VERSION)
                .isEqualTo("final-validated-report-v1");
    }
}
