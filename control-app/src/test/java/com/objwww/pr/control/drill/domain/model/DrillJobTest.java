package com.objwww.pr.control.drill.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DR-02 作业不变量（§7.4 与 V86 CHECK 同律）：CLOSED 必带 outcome+closedAt
 * （CLOSED≠成功，outcome 另存）；非 CLOSED 零 outcome/closedAt——双方向都在
 * 域构造器钉死，与库 CHECK 形成双因子。
 */
class DrillJobTest {

    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");

    private static DrillJob queued() {
        return DrillJob.queued(UUID.randomUUID(), "S3", "F1 幂等失效", "0".repeat(64),
                "arena-195", "op", "{}", "1".repeat(64), "k", NOW);
    }

    @Test
    @DisplayName("queued 工厂：QUEUED 零 outcome/closedAt/stop 面，revision=0")
    void queuedFactory() {
        DrillJob job = queued();
        assertThat(job.state()).isEqualTo(DrillJob.State.QUEUED);
        assertThat(job.outcome()).isNull();
        assertThat(job.closedAt()).isNull();
        assertThat(job.stopRequestedAt()).isNull();
        assertThat(job.revision()).isZero();
    }

    @Test
    @DisplayName("CLOSED 缺 outcome 或 closedAt 即拒绝（恢复核验完成 ≠ 演练目标达成）")
    void closedRequiresOutcomeAndClosedAt() {
        DrillJob base = queued();
        assertThatThrownBy(() -> base.advanced(DrillJob.State.CLOSED, null, null, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLOSED");
        assertThatThrownBy(() -> base.advanced(DrillJob.State.CLOSED, null, "PASS", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("非 CLOSED 携带 outcome/closedAt 即拒绝（终态列与 CLOSED 同生同灭）")
    void nonClosedRejectsOutcome() {
        DrillJob base = queued();
        assertThatThrownBy(() -> base.advanced(DrillJob.State.FAILED, "x", "FAIL", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base.advanced(DrillJob.State.VERIFYING, null, null, NOW, NOW)
                .advanced(DrillJob.State.FAILED, "x", null, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("advanced：revision+1、正文冻结面不变（模板/参数/幂等键/发起人）")
    void advancedKeepsFrozenFields() {
        DrillJob base = queued();
        DrillJob next = base.advanced(DrillJob.State.PRECHECK, null, null, null, NOW);
        assertThat(next.revision()).isEqualTo(1);
        assertThat(next.templateDigest()).isEqualTo(base.templateDigest());
        assertThat(next.paramsJson()).isEqualTo(base.paramsJson());
        assertThat(next.idempotencyKey()).isEqualTo(base.idempotencyKey());
        assertThat(next.operator()).isEqualTo(base.operator());
        DrillJob closed = next.advanced(DrillJob.State.CLOSED, null, "INCONCLUSIVE", NOW, NOW);
        assertThat(closed.outcome()).isEqualTo("INCONCLUSIVE");
        assertThat(closed.closedAt()).isEqualTo(NOW);
    }
}
