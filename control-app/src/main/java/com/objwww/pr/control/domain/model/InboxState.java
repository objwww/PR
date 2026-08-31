package com.objwww.pr.control.domain.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Webhook Inbox 六态机（M1 技术方案 v1.2 §4.1/§4.2，UT-11）。
 * 防什么：防"FAILED 一态两义"导致的死信被重投悄悄复活（评审修正 #3），
 * 防终态被晚到回写改写（I14/I16）。
 *
 * <p>迁移表：
 * <ul>
 *   <li>RECEIVED → PROCESSING（领取，lease_epoch+1 由 claim SQL 完成）</li>
 *   <li>PROCESSING → PROCESSING（崩溃回收重领：租约过期后被再次领取，状态不变、
 *       租约易主，lease_epoch+1 栅栏旧 Processor）/ PROCESSED / RETRY_WAIT
 *       / DEAD_LETTER / IGNORED（LWW 快筛拦截 ST-11、非处理事件留痕 ST-16 的归宿）</li>
 *   <li>RETRY_WAIT → PROCESSING（退避到点重领）。该迁移的一次完整循环必须伴随
 *       attempt_count+1——迁移表只判状态对，计数递增由回写 SQL 保证
 *       （completeRetryWait/completeDeadLetter 的 attempt_count = attempt_count+1，
 *       §4.2 失败路径；重领本身不再重复计数）</li>
 *   <li>PROCESSED / IGNORED / DEAD_LETTER 为终态，任何迁出抛 IllegalStateException。
 *       DEAD_LETTER 的显式管理复活（I16）走运维 SQL 直改，不经本机。</li>
 * </ul>
 */
public enum InboxState {

    /** 已受理落库，待领取 */
    RECEIVED,
    /** 已被 Processor 领取（持租约） */
    PROCESSING,
    /** 可恢复的暂败：next_retry_at 到点后自动重领 */
    RETRY_WAIT,
    /** 终态：处理完成 */
    PROCESSED,
    /** 终态：被忽略（陈旧事件快筛 / 非处理事件留痕） */
    IGNORED,
    /** 终态：重试耗尽或不可恢复（如畸形载荷）；重投不唤醒（I16） */
    DEAD_LETTER;

    private static final Map<InboxState, Set<InboxState>> TRANSITIONS = new EnumMap<>(InboxState.class);

    static {
        TRANSITIONS.put(RECEIVED, Set.of(PROCESSING));
        TRANSITIONS.put(PROCESSING,
                Set.of(PROCESSING, PROCESSED, RETRY_WAIT, DEAD_LETTER, IGNORED));
        TRANSITIONS.put(RETRY_WAIT, Set.of(PROCESSING));
        // 终态：无出边
        TRANSITIONS.put(PROCESSED, Set.of());
        TRANSITIONS.put(IGNORED, Set.of());
        TRANSITIONS.put(DEAD_LETTER, Set.of());
    }

    /** 校验并执行迁移；非法迁移（含终态任何迁出）抛 {@link IllegalStateException} */
    public InboxState transitionTo(InboxState to) {
        if (!canTransitionTo(to)) {
            throw new IllegalStateException("非法状态迁移: " + this + " -> " + to);
        }
        return to;
    }

    public boolean canTransitionTo(InboxState to) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(to);
    }

    /** 终态：PROCESSED / IGNORED / DEAD_LETTER */
    public boolean isTerminal() {
        return TRANSITIONS.getOrDefault(this, Set.of()).isEmpty();
    }
}
