package com.objwww.pr.control.application;

/**
 * 执行期租约心跳视图（T10）：Worker 后台周期续租，执行器在粗粒度检查点探活。
 * false = 租约已被判死/重领（心跳 UPDATE 0 行），执行器应尽快停手；
 * 即使不停手，晚到结果也会被 T2 的 lease_epoch 栅栏记 STALE（I11，B-2 窗口合法）。
 */
public interface LeaseHeartbeat {

    boolean isAlive();
}
