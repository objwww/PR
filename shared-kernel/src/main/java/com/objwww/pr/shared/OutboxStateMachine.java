package com.objwww.pr.shared;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Outbox 八态机（架构冻结文档 v2.2 §1）。两个应用共用，放 shared-kernel。
 *
 * <p>迁移表（M0 冻结版）：
 * <ul>
 *   <li>PENDING → IN_FLIGHT / SUPERSEDED（级联，v2.1 修订二）/ FAILED_TERMINAL（schema 白名单拒绝，EX-09）/ MANUAL</li>
 *   <li>IN_FLIGHT → CONFIRMED / RECONCILING（崩溃窗口，方案 §4.3）/ RETRY_WAIT
 *       / SUPERSEDED（422 STALE_HEAD 确定性否定，§6.3/EX-02）/ FAILED_TERMINAL（422 参数错误等确定性失败）/ MANUAL</li>
 *   <li>RECONCILING → CONFIRMED / RETRY_WAIT / MANUAL（超对账预算熔断，B12 半步）</li>
 *   <li>RETRY_WAIT → PENDING（退避到期重领）/ SUPERSEDED / MANUAL</li>
 *   <li>CONFIRMED / SUPERSEDED / FAILED_TERMINAL / MANUAL 为终态，不再出迁；
 *       MANUAL→SUPERSEDED/CONFIRMED 的人工补偿迁移属 M7，M0 封死。</li>
 * </ul>
 * 任何非终态 → MANUAL 为熔断出口（B13 半步）。
 */
public final class OutboxStateMachine {

    private static final Map<OutboxState, Set<OutboxState>> TRANSITIONS = new EnumMap<>(OutboxState.class);

    static {
        TRANSITIONS.put(OutboxState.PENDING, Set.of(
                OutboxState.IN_FLIGHT, OutboxState.SUPERSEDED,
                OutboxState.FAILED_TERMINAL, OutboxState.MANUAL));
        TRANSITIONS.put(OutboxState.IN_FLIGHT, Set.of(
                OutboxState.CONFIRMED, OutboxState.RECONCILING, OutboxState.RETRY_WAIT,
                OutboxState.SUPERSEDED, OutboxState.FAILED_TERMINAL, OutboxState.MANUAL));
        TRANSITIONS.put(OutboxState.RECONCILING, Set.of(
                OutboxState.CONFIRMED, OutboxState.RETRY_WAIT, OutboxState.MANUAL));
        TRANSITIONS.put(OutboxState.RETRY_WAIT, Set.of(
                OutboxState.PENDING, OutboxState.SUPERSEDED, OutboxState.MANUAL));
        // 终态：无出边
        TRANSITIONS.put(OutboxState.CONFIRMED, Set.of());
        TRANSITIONS.put(OutboxState.SUPERSEDED, Set.of());
        TRANSITIONS.put(OutboxState.FAILED_TERMINAL, Set.of());
        TRANSITIONS.put(OutboxState.MANUAL, Set.of());
    }

    private OutboxStateMachine() {
    }

    /** 校验并执行迁移；非法迁移抛 {@link IllegalTransitionException} */
    public static OutboxState transition(OutboxState from, OutboxState to) {
        if (!canTransition(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
        return to;
    }

    public static boolean canTransition(OutboxState from, OutboxState to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /** 终态：CONFIRMED / SUPERSEDED / FAILED_TERMINAL / MANUAL */
    public static boolean isTerminal(OutboxState state) {
        return TRANSITIONS.getOrDefault(state, Set.of()).isEmpty();
    }
}
