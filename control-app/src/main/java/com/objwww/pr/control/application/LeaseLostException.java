package com.objwww.pr.control.application;

/**
 * 心跳发现租约已失效（被判死/重领）：执行器主动停手信号。
 * Worker 将其归类为 Failed(retryable=false) 照常上报 T2——T2 栅栏会记 STALE（I11）。
 */
public class LeaseLostException extends RuntimeException {

    public LeaseLostException(String message) {
        super(message);
    }
}
