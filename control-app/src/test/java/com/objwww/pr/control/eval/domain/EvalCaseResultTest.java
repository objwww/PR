package com.objwww.pr.control.eval.domain;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.domain.ScenarioMetrics.ScoringVerdict;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3-14 域不变量：verdict 形态与评分对象存在性一致（DECIDABLE/UNRESOLVED 必有报告、
 * 结构失败/缺席无报告）、hit 仅 DECIDABLE、终态工厂守卫——DB 约束（V10 CHECK）的
 * 内存侧镜像，M3-15/16 评分器消费前的第一道门。
 */
class EvalCaseResultTest {

    private static final TypedRootCause EXPECTED =
            new TypedRootCause("payment", "BUSINESS_ERROR_RATE", "PAYMENT_CHARGE_FAILURE");

    private static Builder builder() {
        return new Builder();
    }

    /** 测试专用最小装配器（规避 20 参构造器的行宽） */
    private static final class Builder {
        private ScoringVerdict verdict = ScoringVerdict.DECIDABLE;
        private boolean hit = true;
        private UUID reportId = UUID.randomUUID();
        private UUID attemptId = UUID.randomUUID();

        private Builder verdict(ScoringVerdict verdict) {
            this.verdict = verdict;
            return this;
        }

        private Builder hit(boolean hit) {
            this.hit = hit;
            return this;
        }

        private Builder noReport() {
            this.reportId = null;
            this.attemptId = null;
            return this;
        }

        private EvalCaseResult build() {
            return new EvalCaseResult(UUID.randomUUID(), UUID.randomUUID(), "S1", 1,
                    "state-machine-selected-v1", null, attemptId, reportId,
                    verdict, hit, EXPECTED, null, List.of("checkout"), null,
                    0, 0, 1, null, false, null);
        }
    }

    @Test
    @DisplayName("DECIDABLE/UNRESOLVED 必带报告；STRUCTURE_REJECTED/TIMEOUT 必无报告")
    void verdictShapeMustMatchScoredReportPresence() {
        assertThat(builder().build().rootCauseHit()).isTrue();

        assertThat(builder().verdict(ScoringVerdict.UNRESOLVED).hit(false).build().verdict())
                .isEqualTo(ScoringVerdict.UNRESOLVED);

        assertThatThrownBy(() -> builder().noReport().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scored_report_id");
        assertThatThrownBy(() -> builder().verdict(ScoringVerdict.UNRESOLVED)
                .hit(false).noReport().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scored_report_id");

        for (ScoringVerdict noReportVerdict : List.of(
                ScoringVerdict.STRUCTURE_REJECTED, ScoringVerdict.TIMEOUT_OR_ABSENT)) {
            EvalCaseResult result = builder().verdict(noReportVerdict).hit(false)
                    .noReport().build();
            assertThat(result.verdict()).isEqualTo(noReportVerdict);
        }
        assertThatThrownBy(() -> builder().verdict(ScoringVerdict.STRUCTURE_REJECTED)
                .hit(false).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scored_report_id");
    }

    @Test
    @DisplayName("hit 仅 DECIDABLE：非 DECIDABLE 带 hit=true 抛出；round_no 从 1 起")
    void hitOnlyDecidableAndRoundGuard() {
        assertThatThrownBy(() -> builder().verdict(ScoringVerdict.UNRESOLVED).hit(true).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root_cause_hit");

        assertThatThrownBy(() -> new EvalCaseResult(UUID.randomUUID(), UUID.randomUUID(),
                "S1", 0, "v1", null, null, null, ScoringVerdict.TIMEOUT_OR_ABSENT,
                false, EXPECTED, null, List.of(), null, 0, 0, 0, null, false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("round_no");
    }

    @Test
    @DisplayName("EvalRun 终态工厂：RUNNING 拒入 terminal；SUCCEEDED 缺快照/计数抛出")
    void evalRunTerminalGuards() {
        UUID id = UUID.randomUUID();
        EvalRunMetadata metadata = new EvalRunMetadata(1, "d", Digest.sha256Of("r"), 1,
                "m", "p", Digest.sha256Of("pd"), Digest.sha256Of("td"),
                null, null, null, null, null, "pf", Digest.sha256Of("ar"), "sd",
                "grader-test-v1");

        assertThat(EvalRun.running(id, metadata, Instant.now()).state())
                .isEqualTo(EvalRun.EvalRunState.RUNNING);

        assertThatThrownBy(() -> EvalRun.terminal(id, metadata,
                EvalRun.EvalRunState.RUNNING, Instant.now(), Instant.now(), null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RUNNING");
        assertThatThrownBy(() -> EvalRun.terminal(id, metadata,
                EvalRun.EvalRunState.SUCCEEDED, Instant.now(), Instant.now(),
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SUCCEEDED");
        // FAILED 允许无快照（中途夭折）
        assertThat(EvalRun.terminal(id, metadata, EvalRun.EvalRunState.FAILED,
                Instant.now(), Instant.now(), null, null, null).state())
                .isEqualTo(EvalRun.EvalRunState.FAILED);
    }
}
