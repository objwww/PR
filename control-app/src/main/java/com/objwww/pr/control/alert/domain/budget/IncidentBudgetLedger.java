package com.objwww.pr.control.alert.domain.budget;

import java.util.UUID;

/**
 * Incident 级跨 Run 滚动窗口预算端口（AM4 M4-09，V13 incident_budget_entry）。
 *
 * <p>admission 语义（技术方案 v1.3 §6）：新 Run 派生前先过预留判定——24h 与 7d 双窗口
 * <b>独立</b>判定（窗口滚动按 entry.created_at 过滤，同笔预留双窗口各记一笔）；放行才
 * 派生 run，拒绝不落账（耗尽不派生）。实现方自含短事务并锁 incident 行串行化同事故的
 * 并发 admission（SUM-判-落账原子）；<b>存储不可用默认 fail-closed</b>——异常上抛，
 * 调用方放弃派生（绝不退化为无预算放行）。
 */
public interface IncidentBudgetLedger {

    /**
     * admission 预留。返回 {@link BudgetProbe}：consumed = 双窗口已用最大值、
     * remaining = 双窗口余量最小值、retryAfterMs 恒空（硬预算无重试语义）。
     * incident 不存在抛 IllegalStateException（fail-closed 同义）。
     */
    BudgetProbe admit(UUID incidentId, UUID runId, long estimateUnits,
            long limit24h, long limit7d);
}
