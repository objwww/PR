package com.objwww.pr.control.release.interfaces;

import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EN-10 版本中心查询面（O06/O07：状态从后端恢复、实际 revision/digest 可见；O08：
 * 非法入参就地 400 解释而非 500）：release_asset / config_bundle 的只读列表+明细，
 * 直调方法脸（Web 层为 Spring 样板，McpMountControllerTest 同构）。发布/激活不在
 * 本面——仍走 RELEASE 机器线（SecurityConfig 矩阵），页面只读展示。
 */
class ReleaseAssetQueryControllerTest {

    private static final Instant T0 = Instant.parse("2026-09-11T00:00:00Z");

    /** 内存资产仓储（listRecent 语义：created_at 倒序、kind 过滤、limit 收敛） */
    private static class MemAssets implements ReleaseAssetRepository {
        final List<ReleaseAsset> rows = new java.util.ArrayList<>();

        @Override
        public boolean insert(ReleaseAsset asset) {
            rows.add(asset);
            return true;
        }

        @Override
        public Optional<ReleaseAsset> findByDigest(String kind, Digest digest) {
            return rows.stream().filter(a -> a.kind().equals(kind)
                    && a.assetDigest().equals(digest)).findFirst();
        }

        @Override
        public List<ReleaseAsset> listRecent(String kind, int limit) {
            return rows.stream()
                    .filter(a -> kind == null || a.kind().equals(kind))
                    .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                    .limit(Math.max(0, limit))
                    .toList();
        }
    }

    /** 内存 bundle 仓储（listRecent 语义：revision 倒序；写面方法不在查询面消费） */
    private static class MemBundles implements ConfigBundleRepository {
        final Map<Digest, BundleSummary> rows = new LinkedHashMap<>();
        ActivePointer pointer;

        @Override
        public List<BundleSummary> listRecent(int limit) {
            return rows.values().stream()
                    .sorted((a, b) -> Long.compare(b.revision(), a.revision()))
                    .limit(Math.max(0, limit))
                    .toList();
        }

        @Override
        public long nextRevision() {
            return 0;
        }

        @Override
        public boolean insert(com.objwww.pr.control.release.domain.model.ConfigBundle bundle) {
            throw new UnsupportedOperationException("查询面单测不消费写面");
        }

        @Override
        public Optional<com.objwww.pr.control.release.domain.model.ConfigBundle> findByDigest(
                Digest digest) {
            return Optional.empty();
        }

        @Override
        public Optional<Digest> activeDigest() {
            return pointer == null ? Optional.empty() : Optional.of(pointer.bundleDigest());
        }

        @Override
        public Optional<ActivePointer> findActivePointer() {
            return Optional.ofNullable(pointer);
        }

        @Override
        public boolean activateQualified(Digest toDigest, long expectedActiveRevision,
                String by, Instant at) {
            return false;
        }

        void seed(ActivePointer p) {
            this.pointer = p;
        }
    }

    private static ReleaseAsset asset(String kind, Map<String, Object> content, Instant at) {
        return ReleaseAsset.of(kind, content, "en10", at);
    }

    private static ReleaseAsset runbook(String id, Instant at) {
        return asset(ReleaseAsset.KIND_RUNBOOK_DOC,
                Map.of("runbook_id", id, "title", id + " 手册",
                        "description", "排查步骤", "text", "1) 步骤"),
                at);
    }

