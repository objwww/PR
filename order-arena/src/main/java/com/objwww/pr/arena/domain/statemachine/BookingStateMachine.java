package com.objwww.pr.arena.domain.statemachine;

import com.objwww.pr.arena.domain.model.BookingStatus;

/**
 * Booking 机（C-1 冻结）：CREATED→{ENABLED, DISCARDED}；ENABLED→{DISCARDED}（取消/废单）；
 * DISCARDED 终态。ENABLED→CREATED 的回跳只存在于 F2 注入（绕过本机直改 DB，
 * DomainProbe 探测面），正常域一律非法。
 */
public final class BookingStateMachine {

    private static final TransitionTable<BookingStatus> TABLE =
            TransitionTable.<BookingStatus>forEnum(BookingStatus.class)
                    .allow(BookingStatus.CREATED, BookingStatus.ENABLED, BookingStatus.DISCARDED)
                    .allow(BookingStatus.ENABLED, BookingStatus.DISCARDED)
                    .build();

    private BookingStateMachine() {
    }

    public static boolean allowed(BookingStatus from, BookingStatus to) {
        return TABLE.allowed(from, to);
    }

    public static void requireTransition(BookingStatus from, BookingStatus to) {
        TABLE.requireTransition(from, to);
    }

    public static TransitionTable<BookingStatus> table() {
        return TABLE;
    }
}
