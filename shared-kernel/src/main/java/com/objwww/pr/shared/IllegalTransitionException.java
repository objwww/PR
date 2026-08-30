package com.objwww.pr.shared;

/**
 * 非法状态迁移（各状态机统一抛出；UT-02"非法全抛"的断言目标）。
 */
public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(Enum<?> from, Enum<?> to) {
        super("非法状态迁移: " + from + " -> " + to);
    }
}