    @Test
    @DisplayName("V01：list 默认面——全 kind、created_at 倒序、摘要投影（digest 可见）")
    void listReturnsReverseChronologicalSummaries() {
        MemAssets assets = new MemAssets();
        assets.insert(runbook("b-doc", T0));
        assets.insert(asset(ReleaseAsset.KIND_TOOL_SCHEMA,
                Map.of("schema", Map.of("type", "object")), T0.plusSeconds(60)));
        ReleaseAssetQueryController controller = new ReleaseAssetQueryController(
                assets, new MemBundles());

        ResponseEntity<Map<String, Object>> response = controller.list(null, 50);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = itemsOf(response);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("kind")).isEqualTo(ReleaseAsset.KIND_TOOL_SCHEMA);
        assertThat(items.get(0).get("digest").toString()).hasSize(64);
        assertThat(items.get(1).get("kind")).isEqualTo(ReleaseAsset.KIND_RUNBOOK_DOC);
        assertThat(items.get(1)).as("摘要投影含标题面，不含正文").containsKey("summary");
        assertThat(items.toString()).as("列表零正文（R05 最小披露同律）").doesNotContain("1) 步骤");
    }

    @Test
    @DisplayName("V02：kind 过滤命中；未知 kind → 400 就地解释")
    void kindFilterNarrowsAndUnknownKindIs400() {
        MemAssets assets = new MemAssets();
        assets.insert(runbook("b-doc", T0));
        assets.insert(asset(ReleaseAsset.KIND_PROMPT,
                Map.of("messages_template", "hi {{x}}", "variables_schema", List.of("x")),
                T0.plusSeconds(1)));
        ReleaseAssetQueryController controller = new ReleaseAssetQueryController(
                assets, new MemBundles());

        assertThat(itemsOf(controller.list(ReleaseAsset.KIND_RUNBOOK_DOC, 50))).hasSize(1);
        assertThat(controller.list("NOT_A_KIND", 50).getStatusCode())
                .as("O08：非法入参 400 就地解释，不 500 不伪成功")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("V03：limit 收敛 1..200；非法 limit → 400")
    void limitIsClampedAndInvalidRejected() {
        ReleaseAssetQueryController controller = new ReleaseAssetQueryController(
                new MemAssets(), new MemBundles());

        assertThat(controller.list(null, 0).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.list(null, -5).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.list(null, 999).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(itemsOf(controller.list(null, 999))).isEmpty();
    }

    @Test
    @DisplayName("V04：明细——(kind,digest) 精确解析 content 全量；缺 → 404；非法 digest → 400")
    void detailResolvesExactlyOrFailsHonest() {
        MemAssets assets = new MemAssets();
        ReleaseAsset doc = runbook("b-doc", T0);
        assets.insert(doc);
        ReleaseAssetQueryController controller = new ReleaseAssetQueryController(
                assets, new MemBundles());

        ResponseEntity<Map<String, Object>> hit = controller.detail(
                ReleaseAsset.KIND_RUNBOOK_DOC, doc.assetDigest().hex());
        assertThat(hit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hit.getBody()).containsEntry("kind", ReleaseAsset.KIND_RUNBOOK_DOC);
        assertThat(hit.getBody().get("content").toString()).contains("1) 步骤");

        assertThat(controller.detail(ReleaseAsset.KIND_RUNBOOK_DOC,
                "ab".repeat(32)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.detail(ReleaseAsset.KIND_RUNBOOK_DOC,
                "NOT-HEX").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.detail(ReleaseAsset.KIND_RUNBOOK_CATALOG,
                doc.assetDigest().hex()).getStatusCode())
                .as("kind 不同同 digest = 不同资产（port 语义）")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("V05：bundles 面——revision 倒序 + 当前指针 active 标记；无指针 active=null")
    void bundlesFaceMarksCurrentPointer() {
        MemBundles bundles = new MemBundles();
        Digest old = new Digest("ab".repeat(32));
        Digest current = new Digest("cd".repeat(32));
        bundles.rows.put(old, new ConfigBundleRepository.BundleSummary(old, 1, "r",
                T0, null));
        bundles.rows.put(current, new ConfigBundleRepository.BundleSummary(current, 2, "r",
                T0.plusSeconds(60), T0.plusSeconds(120)));
        bundles.seed(new ConfigBundleRepository.ActivePointer(current, 2,
                T0.plusSeconds(120)));
        ReleaseAssetQueryController controller = new ReleaseAssetQueryController(
                new MemAssets(), bundles);

        ResponseEntity<Map<String, Object>> response = controller.bundles();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("active")).asString().contains(current.hex());
        List<Map<String, Object>> items = castList(response.getBody().get("items"));
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("digest").toString()).isEqualTo(current.hex());
        assertThat(items.get(0).get("active")).isEqualTo(Boolean.TRUE);
        assertThat(items.get(1).get("active")).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("V06：空库 → 200 空集 + active null（页面空态友好，非 404）")
    void emptyStateIsHonest200() {
        ReleaseAssetQueryController controller = new ReleaseAssetQueryController(
                new MemAssets(), new MemBundles());

        assertThat(itemsOf(controller.list(null, 50))).isEmpty();
        ResponseEntity<Map<String, Object>> bundles = controller.bundles();
        assertThat(bundles.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bundles.getBody().get("active")).isNull();
        assertThat(castList(bundles.getBody().get("items"))).isEmpty();
    }

    // ------------------------------------------------------------------ 辅助

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(
            ResponseEntity<Map<String, Object>> response) {
        return castList(response.getBody().get("items"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object raw) {
        return (List<Map<String, Object>>) raw;
    }
}
