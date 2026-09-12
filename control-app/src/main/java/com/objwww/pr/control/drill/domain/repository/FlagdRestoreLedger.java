package com.objwww.pr.control.drill.domain.repository;

import com.objwww.pr.control.drill.domain.model.FlagdRestoreRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DR-05 flagd 恢复台账仓储（V95 flagd_restore_ledger；方案 §7.4/§7.5 DR-05 卡）：
 * eval_app 单一读写身份（driver 激活落账 / driver 与 sweeper 条件恢复收口）。
 * <ul>
 *   <li>「可恢复面」= OPEN/UNKNOWN（UNKNOWN = 上次读不到当前值未盲写，仍可重试）；
 *       RESTORED/CONFLICT 终态不回开；</li>
 *   <li>close 为单语句 CAS（仅可恢复面可迁移）——driver 与 sweeper 并发收口恰一方
 *       生效，重复收口幂等落空；</li>
 *   <li>{@link #noop()} = 台账未接线的过渡装配（EvalRunnerConfig 旧两参构造面）：
 *       不落账、查不到、关不上——driver 走无台账条件恢复路径（以 injection 声明的
 *       写入值为所有权判据），重启清扫不可达。</li>
 * </ul>
 */
public interface FlagdRestoreLedger {

    /** 激活落账（写入前已读实际原值/代际；读取失败字段为 null，如实记录） */
    void recordActivation(FlagdRestoreRecord record);

    /** 最新一条可恢复面记录（OPEN/UNKNOWN，created_at 最新优先） */
    Optional<FlagdRestoreRecord> findRestorableByFlag(String flag);

    /** 状态收口 CAS：仅 OPEN/UNKNOWN 可迁移（driver/sweeper 并发恰一方生效） */
    boolean close(UUID id, FlagdRestoreRecord.State to, String reason, Instant now);

    /** 截止清扫面：超 deadline 仍在可恢复面的记录（worker 重启对账入口） */
    List<FlagdRestoreRecord> findRestorablePastDeadline(Instant now);

    /** 台账未接线的过渡面（不落不查不关；生产接线归 EvalRunnerConfig 装配） */
    static FlagdRestoreLedger noop() {
        return new FlagdRestoreLedger() {
            @Override
            public void recordActivation(FlagdRestoreRecord record) {
                // 台账未接线：如实不落（条件恢复退化为无台账路径，重启清扫不可达）
            }

            @Override
            public Optional<FlagdRestoreRecord> findRestorableByFlag(String flag) {
                return Optional.empty();
            }

            @Override
            public boolean close(UUID id, FlagdRestoreRecord.State to, String reason,
                                 Instant now) {
                return false;
            }

            @Override
            public List<FlagdRestoreRecord> findRestorablePastDeadline(Instant now) {
                return List.of();
            }
        };
    }
}
