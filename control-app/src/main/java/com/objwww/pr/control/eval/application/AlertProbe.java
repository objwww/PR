package com.objwww.pr.control.eval.application;

import com.objwww.pr.shared.Digest;

import java.util.List;

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

    /** 单拍快照（BA-180 演练预检洁净门）：期望症状码中<b>当前</b> firing 的集合
     *  （不轮询不等沿）。默认未实现 = 探针面不持此读面，调用方按 UNKNOWN 诚实处理 */
    default List<String> firingNow(String scenarioId) {
        throw new UnsupportedOperationException("firingNow 单拍读面未实现");
    }
}
