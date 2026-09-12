package com.objwww.pr.control.drill.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * DR-06 drill→告警/调查关联端口（方案 docs/告警-前端逐页体验改造与后期优化方案.md
 * §7.5 DR-06 卡「既有 scenario-map 关联、真实关联」）：按「时间窗 + 场景声明的症状
 * 标签」匹配 incident，匹配到才回填 related_incident_id（其 currentRcaRunId 存在才
 * 带 related_run_id）；匹配不到 = empty（详情保持「尚未关联」），严禁按时间近似
 * 瞎关联。多候选确定规则由实现钉死（Postgres 实现：窗口内最早 first_seen_at，
 * id 定序兜底）。
 *
 * <p>关联键裁定：incident 表无独立 env 标签列，alertname 经 incident_key 首段
 * （split_part 首段 = alertname=…）精确等值；靶场维度由「首期单靶场 + 靶场专属
 * 规则名（Arena 系 / checkout 仅靶场栈产生）」隐含，不硬造标签。
 */
public interface DrillCorrelationPort {

    /**
     * 关联匹配：主症状 alertname（模板 symptomCodes 首项）+ 注入时间窗
     * [injectedAt, windowEnd]（windowEnd = injectedAt + maxFiringWaitSeconds）。
     */
    Optional<Correlation> correlate(String primaryAlertname, Instant injectedAt,
                                    Instant windowEnd);

    /** 关联结果：incidentId 必填；currentRcaRunId 可空（存在才回填 related_run_id） */
    record Correlation(UUID incidentId, UUID currentRcaRunId) {
    }

    /** 未接线面（EvalRunnerConfig 旧装配）：恒 empty——不关联、不假装关联 */
    static DrillCorrelationPort disabled() {
        return (alertname, injectedAt, windowEnd) -> Optional.empty();
    }
}
