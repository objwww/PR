package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.FlagdRestoreRecord;
import com.objwww.pr.control.drill.domain.repository.FlagdRestoreLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * DR-05 flagd 截止清扫器（方案 §7.5 DR-05 卡「worker 重启后清扫」；跟随 DrillWorker
 * 孤儿清扫既有模式——无状态、可重入，重启后第一拍即可对账）：
 * 超作业级 deadline 仍在可恢复面（OPEN/UNKNOWN）的台账记录 → 逐条走
 * {@link FlagdConditionalRestore} 条件恢复：
 * <ul>
 *   <li>值仍属本次写入 → 写回原值并收口 RESTORED；</li>
 *   <li>他者已改写 → 收口 CONFLICT（不覆盖、不宣称恢复成功，留人工处理）；</li>
 *   <li>读不到/写回失败 → 不关账（保持可恢复面），下轮清扫重试。</li>
 * </ul>
 * 收口经台账 CAS——与 driver 正常解除并发恰一方生效（重复恢复幂等）。
 */
public final class FlagdRestoreSweeper {

    private static final Logger log = LoggerFactory.getLogger(FlagdRestoreSweeper.class);

    private final FlagdAdminPort port;
    private final FlagdRestoreLedger ledger;
    private final Supplier<Instant> clock;

    public FlagdRestoreSweeper(FlagdAdminPort port, FlagdRestoreLedger ledger,
                               Supplier<Instant> clock) {
        this.port = Objects.requireNonNull(port);
        this.ledger = Objects.requireNonNull(ledger);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 单拍清扫：返回本拍收口的条数（RESTORED/CONFLICT；UNKNOWN 不关账不计数） */
    public int sweep() {
        int handled = 0;
        Instant now = clock.get();
        for (FlagdRestoreRecord record : ledger.findRestorablePastDeadline(now)) {
            FlagdConditionalRestore.Result result = FlagdConditionalRestore.attempt(
                    port, record.flag(), record.appliedVariant(),
                    record.appliedGeneration(), record.restoreTarget());
            switch (result.outcome()) {
                case RESTORED -> {
                    if (ledger.close(record.id(), FlagdRestoreRecord.State.RESTORED,
                            "deadline_sweep:" + result.detail(), now)) {
                        log.warn("flagd 截止清扫：flag={} 已条件恢复原值（{}）",
                                record.flag(), result.detail());
                        handled++;
                    }
                }
                case CONFLICT -> {
                    if (ledger.close(record.id(), FlagdRestoreRecord.State.CONFLICT,
                            "deadline_sweep:" + result.detail(), now)) {
                        log.warn("flagd 截止清扫：flag={} 冲突待处理（{}）——不覆盖他者改写",
                                record.flag(), result.detail());
                        handled++;
                    }
                }
                case UNKNOWN, NOT_APPLIED ->
                    // 不盲写、不关账：保持可恢复面，下轮清扫重试
                        log.warn("flagd 截止清扫：flag={} 暂不可恢复（{}）——留可恢复面重试",
                                record.flag(), result.detail());
            }
        }
        return handled;
    }
}
