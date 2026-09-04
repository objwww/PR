package com.objwww.pr.control.alert.domain.service;

/**
 * 纯函数：投影期软背压判定（§6.4 评审 #4 选项一；不执行 SQL）。
 *
 * <p>backlog = 活跃 incident 数 + 排队 task 数（可观测、可配）；超过阈值 → 逐条 DEFERRED
 * （DEFERRED 行本身即审计，不另写 SUPPRESSED 放大洪峰）；backlog 回落后由处理循环补投。
 */
public record DeferredPolicy(int backlogThreshold) {

    /** 投影期逐 alert 判定结果 */
    public enum Decision { IMMEDIATE, DEFERRED }

    public DeferredPolicy {
        if (backlogThreshold < 0) {
            throw new IllegalArgumentException("backlogThreshold 不能为负");
        }
    }

    /**
     * backlog = activeIncidents + queuedTasks；
     * 严格大于阈值才 DEFERRED（恰好在阈值上仍受理——边界单测锚点 UT-A07）。
     */
    public Decision decide(int activeIncidents, int queuedTasks) {
        int backlog = activeIncidents + queuedTasks;
        return backlog > backlogThreshold ? Decision.DEFERRED : Decision.IMMEDIATE;
    }
}
