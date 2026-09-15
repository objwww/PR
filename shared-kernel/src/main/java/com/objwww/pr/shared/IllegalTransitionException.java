package com.objwww.pr.shared;

/**
 * 非法状态迁移（各状态机统一抛出；UT-02"非法全抛"的断言目标）。
 */
public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(Enum<?> from, Enum<?> to) {
        super("非法状态迁移: " + from + " -> " + to);
    }

    /** 定制描述形态（PB-B1：Operation 越边/终态复活需携带更细的禁则说明） */
    public IllegalTransitionException(String message) {
        super(message);
    }
}
