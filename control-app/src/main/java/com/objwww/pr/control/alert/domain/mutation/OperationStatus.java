package com.objwww.pr.control.alert.domain.mutation;

/**
 * mutation 域操作状态（设计基线 §2.11）：PREPARED→DISPATCHED→ACKNOWLEDGED→
 * VERIFIED→COMPLETED 主链；DISPATCHED/ACKNOWLEDGED 超时→UNKNOWN（网络 timeout
 * ≠ failed）→RECONCILING→{VERIFIED / RETRYABLE / ESCALATED / FAILED_CONFIRMED}；
 * RETRYABLE 重派回 DISPATCHED（锁保持）。
 *
 * <p>两把正交判定（§2.9 R3 首日正确）：
 * <ul>
 *   <li>{@link #isTerminal()} —— 状态机终点；</li>
 *   <li>{@link #releasesResourceLock()} —— 资源锁可正常释放；<b>ESCALATED 终态但
 *   锁保持至人工裁决</b>（终态≠释放）；UNKNOWN/RECONCILING 期间锁绝不让渡
 *   （B 组不变量：无 external side-effect zombie）。</li>
 * </ul>
 */
public enum OperationStatus {
    PREPARED,
    DISPATCHED,
    ACKNOWLEDGED,
    VERIFIED,
    COMPLETED,
    UNKNOWN,
    RECONCILING,
    RETRYABLE,
    ESCALATED,
    FAILED_CONFIRMED,
    CANCELLED_BEFORE_DISPATCH;

    /** 状态机终点（ESCALATED 亦为终点——人工裁决接管，不再自动迁移） */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED_CONFIRMED || this == ESCALATED
                || this == CANCELLED_BEFORE_DISPATCH;
    }

    /**
     * 资源锁可正常释放（§2.9 释放矩阵）：VERIFIED 事实确认即释放（COMPLETED 只是
     * 记账收尾）；ESCALATED 是终态但<b>不</b>在释放集——锁保持至人工裁决。
     */
    public boolean releasesResourceLock() {
        return this == VERIFIED || this == COMPLETED || this == FAILED_CONFIRMED
                || this == CANCELLED_BEFORE_DISPATCH;
    }

    /** 锁 BUSY（不可授予冲突 mutation）：全部非释放态——含终态 ESCALATED */
    public boolean holdsResourceLock() {
        return !releasesResourceLock();
    }
}
