package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.domain.mutation.RcaOperation;

import java.util.Objects;

/**
 * dry-run Action Runner（PB-B4，B v2 §1 Runner 面 Phase B 形态）：真实外部副作用
 * 物理不存在（A10）。只做派发参数完整性自检（digest/resource/params 在档），
 * 结局由 {@code app.alert.mutation.runner-behavior} 决定——SUCCEED（默认，全链
 * 正向走通）或 CHAOS_TIMEOUT（模拟网络超时 → UNKNOWN，供 UNKNOWN→RECONCILING
 * 链演示：锁保持 BUSY，reconcile 裁决）。
 */
public class DryRunActionRunner implements ActionRunner {

    public enum Behavior { SUCCEED, CHAOS_TIMEOUT }

    private final Behavior behavior;

    public DryRunActionRunner(Behavior behavior) {
        this.behavior = Objects.requireNonNull(behavior, "behavior");
    }

    @Override
    public Outcome run(RcaOperation operation) {
        Objects.requireNonNull(operation, "operation");
        if (!operation.dryRun()) {
            throw new IllegalStateException("dry-run Runner 拒绝非 dry_run operation（A10）");
        }
        if (operation.actionDigest() == null || operation.resourceUid() == null
                || operation.paramsJson() == null) {
            throw new IllegalStateException("派发参数不完整（digest/resource/params 必须在档）");
        }
        return behavior == Behavior.CHAOS_TIMEOUT
                ? Outcome.TIMEOUT_UNKNOWN
                : Outcome.EXECUTED;
    }
}
