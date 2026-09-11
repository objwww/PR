package com.objwww.pr.control.release.interfaces;

import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository;
import com.objwww.pr.shared.Digest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * EN-10 版本中心查询面（O06"实际 revision/digest 可见"/O07"状态从后端恢复"）：
 * release_asset 与 config_bundle 的<b>只读</b>列表+明细——发布/激活/回滚不在本面，
 * 仍走 ConfigBundleController 的 RELEASE 机器线（SecurityConfig：本面挂 OPERATOR
 * 浏览器矩阵，机器写面权限边界不混）。O08 纪律：非法入参 400 就地解释、缺失 404、
 * 空库 200 空集——页面零伪成功零伪失败。
 *
 * <p>列表零正文（摘要投影：title/description/runbook_id 等展示键），正文归明细
 * 端点（R05 最小披露同律）。
 */
@RestController
@Profile("docker")
public class ReleaseAssetQueryController {

    /** 列表收敛上界（硬门显式钉；默认 50） */
    static final int MAX_LIMIT = 200;

    /** 摘要投影消费的展示键（正文 text/messages_template/body 永不入列表） */
    private static final List<String> SUMMARY_KEYS =
            List.of("runbook_id", "title", "description", "name", "tags");

    private final ReleaseAssetRepository assets;
    private final ConfigBundleRepository bundles;

    public ReleaseAssetQueryController(ReleaseAssetRepository assets,
            ConfigBundleRepository bundles) {
        this.assets = Objects.requireNonNull(assets, "assets 不得为 null");
        this.bundles = Objects.requireNonNull(bundles, "bundles 不得为 null");
    }

    /** 资产列表：?kind= 过滤（未知 kind → 400）、?limit= 非法 <1 → 400、超界收敛 200 */
    @GetMapping("/api/release-assets")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        if (kind != null && !kind.isBlank() && !ReleaseAsset.isKnownKind(kind)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "未知资产 kind: " + kind + "（合法集 PROMPT/SKILL/TOOL_SCHEMA/"
                            + "RUNBOOK_DOC/RUNBOOK_CATALOG）"));
        }
        if (limit < 1) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "limit 必须 >= 1（收到 " + limit + "）"));
        }
        int effectiveLimit = Math.min(limit, MAX_LIMIT);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ReleaseAsset asset : assets.listRecent(kind, effectiveLimit)) {
            items.add(summaryOf(asset));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        return ResponseEntity.ok(body);
    }

    /** bundle 版本列表：revision 倒序 + 当前指针 active 标记（无指针 active=null） */
    @GetMapping("/api/release-assets/bundles")
    public ResponseEntity<Map<String, Object>> bundles() {
        List<Map<String, Object>> items = new ArrayList<>();
        ConfigBundleRepository.ActivePointer pointer =
                bundles.findActivePointer().orElse(null);
        for (ConfigBundleRepository.BundleSummary bundle : bundles.listRecent(MAX_LIMIT)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("digest", bundle.digest().hex());
            item.put("revision", bundle.revision());
            item.put("created_by", bundle.createdBy());
            item.put("created_at", bundle.createdAt().toString());
            item.put("active", pointer != null && pointer.bundleDigest().equals(bundle.digest()));
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", pointer == null ? null
                : Map.of("digest", pointer.bundleDigest().hex(),
                        "revision", pointer.revision(),
                        "activated_at", pointer.activatedAt().toString()));
        body.put("items", items);
        return ResponseEntity.ok(body);
    }

    /** 资产明细：kind+digest 精确解析（port 语义：kind 不同同 digest = 不同资产） */
    @GetMapping("/api/release-assets/{kind}/{digest}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable("kind") String kind,
            @PathVariable("digest") String digest) {
        if (!ReleaseAsset.isKnownKind(kind)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "未知资产 kind: " + kind));
        }
        Digest assetDigest;
        try {
            assetDigest = new Digest(digest);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "digest 必须为 64 位小写 hex"));
        }
        return assets.findByDigest(kind, assetDigest)
                .<ResponseEntity<Map<String, Object>>>map(asset -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("kind", asset.kind());
                    body.put("digest", asset.assetDigest().hex());
                    body.put("created_by", asset.createdBy());
                    body.put("created_at", asset.createdAt().toString());
                    body.put("content", asset.content());
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ------------------------------------------------------------------ 内部

    /** 摘要投影：仅展示键，零正文（R05 最小披露） */
    private static Map<String, Object> summaryOf(ReleaseAsset asset) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", asset.kind());
        item.put("digest", asset.assetDigest().hex());
        item.put("created_by", asset.createdBy());
        item.put("created_at", asset.createdAt().toString());
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String key : SUMMARY_KEYS) {
            Object value = asset.content().get(key);
            if (value != null) {
                summary.put(key, value);
            }
        }
        item.put("summary", summary);
        return item;
    }
}
