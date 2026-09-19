package com.objwww.pr.control.release.interfaces;

import com.objwww.pr.control.infrastructure.auth.AuthenticatedActor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 提示词草稿工作面（M-d T7）：子 agent 提示词「起草→对照→裁定」流程后端。
 *
 * <p>与既有 /api/v1/prompt-workbench 只读面的关系：本面只写 prompt_draft（V153 草稿表），
 * 不写 release_asset——role_digest 是 AgentProfile 全量摘要，草稿期无法伪造；
 * 发布仍走既有受控激活（ConfigBundle 激活 → rca_model_call.role_digest 对账），
 * 本面不做绕行直发（M-d 方案 §7 安全边界）。鉴权随 /api/v1/ 既有规则。
 */
@RestController
@Profile("docker")
@RequestMapping(path = "/api/v1/prompt-workbench", produces = MediaType.APPLICATION_JSON_VALUE)
public class PromptDraftController {

    private final JdbcClient jdbc;

    public PromptDraftController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 起草请求体：role 必须已有现行版本（base 从 release_asset 最新行快照冻结） */
    public record CreateDraftRequest(String role, String proposedTemplate) {
    }

    @PostMapping(path = "/drafts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@RequestBody CreateDraftRequest request) {
        if (request == null || request.role() == null || request.role().isBlank()
                || request.proposedTemplate() == null || request.proposedTemplate().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "role 与 proposedTemplate 必填"));
        }
        String role = request.role().trim();
        List<Map<String, Object>> base = jdbc.sql("""
                        select asset_digest, content->>'role_version' as role_version,
                               content->>'messages_template' as template
                          from release_asset
                         where asset_kind = 'PROMPT' and content->>'role' = :role
                         order by created_at desc
                         limit 1
                        """)
                .param("role", role)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("digest", rs.getString("asset_digest"));
                    m.put("roleVersion", rs.getString("role_version"));
                    m.put("template", rs.getString("template"));
                    return m;
                }).list();
        if (base.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "该 role 无现行版本（草稿基线必须锚到已投产 prompt）: " + role));
        }
        Map<String, Object> b = base.get(0);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into prompt_draft (
                            id, role, base_role_version, base_asset_digest,
                            base_template, proposed_template, author, status)
                        values (:id, :role, :baseRoleVersion, :baseAssetDigest,
                                :baseTemplate, :proposedTemplate, :author, 'DRAFT')
                        """)
                .param("id", id)
                .param("role", role)
                .param("baseRoleVersion", String.valueOf(b.get("roleVersion")))
                .param("baseAssetDigest", String.valueOf(b.get("digest")))
                .param("baseTemplate", String.valueOf(b.get("template")))
                .param("proposedTemplate", request.proposedTemplate())
                .param("author", AuthenticatedActor.name())
                .update();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id.toString());
        body.put("role", role);
        body.put("baseRoleVersion", b.get("roleVersion"));
        body.put("baseAssetDigest", b.get("digest"));
        body.put("status", "DRAFT");
        return ResponseEntity.status(201).body(body);
    }

    /** 草稿列表（默认不含 DISCARDED；includeDiscarded=true 全量） */
    @GetMapping("/drafts")
    public Map<String, Object> list(
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "includeDiscarded", defaultValue = "false")
            boolean includeDiscarded) {
        StringBuilder sql = new StringBuilder("""
                select id, role, base_role_version, base_asset_digest, status,
                       author, created_at, updated_at, applied_asset_digest,
                       length(base_template) as base_len,
                       length(proposed_template) as proposed_len
                  from prompt_draft
                 where 1 = 1
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        if (!includeDiscarded) {
            sql.append(" and status <> 'DISCARDED' ");
        }
        if (role != null && !role.isBlank()) {
            sql.append(" and role = :role ");
            params.put("role", role.trim());
        }
        sql.append(" order by created_at desc limit 200 ");
        var spec = jdbc.sql(sql.toString());
        for (Map.Entry<String, Object> e : params.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        List<Map<String, Object>> items = spec.query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("role", rs.getString("role"));
            m.put("baseRoleVersion", rs.getString("base_role_version"));
            m.put("baseAssetDigest", rs.getString("base_asset_digest"));
            m.put("status", rs.getString("status"));
            m.put("author", rs.getString("author"));
            m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            m.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
            m.put("appliedAssetDigest", rs.getString("applied_asset_digest"));
            m.put("baseLen", rs.getLong("base_len"));
            m.put("proposedLen", rs.getLong("proposed_len"));
            return m;
        }).list();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("items", items);
        body.put("count", items.size());
        return body;
    }

    /** diff 基线面：起草时刻冻结的 base 快照 vs 提案全文（行数组直出，LCS 渲染归前端） */
    @GetMapping("/drafts/{id}/diff")
    public ResponseEntity<?> diff(@PathVariable String id) {
        UUID draftId;
        try {
            draftId = UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "draftId 非法"));
        }
        List<Map<String, Object>> rows = jdbc.sql("""
                        select role, base_role_version, base_template, proposed_template, status
                          from prompt_draft
                         where id = :id
                        """)
                .param("id", draftId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", rs.getString("role"));
                    m.put("baseRoleVersion", rs.getString("base_role_version"));
                    m.put("baseTemplate", rs.getString("base_template"));
                    m.put("proposedTemplate", rs.getString("proposed_template"));
                    m.put("status", rs.getString("status"));
                    return m;
                }).list();
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "草稿不存在"));
        }
        Map<String, Object> d = rows.get(0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("role", d.get("role"));
        body.put("baseRoleVersion", d.get("baseRoleVersion"));
        body.put("status", d.get("status"));
        body.put("baseLines", String.valueOf(d.get("baseTemplate")).split("\n", -1));
        body.put("proposedLines", String.valueOf(d.get("proposedTemplate")).split("\n", -1));
        return ResponseEntity.ok(body);
    }

    /** 裁定弃稿：CAS DRAFT→DISCARDED（已裁定面不可重复裁定，409 如实） */
    @PostMapping("/drafts/{id}/discard")
    public ResponseEntity<?> discard(@PathVariable String id) {
        UUID draftId;
        try {
            draftId = UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "draftId 非法"));
        }
        int moved = jdbc.sql("""
                        update prompt_draft
                           set status = 'DISCARDED', updated_at = :now
                         where id = :id and status = 'DRAFT'
                        """)
                .param("now", Timestamp.from(java.time.Instant.now()))
                .param("id", draftId)
                .update();
        if (moved == 0) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "裁定未中：草稿不存在或已裁定（状态机单向）"));
        }
        return ResponseEntity.accepted().body(Map.of("id", id, "status", "DISCARDED"));
    }
}
