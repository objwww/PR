package com.objwww.pr.control.it;

import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresConfigBundleRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReleaseAssetRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EN-10 真 PG 数据面（L1，195 窗真值；本机无 docker 自动跳过）：版本中心两个新查询
 * 钉——release_asset listRecent（created_at 倒序/kind 过滤/limit 收敛）+ config_bundle
 * listRecent（revision 倒序/当前指针 active 标记）；O09 备份恢复的"能定位原版本"
 * 前提 = (kind,digest) 精确解析在新查询面下不漂移。
 */
class En10VersionCenterIT extends PostgresITBase {

    private static final Instant T0 = Instant.parse("2026-09-11T00:00:00Z");

    private ReleaseAssetRepository assets;
    private ConfigBundleRepository bundles;
    private JdbcClient jdbc;

    @BeforeEach
    void wire() {
        // config 两表不在基座 TRUNCATE 清单（V24 种子行自管，BA-43 惯例）：全量跑时
        // 他类残留行（同 rev 1/2）会被 listRecent 全量捞出——共享库污染必须自清，
        // 与 PostgresConfigBundleRepositoryTest 同构（pointer 先删再重种未激活行）
        adminJdbc.sql("DELETE FROM config_bundle_active").update();
        adminJdbc.sql("DELETE FROM config_bundle").update();
        adminJdbc.sql("INSERT INTO config_bundle_active (id) VALUES (1)").update();
        assets = new PostgresReleaseAssetRepository(controlDataSource());
        bundles = new PostgresConfigBundleRepository(controlDataSource());
        jdbc = controlJdbc;
    }

    @Test
    @DisplayName("release_asset listRecent：倒序/kind 过滤/limit 收敛 + (kind,digest) 精确回读")
    void assetListingOrdersFiltersAndResolvesExactly() {
        ReleaseAsset old = insertAsset(ReleaseAsset.KIND_RUNBOOK_DOC,
                Map.of("runbook_id", "doc-a", "title", "t", "description", "d",
                        "text", "旧文档"),
                T0);
        insertAsset(ReleaseAsset.KIND_RUNBOOK_DOC,
                Map.of("runbook_id", "doc-b", "title", "t", "description", "d",
                        "text", "新文档"),
                T0.plusSeconds(60));
        insertAsset(ReleaseAsset.KIND_TOOL_SCHEMA,
                Map.of("schema", Map.of("type", "object")), T0.plusSeconds(120));

        List<ReleaseAsset> all = assets.listRecent(null, 200);
        assertThat(all).extracting(ReleaseAsset::createdAt)
                .as("created_at 倒序（最新在前）")
                .isSortedAccordingTo((a, b) -> b.compareTo(a));
        assertThat(all).hasSize(3);

        List<ReleaseAsset> runbooks = assets.listRecent(ReleaseAsset.KIND_RUNBOOK_DOC, 200);
        assertThat(runbooks).hasSize(2);
        assertThat(runbooks).allSatisfy(a ->
                assertThat(a.kind()).isEqualTo(ReleaseAsset.KIND_RUNBOOK_DOC));

        assertThat(assets.listRecent(null, 2)).as("limit 收敛").hasSize(2);
        assertThat(assets.listRecent(null, 0)).isEmpty();

        assertThat(assets.findByDigest(ReleaseAsset.KIND_RUNBOOK_DOC, old.assetDigest())
                .orElseThrow().content().get("runbook_id")).isEqualTo("doc-a");
    }

    @Test
    @DisplayName("config_bundle listRecent：revision 倒序 + 当前指针 active 标记")
    void bundleListingMarksCurrentPointer() {
        UUID older = insertBundle("bb".repeat(32), 1, T0);
        UUID newer = insertBundle("cc".repeat(32), 2, T0.plusSeconds(60));
        activate(newer, T0.plusSeconds(120));

        List<ConfigBundleRepository.BundleSummary> items = bundles.listRecent(200);

        assertThat(items).extracting(ConfigBundleRepository.BundleSummary::revision)
                .containsExactly(2L, 1L);
        assertThat(items.get(0).digest().hex()).isEqualTo("cc".repeat(32));
        assertThat(items.get(0).activatedAt()).as("当前指针行带激活时刻").isNotNull();
        assertThat(items.get(1).activatedAt()).as("历史行 active=null").isNull();
    }

    // ------------------------------------------------------------------ 种子与辅助

    private ReleaseAsset insertAsset(String kind, Map<String, Object> content, Instant at) {
        ReleaseAsset asset = ReleaseAsset.of(kind, content, "en10-it", at);
        assets.insert(asset);
        return asset;
    }

    private UUID insertBundle(String digestHex, long revision, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO config_bundle (id, bundle_digest, revision, content,
                    created_by, created_at)
                VALUES (:id, :digest, :revision, CAST(:content AS jsonb), :by, :at)
                """)
                .param("id", id).param("digest", digestHex).param("revision", revision)
                .param("content", "{\"prompts\":{}}").param("by", "en10-it")
                .param("at", Timestamp.from(at))
                .update();
        return id;
    }

    private void activate(UUID bundleId, Instant at) {
        String digest = jdbc.sql("SELECT bundle_digest FROM config_bundle WHERE id = :id")
                .param("id", bundleId)
                .query((rs, i) -> rs.getString("bundle_digest")).single();
        jdbc.sql("""
                UPDATE config_bundle_active
                   SET bundle_digest = :digest, activated_at = :at
                 WHERE id = 1
                """)
                .param("digest", digest).param("at", Timestamp.from(at))
                .update();
    }
}
