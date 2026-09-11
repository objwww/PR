package com.objwww.pr.control.alert.domain.agent;

/**
 * rca_model_call PENDING 落账的 epoch 栅栏拒绝（EN-04 H04 调度闸）：动作携带的
 * configEpoch 已落后于 run 当前代际（切换事务已提交追加新史行）——该动作在切换
 * 前后不属于同一代际，拒发发送资格。<b>零触网</b>（open 失败 = 不进 gateway），非
 * 重试（动作须以新 epoch 重新铸造；旧绑定任务随 round 终结，§231 新调用绑新 epoch）。
 * 与 PostgresRcaModelCallLedger 的 run 行锁共享原子协调边界——切换应用的计数检查
 * 与动作领取在同一锁序下串行，无计数检查空窗（§225）。
 */
public final class RcaModelCallFenceException extends RuntimeException {

    public static final String CODE = "EPOCH_FENCE";

    public RcaModelCallFenceException(String message) {
        super(CODE + ": " + message);
    }
}
