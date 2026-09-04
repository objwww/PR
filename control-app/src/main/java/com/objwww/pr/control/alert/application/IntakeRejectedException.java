package com.objwww.pr.control.alert.application;

/**
 * 入口拒绝（结构非法/超尺寸等 4xx 路径；§6.4：AM 不重试 4xx，零落库）。
 * DB 故障不走本异常（DataAccessException 直接上抛 → 503）。
 */
public class IntakeRejectedException extends RuntimeException {

    private final int httpStatus;

    public IntakeRejectedException(int httpStatus, String reason) {
        super(reason);
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
