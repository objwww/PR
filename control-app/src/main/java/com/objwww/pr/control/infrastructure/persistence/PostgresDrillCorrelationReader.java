package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.drill.application.DrillCorrelationPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link DrillCorrelationPort} 的 Postgres 实现（DR-06，§7.5「真实关联」；
 * eval_app 经 V11 持 incident SELECT，本类全程只读、零告警域写面）：
 * <ul>
 *   <li><b>关联键</b>：incident_key 首段（split_part 首段 = alertname=…，INV-AM1-4
 *       标签序钉死 alertname 居首）精确等值场景主症状码——不子串模糊匹配
 *       （LIKE '%alertname=x%' 会误中 alertname=checkout2 形）；</li>
 *   <li><b>时间窗</b>：incident.episode_started_at ∈ [injectedAt, windowEnd]——注入后
 *       的<b>新 firing 沿</b>才算本场（BA-180：episode 水印=本 episode 的 firing 起点，
 *       首见与复燃同键；旧口径取 first_seen_at 会把"同 incident_key 复燃的新 episode"
 *       全部漏挂——incident_key 唯一 + generation 递增，复燃不换行、first_seen_at 停在
 *       历史首见，S3 等老场景演练关联恒 null、outcome 恒 FAIL 失真；残留 firing
 *       （episode 起点早于注入）不命中， SYMPTOM 等待口径与"新沿"一致）；</li>
 *   <li><b>多候选确定规则</b>：窗口内最早 episode_started_at，id 升序兜底——钉死可重放；</li>
 *   <li><b>靶场维度</b>：incident/alert_event 无 env 标签列（如实面，不硬造）——
 *       首期单靶场 + 靶场专属规则名（Arena 系 / checkout 仅靶场栈的规则产生）隐含
 *       隔离；多靶场落地时需补标签面再扩键。</li>
 * </ul>
 * 找不到 = empty（worker 保持 related_* null，前端「尚未关联」），不猜不凑。
 */
public class PostgresDrillCorrelationReader implements DrillCorrelationPort {

    private final JdbcClient jdbc;

    public PostgresDrillCorrelationReader(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Optional<Correlation> correlate(String primaryAlertname, Instant injectedAt,
                                           Instant windowEnd) {
        return jdbc.sql("""
                        SELECT i.id, i.current_rca_run_id FROM incident i
                        WHERE split_part(i.incident_key, '|', 1) = :key
                          AND i.episode_started_at >= :since AND i.episode_started_at <= :until
                        ORDER BY i.episode_started_at ASC, i.id ASC LIMIT 1
                        """)
                .param("key", "alertname=" + primaryAlertname)
                .param("since", Timestamp.from(injectedAt))
                .param("until", Timestamp.from(windowEnd))
                .query((rs, row) -> new Correlation(
                        rs.getObject("id", UUID.class),
                        rs.getObject("current_rca_run_id", UUID.class)))
                .optional();
    }
}
