package com.objwww.pr.control.release.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 配置版本结构化 Diff（业界对齐 LaunchDarkly 版本对比：按配置键对比而非文本行）。
 * 挂 /api/v1（operator 只读面）——/api/config-bundles/** 是 RELEASE 机器线，operator
 * 会话 403，而版本对比是复核动作必须对 operator 可见。纯读无副作用。
 */
@RestController
@RequestMapping("/api/v1/config-bundles")
public class ConfigBundleDiffController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcClient jdbc;

    public ConfigBundleDiffController(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    /** 当前生效版本指针（真源 config_bundle_active；无生效行如实 ok=false） */
    @GetMapping("/active-digest")
    public Map<String, Object> activeDigest() {
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        List<Map<String, Object>> rows = jdbc.sql("""
                select bundle_digest, activated_at, activated_by
                  from config_bundle_active order by activated_at desc limit 1
                """)
                .query((rs, i) -> Map.<String, Object>of(
                        "digest", (Object) rs.getString("bundle_digest"),
                        "activatedAt", (Object) rs.getTimestamp("activated_at").toInstant().toString(),
                        "activatedBy", (Object) rs.getString("activated_by")))
                .list();
        if (rows.isEmpty()) {
            return Map.of("status", "OK", "active", false);
        }
        return Map.of("status", "OK", "active", true, "items", rows);
    }

    /** 三类差异：changed（两侧都在值不同）/ added（基线无新增）/ removed（基线有已删） */
    @GetMapping("/{digest}/diff/{baseDigest}")
    public Map<String, Object> diff(@PathVariable String digest,
                                    @PathVariable String baseDigest) {
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        Map<String, Object> base = loadFlat(baseDigest);
        Map<String, Object> target = loadFlat(digest);
        if (base == null || target == null) {
            return Map.of("status", "REJECTED", "reason",
                    (base == null ? "BASE_NOT_FOUND:" + baseDigest : "TARGET_NOT_FOUND:" + digest));
        }
        List<Map<String, Object>> changed = new ArrayList<>();
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        for (Map.Entry<String, Object> e : target.entrySet()) {
            String path = e.getKey();
            if (!base.containsKey(path)) {
                added.add(entry(path, null, e.getValue()));
            } else if (!String.valueOf(base.get(path)).equals(String.valueOf(e.getValue()))) {
                changed.add(entry(path, base.get(path), e.getValue()));
            }
        }
        for (Map.Entry<String, Object> e : base.entrySet()) {
            if (!target.containsKey(e.getKey())) {
                removed.add(entry(e.getKey(), e.getValue(), null));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("baseDigest", baseDigest);
        body.put("targetDigest", digest);
        body.put("changed", changed);
        body.put("added", added);
        body.put("removed", removed);
        body.put("unchangedCount", base.size() + added.size() - removed.size() - changed.size());
        return body;
    }

    /** 取单条 bundle 的 content 并展平为 path→value（map 键排序保证输出稳定） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadFlat(String digest) {
        List<String> rows = jdbc.sql("select content::text from config_bundle where bundle_digest = :d")
                .param("d", digest)
                .query((rs, i) -> rs.getString(1))
                .list();
        if (rows.isEmpty()) {
            return null;
        }
        try {
            JsonNode tree = MAPPER.readTree(rows.get(0));
            Map<String, Object> flat = new TreeMap<>();
            flatten("", tree, flat);
            return flat;
        } catch (Exception e) {
            Map<String, Object> flat = new TreeMap<>();
            flat.put("<parse_error>", String.valueOf(e.getMessage()));
            return flat;
        }
    }

    private static void flatten(String prefix, JsonNode node, Map<String, Object> out) {
        if (node.isObject()) {
            node.fields().forEachRemaining(f -> flatten(
                    prefix.isEmpty() ? f.getKey() : prefix + "." + f.getKey(), f.getValue(), out));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flatten(prefix + "[" + i + "]", node.get(i), out);
            }
            if (node.isEmpty()) {
                out.put(prefix, "[]");
            }
        } else {
            out.put(prefix, node.isNull() ? "null" : node.asText());
        }
    }

    private static Map<String, Object> entry(String path, Object base, Object target) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("base", base);
        m.put("target", target);
        return m;
    }
}
