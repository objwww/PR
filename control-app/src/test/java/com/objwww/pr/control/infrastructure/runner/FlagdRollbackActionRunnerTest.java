package com.objwww.pr.control.infrastructure.runner;

import com.objwww.pr.control.alert.application.mutation.ActionRunner;
import com.objwww.pr.control.alert.domain.mutation.RcaOperation;
import com.objwww.pr.control.drill.application.FlagdAdminPort;
import com.objwww.pr.control.drill.domain.model.FlagdRestoreRecord;
import com.objwww.pr.control.drill.domain.model.FlagdState;
import com.objwww.pr.control.drill.domain.repository.ChangeEventLedger;
import com.objwww.pr.control.drill.domain.repository.FlagdRestoreLedger;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BA-191 flagd 回滚真执行面单测：白名单命中后的真派发结局全谱——成功落真实
 * change_event ROLLBACK 行；他者已写回幂等收口不重复落账；确定性判败（资源非旗标/
 * 缺 service/台账无记录/CONFLICT/账本落行失败）FAILED 且不落账；传输未知
 * TIMEOUT_UNKNOWN 不猜失败。
 */
class FlagdRollbackActionRunnerTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-19T10:00:00Z"),
            ZoneOffset.UTC);
    private static final String UID = "flag://flagd/paymentFailure";
    private static final UUID OP_ID = UUID.randomUUID();

    /** flagd 管理面假件：可变当前值/代际 + 读写失败注入 + 触网计数 */
    static final class FakeFlagdPort implements FlagdAdminPort {
        FlagdState current;
        boolean failRead;
        boolean failWrite;
        int reads;
        int writes;

        FakeFlagdPort(String variant, String generation) {
            this.current = new FlagdState(variant, generation);
        }

        @Override
        public FlagdState read(String flag) {
            reads++;
            if (failRead) {
                throw new RuntimeException("read boom");
            }
            return current;
        }

        @Override
        public String write(String flag, String variant) {
            writes++;
            if (failWrite) {
                throw new RuntimeException("write boom");
            }
            current = new FlagdState(variant,
                    current.generation() == null ? null : current.generation() + 1);
            return variant;
        }
    }

    static final class FakeRestoreLedger implements FlagdRestoreLedger {
        FlagdRestoreRecord record;
        final List<String> closures = new ArrayList<>();

        @Override
        public void recordActivation(FlagdRestoreRecord r) {
        }

        @Override
        public Optional<FlagdRestoreRecord> findRestorableByFlag(String flag) {
            return Optional.ofNullable(record);
        }

        @Override
        public boolean close(UUID id, FlagdRestoreRecord.State to, String reason,
                             Instant now) {
            closures.add(to + ":" + reason);
            return true;
        }

        @Override
        public List<FlagdRestoreRecord> findRestorablePastDeadline(Instant now) {
            return List.of();
        }
    }

    static final class FakeChangeEvents implements ChangeEventLedger {
        final List<RollbackFact> rollbacks = new ArrayList<>();
        boolean failRollback;

        @Override
        public void recordDeploy(DeployFact fact) {
        }

        @Override
        public void recordRollback(RollbackFact fact) {
            if (failRollback) {
                throw new RuntimeException("insert boom");
            }
            rollbacks.add(fact);
        }
    }

    private static FlagdRestoreRecord openRecord() {
        Instant now = FIXED.instant();
        return new FlagdRestoreRecord(UUID.randomUUID(), "paymentFailure", "S27", 1,
                "off", "g1", "50%", "g2", "off", now.plusSeconds(3600),
                FlagdRestoreRecord.State.OPEN, null, now, now);
    }

    private static RcaOperation realOp(String resourceUid, String paramsJson) {
        return RcaOperation.prepareReal(OP_ID, UUID.randomUUID(), UUID.randomUUID(), null,
                "service.rollback", "a".repeat(64), resourceUid, 1, paramsJson,
                FIXED.instant());
    }

    private static FlagdRollbackActionRunner runner(FakeFlagdPort port,
            FakeRestoreLedger ledger, FakeChangeEvents events) {
        return new FlagdRollbackActionRunner(port, ledger, events, FIXED);
    }

    @Test
    @DisplayName("白名单命中·真执行成功：旗标写回 baseline + 落真实 ROLLBACK 行 + 台账 RESTORED")
    void successWritesFlagAndRollbackLedgerRow() {
        FakeFlagdPort port = new FakeFlagdPort("50%", "g2");
        FakeRestoreLedger ledger = new FakeRestoreLedger();
        ledger.record = openRecord();
        FakeChangeEvents events = new FakeChangeEvents();

        ActionRunner.Result result = runner(port, ledger, events)
                .runDetailed(realOp(UID, "{\"service\":\"payment\",\"reason\":\"回滚\"}"));

        assertThat(result.outcome()).isEqualTo(ActionRunner.Outcome.EXECUTED);
        assertThat(result.detail()).contains("paymentFailure").contains("已回滚");
        assertThat(port.current.variant()).isEqualTo("off");
        assertThat(port.writes).isEqualTo(1);
        assertThat(ledger.closures).anyMatch(c -> c.startsWith("RESTORED:operation_rollback:"));
        assertThat(events.rollbacks).hasSize(1);
        ChangeEventLedger.RollbackFact row = events.rollbacks.get(0);
        assertThat(row.deployId()).isEqualTo("flagd-rollback-" + OP_ID);
        assertThat(row.service()).isEqualTo("payment");
        assertThat(row.environment()).isEqualTo("production");
        assertThat(row.rollbackOf()).isEqualTo(Digest.sha256Of(
                "flagd:paymentFailure:off->50%").value());
        assertThat(row.configDigest()).isEqualTo(Digest.sha256Of(
                "flagd:paymentFailure:50%->off").value());
    }

    @Test
    @DisplayName("他者已写回：目标态核验达成 EXECUTED，但不重复落 ROLLBACK 行（不冒充本次处置）")
    void alreadyRestoredByOtherDoesNotRecordRollbackRow() {
        FakeFlagdPort port = new FakeFlagdPort("off", "g9"); // 非本次写入面但已在目标值
        FakeRestoreLedger ledger = new FakeRestoreLedger();
        ledger.record = openRecord();
        FakeChangeEvents events = new FakeChangeEvents();

        ActionRunner.Result result = runner(port, ledger, events)
                .runDetailed(realOp(UID, "{\"service\":\"payment\"}"));

        assertThat(result.outcome()).isEqualTo(ActionRunner.Outcome.EXECUTED);
        assertThat(result.detail()).contains("目标态核验达成");
        assertThat(port.writes).isZero();
        assertThat(events.rollbacks).isEmpty();
        assertThat(ledger.closures).anyMatch(c -> c.startsWith("RESTORED:"));
    }

    @Test
    @DisplayName("CONFLICT（他者已改写）：FAILED 诚实判败 + 台账 CONFLICT + 不落 ROLLBACK 行")
    void conflictIsHonestFailure() {
        FakeFlagdPort port = new FakeFlagdPort("80%", "g7");
        FakeRestoreLedger ledger = new FakeRestoreLedger();
        ledger.record = openRecord();
        FakeChangeEvents events = new FakeChangeEvents();

        ActionRunner.Result result = runner(port, ledger, events)
                .runDetailed(realOp(UID, "{\"service\":\"payment\"}"));

        assertThat(result.outcome()).isEqualTo(ActionRunner.Outcome.FAILED);
        assertThat(result.detail()).contains("flag_restore_conflict").contains("不覆盖");
        assertThat(port.writes).isZero();
        assertThat(events.rollbacks).isEmpty();
        assertThat(ledger.closures).anyMatch(c -> c.startsWith("CONFLICT:"));
    }

    @Test
    @DisplayName("传输未知（读失败）：TIMEOUT_UNKNOWN 不猜失败 + 台账留可恢复面 + 不落账")
    void readFailureIsTimeoutUnknown() {
        FakeFlagdPort port = new FakeFlagdPort("50%", "g2");
        port.failRead = true;
        FakeRestoreLedger ledger = new FakeRestoreLedger();
        ledger.record = openRecord();
        FakeChangeEvents events = new FakeChangeEvents();

        ActionRunner.Result result = runner(port, ledger, events)
                .runDetailed(realOp(UID, "{\"service\":\"payment\"}"));

        assertThat(result.outcome()).isEqualTo(ActionRunner.Outcome.TIMEOUT_UNKNOWN);
        assertThat(result.detail()).contains("flag_state_unknown");
        assertThat(events.rollbacks).isEmpty();
        assertThat(ledger.closures).anyMatch(c -> c.startsWith("UNKNOWN:"));
    }

    @Test
    @DisplayName("台账无可恢复记录：FAILED 不盲写（原值/代际不编造），零触网零落账")
    void missingLedgerRecordFailsClosed() {
        FakeFlagdPort port = new FakeFlagdPort("50%", "g2");
        FakeRestoreLedger ledger = new FakeRestoreLedger(); // 无记录
        FakeChangeEvents events = new FakeChangeEvents();

        ActionRunner.Result result = runner(port, ledger, events)
                .runDetailed(realOp(UID, "{\"service\":\"payment\"}"));

        assertThat(result.outcome()).isEqualTo(ActionRunner.Outcome.FAILED);
        assertThat(result.detail()).contains("flagd_rollback_ledger_absent").contains("不盲写");
        assertThat(port.reads).isZero();
        assertThat(port.writes).isZero();
        assertThat(events.rollbacks).isEmpty();
    }

    @Test
    @DisplayName("资源身份非 flagd 旗标：FAILED 未触网不假装执行")
    void nonFlagResourceFailsBeforeTouchingWire() {
        FakeFlagdPort port = new FakeFlagdPort("50%", "g2");
        FakeRestoreLedger ledger = new FakeRestoreLedger();
        ledger.record = openRecord();
        FakeChangeEvents events = new FakeChangeEvents();

        ActionRunner.Result result = runner(port, ledger, events)
                .runDetailed(realOp("res://prod/payment", "{\"service\":\"payment\"}"));

        assertThat(result.outcome()).isEqualTo(ActionRunner.Outcome.FAILED);
        assertThat(result.detail()).contains("flagd_rollback_resource_mismatch");
        assertThat(port.reads).isZero();
        assertThat(events.rollbacks).isEmpty();
    }

    @Test
    @DisplayName("意图缺 service 关联键：FAILED 不编造账本关联面，未触网")
    void missingServiceKeyFailsClosed() {
        FakeFlagdPort port = new FakeFlagdPort("50%", "g2");
        FakeRestoreLedger ledger = new FakeRestoreLedger();
        ledger.record = openRecord();
        FakeChangeEvents events = new FakeChangeEvents();

        ActionRunner.Result result = runner(port, ledger, events)
                .runDetailed(realOp(UID, "{\"reason\":\"无理由面\"}"));

        assertThat(result.outcome()).isEqualTo(ActionRunner.Outcome.FAILED);
        assertThat(result.detail()).contains("flagd_rollback_service_absent");
        assertThat(port.reads).isZero();
        assertThat(events.rollbacks).isEmpty();
    }

    @Test
    @DisplayName("账本落行失败：生效而无证据 fail-closed 判败（FAILED），不假装成功")
    void rollbackLedgerWriteFailureIsHonestFailure() {
        FakeFlagdPort port = new FakeFlagdPort("50%", "g2");
        FakeRestoreLedger ledger = new FakeRestoreLedger();
        ledger.record = openRecord();
        FakeChangeEvents events = new FakeChangeEvents();
        events.failRollback = true;

        ActionRunner.Result result = runner(port, ledger, events)
                .runDetailed(realOp(UID, "{\"service\":\"payment\"}"));

        assertThat(result.outcome()).isEqualTo(ActionRunner.Outcome.FAILED);
        assertThat(result.detail()).contains("flagd_rollback_evidence_lost")
                .contains("生效而无证据");
        assertThat(port.current.variant()).isEqualTo("off"); // 旗标如实已回滚
        assertThat(events.rollbacks).isEmpty();
    }

    @Test
    @DisplayName("dry_run operation / 非 service.rollback 动作：显式炸出（装配缺陷）")
    void dryRunAndWrongActionRejected() {
        FakeFlagdPort port = new FakeFlagdPort("50%", "g2");
        FakeRestoreLedger ledger = new FakeRestoreLedger();
        ledger.record = openRecord();
        FlagdRollbackActionRunner runner = runner(port, ledger, new FakeChangeEvents());

        RcaOperation dry = RcaOperation.prepare(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, "service.rollback", "a".repeat(64), UID, 1,
                "{\"service\":\"payment\"}", FIXED.instant());
        assertThatThrownBy(() -> runner.run(dry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dry_run");
        RcaOperation wrongAction = RcaOperation.prepareReal(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null, "chaos.resolve",
                "a".repeat(64), UID, 1, "{}", FIXED.instant());
        assertThatThrownBy(() -> runner.run(wrongAction))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("service.rollback");
    }
}
