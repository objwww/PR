package com.objwww.pr.control.eval.application;

import com.objwww.pr.shared.Digest;

/**
 * 告警面探针（M3-17）：firing 等待 / resolved 确认 / 规则摘要——
 * 默认实现查 Prometheus HTTP API（真栈行为归 195 门），测试以假件替换。
 */
public interface AlertProbe {

    /** 在 maxWaitSeconds 内期望告警全部 firing？超时返回 false（不抛中断批量） */
    boolean awaitAllFiring(String scenarioId, int maxWaitSeconds);

    /** 在 maxWaitSeconds 内期望告警全部 resolved（无残留 firing）？ */
    boolean awaitAllResolved(String scenarioId, int maxWaitSeconds);

    /** 靶场恢复会话在 cleanup 窗口内收口（CLOSED）？S1/S2 无会话恒 true */
    boolean awaitSessionClosed(String scenarioId, int cleanupTimeoutSeconds);

    /** 期望规则表达式摘要（激活请求 ruleDigest；M2-24 scenario_map 输入面） */
    Digest ruleDigest(String alertname);
}
