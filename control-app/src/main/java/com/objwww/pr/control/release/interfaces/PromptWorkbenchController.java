package com.objwww.pr.control.release.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.tool.ToolRisk;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prompt 工作台读面（前端产品化 3.16；Wave 5）。
 *
 * <p>数据源全部既有真表：release_asset（asset_kind='PROMPT'，内容含 role/role_version/
 * variables_schema/messages_template）+ rca_model_call（role_digest 投产调用链——
 * 模型调用账本的 role_digest 即 PROMPT 内容的 role_digest，实测 195 匹配）+
 * config_bundle_active/config_bundle（当前激活包的 native.proposal.tasks=能力@版本）。
 * 零新采集、零前端造数。
 */
@RestController
@RequestMapping(path = "/api/v1/prompt-workbench", produces = MediaType.APPLICATION_JSON_VALUE)
public class PromptWorkbenchController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;
    private final ToolRegistry toolRegistry;
    private final List<String> primaryAllowlist;

    public PromptWorkbenchController(
            ObjectProvider<JdbcClient> jdbc,
            ObjectProvider<ToolRegistry> toolRegistry,
            // 同源漂移风险（BA-171 起三处互指）：本默认值 = AlertAm4Config#am4PrimaryProfile
            // 与 #am4PrimaryToolPort 的 app.alert.r7.primary.tool-allowlist 默认值——
            // 放行面调整时三处同步改
            @Value("${app.alert.r7.primary.tool-allowlist:prometheus.query,logs.query,"
                    + "prometheus.instant,prometheus.metric_value,prometheus.catalog,prometheus.label_values,"
                    + "prometheus.rules,logs.aggregate,service.restart,service.rollback,"
                    + "alert.history,change.diff,change.query,rca_history.search}")
            String primaryToolAllowlist) {
        this.jdbc = jdbc.getIfAvailable();
        this.toolRegistry = toolRegistry.getIfAvailable();
        this.primaryAllowlist = Arrays.stream(primaryToolAllowlist.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** PROMPT 版本列表（created_at 倒序）+ 投产用量（role_digest 在调用账本的聚合） */
    @GetMapping("/assets")
    public Map<String, Object> assets(
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "limit", defaultValue = "60") int limit) {
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        int effectiveLimit = Math.max(1, Math.min(limit, 200));
        StringBuilder sql = new StringBuilder("""
                select r.asset_digest, r.content::text as content, r.created_by, r.created_at,
                       coalesce(u.calls, 0) as calls, u.last_used_at
                  from release_asset r
                  left join (
                      select m.role_digest, count(*) as calls, max(m.created_at) as last_used_at
                        from rca_model_call m
                       group by m.role_digest
                  ) u on u.role_digest = r.content->>'role_digest'
                 where r.asset_kind = 'PROMPT'
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        if (role != null && !role.isBlank()) {
            sql.append(" and r.content->>'role' = :role ");
            params.put("role", role.trim());
        }
        sql.append(" order by r.created_at desc limit :limit ");
        params.put("limit", effectiveLimit);

        var spec = jdbc.sql(sql.toString());
        for (Map.Entry<String, Object> e : params.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        List<Map<String, Object>> items = spec.query((rs, i) -> {
            Map<String, Object> content = parseObject(rs.getString("content"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("digest", rs.getString("asset_digest"));
            m.put("role", content.get("role"));
            m.put("roleVersion", content.get("role_version"));
            m.put("variablesSchema", content.get("variables_schema"));
            String template = String.valueOf(content.getOrDefault("messages_template", ""));
            m.put("templateHead", template.length() <= 200 ? template : template.substring(0, 200));
            m.put("bodyLen", template.length());
            m.put("createdBy", rs.getString("created_by"));
            m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            m.put("calls", rs.getLong("calls"));
            Timestamp lastUsed = rs.getTimestamp("last_used_at");
            m.put("lastUsedAt", lastUsed == null ? null : lastUsed.toInstant().toString());
            return m;
        }).list();

        List<String> roles = jdbc.sql("""
                select distinct content->>'role' as role from release_asset
                 where asset_kind = 'PROMPT' and content->>'role' is not null
                 order by role
                """).query((rs, i) -> rs.getString("role")).list();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("items", items);
        body.put("roles", roles);
        body.put("count", items.size());
        return body;
    }

    /** 当前激活包的生效能力版本（native.proposal.tasks 的 type=能力@版本） */
    @GetMapping("/active-plan")
    public Map<String, Object> activePlan() {
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        List<String[]> rows = jdbc.sql("""
                select b.revision, b.content::text as content, a.activated_at as activated_at
                  from config_bundle_active a
                  join config_bundle b on b.bundle_digest = a.bundle_digest
                 limit 1
                """).query((rs, i) -> new String[]{
                rs.getString("revision"), rs.getString("content"), rs.getString("activated_at")}).list();
        if (rows.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("status", "OK");
            empty.put("tasks", List.of());
            empty.put("note", "当前无激活配置包——生效能力版本未知，如实留空");
            return empty;
        }
        String[] row = rows.get(0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("revision", Integer.valueOf(row[0]));
        body.put("activatedAt", row[2]);
        List<Map<String, Object>> tasks = new ArrayList<>();
        try {
            JsonNode plan = JSON.readTree(row[1]).path("native").path("proposal").path("tasks");
            if (plan.isArray()) {
                plan.forEach(t -> {
                    String type = t.path("type").asText("");
                    int at = type.lastIndexOf('@');
                    Map<String, Object> task = new LinkedHashMap<>();
                    task.put("capability", at > 0 ? type.substring(0, at) : type);
                    task.put("version", at > 0 ? type.substring(at + 1) : null);
                    task.put("taskKey", t.path("key").asText(null));
                    tasks.add(task);
                });
            }
        } catch (Exception e) {
            body.put("parseError", "激活包 plan 解析失败（如实透出）");
        }
        body.put("tasks", tasks);
        return body;
    }

    /**
     * 工具注册面透出（真实注册表 ToolRegistry，非前端造数）：每工具返回 name/version/risk
     * （枚举名）/executable（risk.executable()）/approvalRequired（R2||R3）/
     * inPrimaryAllowlist/descriptionZh（BA-176：一句话中文用途，词典未命中回退工具名）；
     * 顶层 allowlist=主 Agent 实际生效放行清单，note=R2/R3 策略口径说明。
     */
    @GetMapping("/tools")
    public Map<String, Object> tools() {
        if (toolRegistry == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "TOOL_REGISTRY_NOT_ASSEMBLED");
        }
        Set<String> allowlist = new LinkedHashSet<>(primaryAllowlist);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ToolRegistry.Registration r : toolRegistry.all()) {
            ToolRisk risk = r.definition().risk();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.definition().name());
            m.put("version", r.definition().version());
            m.put("risk", risk.name());
            m.put("executable", risk.executable());
            m.put("approvalRequired", risk == ToolRisk.R2 || risk == ToolRisk.R3);
            m.put("inPrimaryAllowlist", allowlist.contains(r.definition().name()));
            m.put("descriptionZh",
                    com.objwww.pr.control.alert.application.tool.ToolDescriptionZh
                            .of(r.definition().name()));
            items.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("items", items);
        body.put("count", items.size());
        body.put("allowlist", primaryAllowlist);
        body.put("note", "R0/R1 只读工具可真实执行；R2/R3 写类工具不直接执行——调用即铸意图"
                + "（VALIDATE_ONLY）并自动进审批队列（R3 需两人审批），批准后进入执行计划"
                + "（无 unlock 白名单行 = dry_run 模拟执行）");
        return body;
    }

    private static Map<String, Object> parseObject(String json) {
        try {
            return JSON.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
