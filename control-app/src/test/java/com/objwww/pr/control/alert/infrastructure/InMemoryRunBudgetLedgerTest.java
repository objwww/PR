package com.objwww.pr.control.alert.infrastructure;

import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.budget.ReservationKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BA-108 对齐钉（L0）：InMemoryRunBudgetLedger 无限额行 = fail-closed 拒绝，与
 * PG 侧（AlertRunBudgetLedgerIT itB09"别的 run 无限额行 → 条件 UPDATE 0 行，
 * fail-closed 拒"）同断言语义——双实现同语义钉。假件不再是"无行=不限"失真面。
 */
class InMemoryRunBudgetLedgerTest {

    @Test
    @DisplayName("BA-108：无限额行 reserve 拒绝且不落 entry（与 PG itB09 同语义对照案）")
    void noLimitRowRejectsFailClosed() {
        InMemoryRunBudgetLedger ledger = new InMemoryRunBudgetLedger();
        UUID run = UUID.randomUUID();
        ReservationKey key = ReservationKey.forReport(run);

        assertThat(ledger.reserve(key, 1).allowed())
                .as("无限额行 → fail-closed 拒绝（PG 同语义）").isFalse();
        assertThat(ledger.entryCount())
                .as("拒绝路径不落 entry（与 PG 0 行更新同观测面）").isZero();
    }

    @Test
    @DisplayName("播种后正常强制：限额内放行、超限拒绝、拒绝不落 entry")
    void seededRowsEnforceNormally() {
        InMemoryRunBudgetLedger ledger = new InMemoryRunBudgetLedger();
        UUID run = UUID.randomUUID();
        ledger.ensureLimit(run, BudgetKind.TOOL_CALL, 2);
        ReservationKey first = new ReservationKey(run, UUID.randomUUID(),
                UUID.randomUUID(), 1L, BudgetKind.TOOL_CALL);
        ReservationKey second = new ReservationKey(run, UUID.randomUUID(),
                UUID.randomUUID(), 2L, BudgetKind.TOOL_CALL);
        ReservationKey third = new ReservationKey(run, UUID.randomUUID(),
                UUID.randomUUID(), 3L, BudgetKind.TOOL_CALL);

        assertThat(ledger.reserve(first, 1).allowed()).isTrue();
        assertThat(ledger.reserve(second, 1).allowed()).isTrue();
        assertThat(ledger.reserve(third, 1).allowed())
                .as("超限拒绝（2/2 已耗）").isFalse();
        assertThat(ledger.entryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("幂等重放：同键重复 reserve 放行且不重复计数（与 PG UPSERT 幂等同语义）")
    void duplicateKeyReplayAllowed() {
        InMemoryRunBudgetLedger ledger = new InMemoryRunBudgetLedger();
        UUID run = UUID.randomUUID();
        ledger.ensureLimit(run, BudgetKind.STEP, 1);
        ReservationKey key = new ReservationKey(run, UUID.randomUUID(),
                UUID.randomUUID(), 1L, BudgetKind.STEP);
        assertThat(ledger.reserve(key, 1).allowed()).isTrue();
        assertThat(ledger.reserve(key, 1).allowed()).isTrue();
        assertThat(ledger.consumedOf(run, BudgetKind.STEP)).isEqualTo(1);
    }
}
