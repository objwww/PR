package com.objwww.pr.arena.domain.statemachine;

import com.objwww.pr.arena.domain.model.PaymentResult;

/**
 * Pay 机（支付事实 result 迁移，C-1/M2-10/M2-20）：
 * INITIATED→{SUCCEEDED, DECLINED, UNKNOWN}（网关三返回）；
 * UNKNOWN→{SUCCEEDED（迟到成功）, DECLINED（对账确认失败）, RECONCILING（进入对账）}；
 * RECONCILING→{SUCCEEDED, DECLINED}；SUCCEEDED/DECLINED 终态。
 * 退款语义在 Refund 机与 pay_status（NOT_PAY→PAID→REFUNDED）一致性模型中，不在本机。
 */
public final class PayStateMachine {

    private static final TransitionTable<PaymentResult> TABLE =
            TransitionTable.<PaymentResult>forEnum(PaymentResult.class)
                    .allow(PaymentResult.INITIATED, PaymentResult.SUCCEEDED,
                            PaymentResult.DECLINED, PaymentResult.UNKNOWN)
                    .allow(PaymentResult.UNKNOWN, PaymentResult.SUCCEEDED,
                            PaymentResult.DECLINED, PaymentResult.RECONCILING)
                    .allow(PaymentResult.RECONCILING, PaymentResult.SUCCEEDED,
                            PaymentResult.DECLINED)
                    .build();

    private PayStateMachine() {
    }

    public static boolean allowed(PaymentResult from, PaymentResult to) {
        return TABLE.allowed(from, to);
    }

    public static void requireTransition(PaymentResult from, PaymentResult to) {
        TABLE.requireTransition(from, to);
    }

    public static TransitionTable<PaymentResult> table() {
        return TABLE;
    }
}
