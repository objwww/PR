package com.objwww.pr.publisher.domain.port;

/**
 * 租约栅栏失效（B-2）：UPDATE 按 lease_epoch/状态守卫命中 0 行，说明租约已被收回或
 * 状态已被他人推进——僵尸 worker 的晚到落账必须放弃，不能推进状态机。
 */
public class StaleLeaseException extends RuntimeException {

    public StaleLeaseException(String message) {
        super(message);
    }
}
