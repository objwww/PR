package com.objwww.pr.shared.sandbox;

/**
 * 沙箱作业失败分类（M4 §4.2 状态机，G1 甲 P0-9 采纳）。
 *
 * <p>三分类决定重试策略：
 * <ul>
 *   <li>INFRASTRUCTURE：基础设施故障（Docker daemon 错误、镜像拉取失败、资源不足），可重试</li>
 *   <li>USER_CODE：用户代码错误（工具脚本非零退出、输出格式错误、资源超限），不可重试</li>
 *   <li>POLICY_REJECTION：策略拒绝（输入物料安全检查失败、输出违规），不可重试</li>
 * </ul>
 */
public enum FailureClass {
    /** 基础设施故障（可重试） */
    INFRASTRUCTURE(true),
    /** 用户代码错误（不可重试） */
    USER_CODE(false),
    /** 策略拒绝（不可重试） */
    POLICY_REJECTION(false);

    private final boolean retryable;

    FailureClass(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
