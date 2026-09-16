package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.shared.Digest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置中心读面（前端产品化 3.6；Wave 5）。
 *
 * <p>接入管理：机器线 bearer 与 webhook HMAC 密钥的**配置状态**读面——与 SecurityConfig
 * 装配同源（app.alert.webhook.hmac-keys / 五条机器线 bearer 属性），只透出 keyId 与
 * 密钥指纹（SHA-256 前 8 位），原文不出环境变量，不落库不上屏。
 * 告警分类：incident.category 生成列（V82：coalesce(override, rule, 'UNCLASSIFIED')）
 * 的实时分布与来源拆分（RULE/OVERRIDE）。
 */
@RestController
@RequestMapping(path = "/api/v1/config", produces = MediaType.APPLICATION_JSON_VALUE)
public class ConfigCenterController {

    private final JdbcClient jdbc;
    private final Environment env;

    public ConfigCenterController(ObjectProvider<JdbcClient> jdbc, Environment env) {
        this.jdbc = jdbc.getIfAvailable();
        this.env = env;
    }

    /** 接入管理：webhook 入口 + HMAC 密钥姿态 + 机器线 bearer 配置状态（指纹不回显原文） */
    @GetMapping("/intake")
    public Map<String, Object> intake() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("webhookPath", "/webhooks/alertmanager");

        String hmacKeys = env.getProperty("app.alert.webhook.hmac-keys", "");
        List<String> keyIds = new ArrayList<>();
        if (hmacKeys != null && !hmacKeys.isBlank()) {
            for (String entry : hmacKeys.split(",")) {
                int sep = entry.indexOf(':');
                if (sep > 0) {
                    keyIds.add(entry.substring(0, sep).trim());
                }
            }
        }
        body.put("hmacEnabled", !keyIds.isEmpty());
        body.put("hmacKeyIds", keyIds);
        body.put("hmacNote", keyIds.isEmpty()
                ? "未配置 HMAC 密钥——入口按 bearer-only 姿态运行（Alertmanager 兼容）"
                : "HMAC-SHA256 强制验签（携带签名的请求验签失败即 401，不回落）");

        List<Map<String, Object>> lines = new ArrayList<>();
        addMachineLine(lines, "告警接入线", "app.alert.webhook.bearer", "MACHINE_WEBHOOK");
        addMachineLine(lines, "控制面合成告警线", "app.alert.control-router.bearer", "MACHINE_WEBHOOK");
        addMachineLine(lines, "运维机器线", "app.operator.api.bearer", "OPERATOR");
        addMachineLine(lines, "发布机器线", "app.release.api.bearer", "RELEASE");
        addMachineLine(lines, "值班回写线", "app.duty.adapter.bearer", "DUTY_ADAPTER");
        body.put("machineLines", lines);
        return body;
    }

    private void addMachineLine(List<Map<String, Object>> lines, String name,
            String property, String role) {
        String value = env.getProperty(property, "");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("property", property);
        m.put("role", role);
        m.put("configured", value != null && !value.isBlank());
        m.put("fingerprint", value == null || value.isBlank()
                ? null : Digest.sha256Of(value).value().substring(0, 8));
        lines.add(m);
    }

    /** 告警分类分布（V82 生成列直出）：每类计数 + 规则/人工来源拆分 */
    @GetMapping("/category-stats")
    public Map<String, Object> categoryStats() {
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        long total = 0;
        long overridden = 0;
        long unclassified = 0;
        for (Map<String, Object> row : jdbc.sql("""
                select coalesce(category, 'UNCLASSIFIED') as cat,
                       coalesce(category_source, 'RULE') as src,
                       count(*) as cnt
                  from incident
                 group by 1, 2
                """).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cat", rs.getString("cat"));
            m.put("src", rs.getString("src"));
            m.put("cnt", rs.getLong("cnt"));
            return m;
        }).list()) {
            long cnt = (long) row.get("cnt");
            total += cnt;
            String cat = String.valueOf(row.get("cat"));
            if ("OVERRIDE".equals(row.get("src"))) {
                overridden += cnt;
            }
            if ("UNCLASSIFIED".equals(cat)) {
                unclassified += cnt;
            }
            merge(items, cat, row.get("src").toString(), cnt);
        }
        items.sort((a, b) -> Long.compare((long) b.get("total"), (long) a.get("total")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("items", items);
        body.put("total", total);
        body.put("overridden", overridden);
        body.put("unclassified", unclassified);
        body.put("unclassifiedRate", total == 0 ? null
                : Math.round(unclassified * 1000.0 / total) / 10.0);
        return body;
    }

    private void merge(List<Map<String, Object>> items, String cat, String src, long cnt) {
        for (Map<String, Object> item : items) {
            if (cat.equals(item.get("category"))) {
                item.put("total", (long) item.get("total") + cnt);
                item.put(src.equals("OVERRIDE") ? "override" : "rule",
                        (long) item.getOrDefault(src.equals("OVERRIDE") ? "override" : "rule", 0L) + cnt);
                return;
            }
        }
        Map<String, Object> fresh = new LinkedHashMap<>();
        fresh.put("category", cat);
        fresh.put("total", cnt);
        fresh.put("rule", src.equals("RULE") ? cnt : 0L);
        fresh.put("override", src.equals("OVERRIDE") ? cnt : 0L);
        items.add(fresh);
    }
}
