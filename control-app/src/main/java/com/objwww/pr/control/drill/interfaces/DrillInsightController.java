package com.objwww.pr.control.drill.interfaces;

import com.objwww.pr.control.drill.application.DrillJobService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演练洞察读面（前端产品化 3.12；Wave 5-6）。
 *
 * <p>韧性覆盖矩阵 = 模板注册表场景全集 × drill_job 实际演练（次数/最近时间/最近状态），
 * 覆盖率 = 已演练 (场景,环境) 组合 / 全组合——空格即韧性缺口，如实呈现不造数。
 * 四段复盘 = 注入执行（state 推进过 PRECHECK）/ 故障感知（related_incident_id）/
 * 根因定界（related_run_id）/ 恢复闭环（state=CLOSED），全部 drill_job 真列直出。
 */
@RestController
@RequestMapping(path = "/api/v1/drill-matrix", produces = MediaType.APPLICATION_JSON_VALUE)
public class DrillInsightController {

    private final JdbcClient jdbc;
    private final ObjectProvider<DrillJobService> drills;

    public DrillInsightController(ObjectProvider<JdbcClient> jdbc,
            ObjectProvider<DrillJobService> drills) {
        this.jdbc = jdbc.getIfAvailable();
        this.drills = drills;
    }

    @GetMapping
    public Map<String, Object> matrix() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");

        // 场景全集 = 模板注册表（故障模式库）；服务缺位时如实空表
        List<Map<String, Object>> scenarios = new ArrayList<>();
        DrillJobService service = drills.getIfAvailable();
        if (service != null) {
            for (DrillJobService.TemplateCard t : service.templates().templates()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("scenarioId", t.scenarioId());
                s.put("name", t.name());
                s.put("scenarioType", t.scenarioType());
                s.put("faultSource", t.faultSource());
                s.put("chaosFamily", t.chaosFamily());
                scenarios.add(s);
            }
        }
        body.put("scenarios", scenarios);

        if (jdbc == null) {
            body.put("envs", List.of());
            body.put("cells", List.of());
            body.put("coverage", Map.of("covered", 0, "total", 0, "pct", null));
            return body;
        }

        List<String> envs = jdbc.sql("""
                select distinct target_env from drill_job order by target_env
                """).query((rs, i) -> rs.getString(1)).list();
        body.put("envs", envs);

        record Cell(String scenario, String env, long drills, Timestamp lastAt, String lastState) {}
        List<Cell> cells = jdbc.sql("""
                select scenario_name, target_env, count(*) as drills,
                       max(created_at) as last_at,
                       (array_agg(state order by created_at desc))[1] as last_state
                  from drill_job
                 group by 1, 2
                """).query((rs, i) -> new Cell(
                rs.getString(1), rs.getString(2), rs.getLong(3),
                rs.getTimestamp(4), rs.getString(5))).list();

        List<Map<String, Object>> cellRows = new ArrayList<>();
        for (Cell c : cells) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scenarioName", c.scenario());
            m.put("targetEnv", c.env());
            m.put("drills", c.drills());
            m.put("lastAt", c.lastAt() == null ? null : c.lastAt().toInstant().toString());
            m.put("lastState", c.lastState());
            cellRows.add(m);
        }
        body.put("cells", cellRows);

        long covered = cellRows.size();
        long total = (long) scenarios.size() * Math.max(envs.size(), 1);
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("covered", covered);
        coverage.put("total", total);
        coverage.put("pct", total == 0 ? null : Math.round(covered * 1000.0 / total) / 10.0);
        body.put("coverage", coverage);
        return body;
    }

    /** 全部演练的四段复盘（注入/感知/定界/恢复）——drill_job 真列直出 */
    @GetMapping("/four-phase")
    public Map<String, Object> fourPhase() {
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        List<Map<String, Object>> items = jdbc.sql("""
                select id::text, scenario_name, target_env, state, outcome, operator,
                       related_incident_id::text as incident_id,
                       related_run_id::text as run_id,
                       claimed_at, closed_at, created_at
                  from drill_job
                 order by created_at desc
                 limit 20
                """).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            String state = rs.getString("state");
            boolean pastPrecheck = switch (state) {
                case "INJECTING", "OBSERVING", "RECOVERING", "VERIFYING", "CLOSED", "RECOVERY_FAILED" -> true;
                default -> false;
            };
            m.put("drillId", rs.getString("id"));
            m.put("scenarioName", rs.getString("scenario_name"));
            m.put("targetEnv", rs.getString("target_env"));
            m.put("operator", rs.getString("operator"));
            m.put("state", state);
            m.put("outcome", rs.getString("outcome"));
            m.put("inject", pastPrecheck);
            String incidentId = rs.getString("incident_id");
            m.put("perceive", incidentId != null);
            m.put("incidentId", incidentId);
            String runId = rs.getString("run_id");
            m.put("locate", runId != null);
            m.put("runId", runId);
            m.put("recoverDone", "CLOSED".equals(state));
            m.put("recoverFailed", "RECOVERY_FAILED".equals(state));
            m.put("closedAt", rs.getTimestamp("closed_at") == null
                    ? null : rs.getTimestamp("closed_at").toInstant().toString());
            m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            return m;
        }).list();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("items", items);
        return body;
    }
}
