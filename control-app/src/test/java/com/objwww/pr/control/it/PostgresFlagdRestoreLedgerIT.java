package com.objwww.pr.control.it;

import com.objwww.pr.control.drill.application.FlagdAdminPort;
import com.objwww.pr.control.drill.application.FlagdRestoreSweeper;
import com.objwww.pr.control.drill.domain.model.FlagdRestoreRecord;
import com.objwww.pr.control.drill.domain.model.FlagdState;
import com.objwww.pr.control.infrastructure.persistence.PostgresFlagdRestoreLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-05 台账真库集成（V95 flagd_restore_ledger，eval_app 单一读写身份；方案 §11 T9）：
 * recordActivation → findRestorablePastDeadline → close CAS 收口全链 +
 * FlagdRestoreSweeper 条件恢复三态（RESTORED 收口 / CONFLICT 收口 / UNKNOWN 不关账
 * 留可恢复面重试）。
 *
 * <p>本机无 Docker → Testcontainers 整类跳过（NOT_RUN）；真 PG 环境跑 Flyway 全迁移。
 */
class PostgresFlagdRestoreLedgerIT extends PostgresITBase {

    private static final Instant BASE = Instant.parse("2026-09-13T00:00:00Z");

    private PostgresFlagdRestoreLedger ledger;

    @BeforeEach
    void setUpLedger() {
        ledger = new PostgresFlagdRestoreLedger(evalJdbc);
    }

    private static FlagdRestoreRecord record(String flag, Instant deadlineAt) {
        return new FlagdRestoreRecord(UUID.randomUUID(), flag, "S1", 1,
                "off", "g0", "50%", "g1", "off", deadlineAt,
                FlagdRestoreRecord.State.OPEN, null, BASE, BASE);
    }

    /** flagd 管理面假件（内存态；写回即生效，回执 = 写入值） */
    private static final class FakeFlagdAdmin implements FlagdAdminPort {
        private FlagdState current;

        FakeFlagdAdmin(FlagdState current) {
            this.current = current;
        }

        @Override
        public FlagdState read(String flag) {
            return current;
        }

        @Override
        public String write(String flag, String variant) {
            current = new FlagdState(variant, current.generation());
            return variant;
        }
    }

    @Test
    @DisplayName("激活落账→截止读面：OPEN 行可按 flag 读回；deadline 未过不出现在清扫面")
    void recordActivationAndDeadlineRead() {
        FlagdRestoreRecord open = record("paymentFailure", BASE.plusSeconds(3600));
        ledger.recordActivation(open);

        Optional<FlagdRestoreRecord> found = ledger.findRestorableByFlag("paymentFailure");
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(open.id());
        assertThat(found.get().state()).isEqualTo(FlagdRestoreRecord.State.OPEN);
        assertThat(found.get().originalVariant()).isEqualTo("off");
        assertThat(found.get().appliedVariant()).isEqualTo("50%");
        assertThat(found.get().appliedGeneration()).isEqualTo("g1");

        // deadline 未过 → 截止清扫面为空；deadline 已过 → 出现
        assertThat(ledger.findRestorablePastDeadline(BASE.plusSeconds(1800))).isEmpty();
        assertThat(ledger.findRestorablePastDeadline(BASE.plusSeconds(3601)))
                .extracting(FlagdRestoreRecord::id).containsExactly(open.id());
    }

    @Test
    @DisplayName("close CAS：仅 OPEN/UNKNOWN 可收口——首次成功、重复收口幂等落空、"
            + "终态行退出可恢复面")
    void closeCasSemantics() {
        FlagdRestoreRecord open = record("paymentFailure", BASE.minusSeconds(60));
        ledger.recordActivation(open);

        assertThat(ledger.close(open.id(), FlagdRestoreRecord.State.RESTORED,
                "it-close", BASE)).isTrue();
        // 重复收口（driver 与 sweeper 并发恰一方生效面）：CAS 落空
        assertThat(ledger.close(open.id(), FlagdRestoreRecord.State.CONFLICT,
                "it-close-2", BASE)).isFalse();
        // 终态不回开：两个读面都不再可见
        assertThat(ledger.findRestorableByFlag("paymentFailure")).isEmpty();
        assertThat(ledger.findRestorablePastDeadline(BASE)).isEmpty();
    }

    @Test
    @DisplayName("sweeper 全链（真库）：超 deadline OPEN 行 + 当前值仍属本次写入 → "
            + "条件恢复 + CAS 收口 RESTORED")
    void sweeperRestoresAndCloses() {
        FlagdRestoreRecord open = record("paymentFailure", BASE.minusSeconds(60));
        ledger.recordActivation(open);
        // 当前值 = 本次写入值且代际未被他者推进 → 可恢复
        FakeFlagdAdmin admin = new FakeFlagdAdmin(new FlagdState("50%", "g1"));
        FlagdRestoreSweeper sweeper = new FlagdRestoreSweeper(admin, ledger, () -> BASE);

        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(admin.read("paymentFailure").variant()).isEqualTo("off"); // 已写回原值
        assertThat(ledger.findRestorableByFlag("paymentFailure")).isEmpty();
        assertThat(ledger.findRestorablePastDeadline(BASE)).isEmpty();
        // 收口如实带原因（admin 视角读回 state_reason）
        String state = evalJdbc.sql(
                        "SELECT state || '|' || state_reason FROM flagd_restore_ledger"
                                + " WHERE id = :id")
                .param("id", open.id()).query(String.class).single();
        assertThat(state).startsWith("RESTORED|deadline_sweep:");
    }

    @Test
    @DisplayName("sweeper 冲突面（真库）：他者已改写 → CONFLICT 收口，不覆盖他者修改")
    void sweeperConflictClosesWithoutOverwrite() {
        FlagdRestoreRecord open = record("paymentFailure", BASE.minusSeconds(60));
        ledger.recordActivation(open);
        // 他者已改写（值不符）→ CONFLICT
        FakeFlagdAdmin admin = new FakeFlagdAdmin(new FlagdState("75%", "g9"));
        FlagdRestoreSweeper sweeper = new FlagdRestoreSweeper(admin, ledger, () -> BASE);

        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(admin.read("paymentFailure").variant()).isEqualTo("75%"); // 未覆盖
        String state = evalJdbc.sql(
                        "SELECT state || '|' || state_reason FROM flagd_restore_ledger"
                                + " WHERE id = :id")
                .param("id", open.id()).query(String.class).single();
        assertThat(state).startsWith("CONFLICT|deadline_sweep:");
    }

    @Test
    @DisplayName("sweeper 读不到面（真库）：UNKNOWN 不关账、不盲写——留可恢复面下轮重试")
    void sweeperUnknownKeepsRestorable() {
        FlagdRestoreRecord open = record("paymentFailure", BASE.minusSeconds(60));
        ledger.recordActivation(open);
        // 读空（服务端未返回当前值）→ UNKNOWN
        FakeFlagdAdmin admin = new FakeFlagdAdmin(new FlagdState(null, null));
        FlagdRestoreSweeper sweeper = new FlagdRestoreSweeper(admin, ledger, () -> BASE);

        assertThat(sweeper.sweep()).isZero();
        // 不关账：仍在可恢复面，下轮清扫可重试
        List<FlagdRestoreRecord> restorable = ledger.findRestorablePastDeadline(BASE);
        assertThat(restorable).extracting(FlagdRestoreRecord::id)
                .containsExactly(open.id());
        assertThat(restorable.getFirst().state())
                .isEqualTo(FlagdRestoreRecord.State.OPEN);
    }
}
