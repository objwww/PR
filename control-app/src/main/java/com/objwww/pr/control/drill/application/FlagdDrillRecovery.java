package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.drill.domain.model.DrillTemplate;
import com.objwww.pr.control.drill.domain.model.FlagdRestoreRecord;
import com.objwww.pr.control.drill.domain.repository.FlagdRestoreLedger;
import com.objwww.pr.control.eval.domain.GoldenCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * DR-04 flagd 恢复适配（对称 {@link FlagdDrillInjection}；§7.4 Flagd 段「只在当前值
 * 仍属于本次写入时条件恢复」）：恢复 = 按 V95 台账协议的条件写回——复用
 * {@link FlagdConditionalRestore} 与同一份 {@link FlagdRestoreLedger} 判定/收口，
 * 与 driver deactivate、{@link FlagdRestoreSweeper} 截止清扫共用同一传输面
 * （{@link FlagdAdminPort}），三面恰一方收口（台账 CAS 幂等）。
 *
 * <p>三态映射：RESTORED → RECOVERED；CONFLICT → FAILED（他者已改写，不覆盖——
 * 确定需人工处理，不重试）；UNKNOWN/NOT_APPLIED → UNKNOWN（读不到/写回未生效，
 * 台账保持可恢复面，下拍重试，上限归 worker 恢复窗口截止）。台账缺席时退化为
 * injection 声明面（与 FlagdScenarioDriver.deactivate 无台账路径同律）。
 */
public final class FlagdDrillRecovery {

    private static final Logger log = LoggerFactory.getLogger(FlagdDrillRecovery.class);

    private final FlagdAdminPort port;
    private final FlagdRestoreLedger ledger;
    private final Supplier<Instant> clock;

    public FlagdDrillRecovery(FlagdAdminPort port, FlagdRestoreLedger ledger,
                              Supplier<Instant> clock) {
        this.port = Objects.requireNonNull(port);
        this.ledger = Objects.requireNonNull(ledger);
        this.clock = Objects.requireNonNull(clock);
    }

    DrillRecoveryPort.RecoverOutcome recover(DrillJob job, DrillTemplate template,
                                             GoldenCase golden) {
        GoldenCase.Injection injection = golden.injection();
        if (injection == null) {
            return DrillRecoveryPort.RecoverOutcome.failed(
                    "INJECTION_PARAMS: flagd 场景缺 injection 参数"
                            + "（flag/variant/baseline_variant；配置缺陷，确定不可恢复）: "
                            + golden.scenarioId());
        }
        var record = ledger.findRestorableByFlag(injection.flag())
                .filter(r -> r.scenarioId().equals(golden.scenarioId()));
        // 所有权判据与恢复目标：有台账用实际原值/写入代际；无台账退化 injection 声明面
        String expectedVariant = record.map(FlagdRestoreRecord::appliedVariant)
                .orElse(injection.variant());
        String expectedGeneration = record.map(FlagdRestoreRecord::appliedGeneration)
                .orElse(null);
        String restoreTarget = record.map(FlagdRestoreRecord::restoreTarget)
                .orElse(injection.baselineVariant());
        FlagdConditionalRestore.Result restore = FlagdConditionalRestore.attempt(
                port, injection.flag(), expectedVariant, expectedGeneration, restoreTarget);
        Instant now = clock.get();
        switch (restore.outcome()) {
            case RESTORED -> {
                record.ifPresent(r -> ledger.close(r.id(),
                        FlagdRestoreRecord.State.RESTORED,
                        "drill_recover:" + restore.detail(), now));
                return DrillRecoveryPort.RecoverOutcome.recovered(
                        "flagd 条件恢复已写回: " + restore.detail());
            }
            case CONFLICT -> {
                record.ifPresent(r -> ledger.close(r.id(),
                        FlagdRestoreRecord.State.CONFLICT,
                        "drill_recover:" + restore.detail(), now));
                log.warn("drill {} flagd 条件恢复冲突：flag={} {}——不覆盖他者改写",
                        job.id(), injection.flag(), restore.detail());
                return DrillRecoveryPort.RecoverOutcome.failed(
                        "flag_restore_conflict: " + restore.detail()
                                + "（他者已改写，确定需人工处理，不重试）");
            }
            default -> {
                // UNKNOWN/NOT_APPLIED：不盲写、不关终态账——台账保持可恢复面，下拍重试
                record.ifPresent(r -> ledger.close(r.id(),
                        FlagdRestoreRecord.State.UNKNOWN,
                        "drill_recover:" + restore.detail(), now));
                return DrillRecoveryPort.RecoverOutcome.unknown(
                        restore.outcome() == FlagdConditionalRestore.Outcome.UNKNOWN
                                ? "flag_state_unknown: " + restore.detail()
                                : "flag_not_restored: " + restore.detail());
            }
        }
    }
}
