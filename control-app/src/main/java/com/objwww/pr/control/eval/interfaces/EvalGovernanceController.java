package com.objwww.pr.control.eval.interfaces;

import com.objwww.pr.control.infrastructure.auth.AuthenticatedActor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 评测治理读/写面（前端产品化 3.11）：废批治理打标（eval_run.governance_tag）+
 * 数据集分层标签（eval_dataset_tier，V133）。写面只做白名单值域校验与落档，
 * 判定语义（哪批算废）由人工裁定——账本如实记录谁/何时/打了什么标。
 */
@RestController
@RequestMapping(path = "/api/eval/governance", produces = MediaType.APPLICATION_JSON_VALUE)
public class EvalGovernanceController {

    private static final Set<String> TAGS =
            Set.of("VALID", "SCRAP_TIMEOUT", "SCRAP_OTHER", "ARCHIVED");
    private static final Set<String> TIERS =
            Set.of("SMOKE", "REGRESSION", "EXPLORE", "REDTEAM");

    private final JdbcClient jdbc;

    public EvalGovernanceController(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    /** 全部已打标实验（前端按 runId 合并进实验列表） */
    @GetMapping("/run-tags")
    public Map<String, Object> runTags() {
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        Map<String, Object> tags = new LinkedHashMap<>();
        jdbc.sql("""
                select id, governance_tag, governance_tagged_by, governance_tagged_at
                  from eval_run where governance_tag is not null
                """).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tag", rs.getString("governance_tag"));
            m.put("by", rs.getString("governance_tagged_by"));
            m.put("at", rs.getTimestamp("governance_tagged_at").toInstant().toString());
            tags.put(rs.getString("id"), m);
            return m;
        }).list();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("tags", tags);
        return body;
    }

    @PostMapping(path = "/runs/{runId}/governance-tag", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> tagRun(@PathVariable String runId,
            @RequestBody Map<String, Object> body) {
        String tag = text(body.get("tag"));
        if (tag == null || !TAGS.contains(tag)) {
            return Map.of("status", "REJECTED", "reason", "UNKNOWN_TAG");
        }
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        int updated = jdbc.sql("""
                update eval_run set governance_tag = :tag,
                       governance_tagged_by = :by, governance_tagged_at = now()
                 where id = :runId
                """)
                .param("tag", tag)
                .param("by", AuthenticatedActor.name())
                .param("runId", UUID.fromString(runId))
                .update();
        if (updated == 0) {
            return Map.of("status", "REJECTED", "reason", "RUN_NOT_FOUND");
        }
        return Map.of("status", "OK", "tag", tag);
    }

    /** 数据集分层清单（含打标人/时间） */
    @GetMapping("/dataset-tiers")
    public Map<String, Object> datasetTiers() {
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        List<Map<String, Object>> items = jdbc.sql("""
                select dataset_name, dataset_version, tier, tagged_by, tagged_at
                  from eval_dataset_tier
                 order by dataset_name, dataset_version
                """).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", rs.getString("dataset_name"));
            m.put("version", rs.getString("dataset_version"));
            m.put("tier", rs.getString("tier"));
            m.put("by", rs.getString("tagged_by"));
            m.put("at", rs.getTimestamp("tagged_at").toInstant().toString());
            return m;
        }).list();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("items", items);
        return body;
    }

    @PostMapping(path = "/dataset-tiers", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> tagDataset(@RequestBody Map<String, Object> body) {
        String name = text(body.get("name"));
        String version = text(body.get("version"));
        String tier = text(body.get("tier"));
        if (name == null || version == null || tier == null || !TIERS.contains(tier)) {
            return Map.of("status", "REJECTED", "reason", "INVALID_DATASET_TIER");
        }
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        jdbc.sql("""
                insert into eval_dataset_tier (dataset_name, dataset_version, tier, tagged_by)
                 values (:name, :version, :tier, :by)
                 on conflict (dataset_name, dataset_version)
                 do update set tier = excluded.tier, tagged_by = excluded.tagged_by,
                               tagged_at = now()
                """)
                .param("name", name).param("version", version).param("tier", tier)
                .param("by", AuthenticatedActor.name())
                .update();
        return Map.of("status", "OK", "tier", tier);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
