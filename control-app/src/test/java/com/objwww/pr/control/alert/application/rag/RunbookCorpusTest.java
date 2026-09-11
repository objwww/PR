package com.objwww.pr.control.alert.application.rag;

import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EN-07 固定语料库（§三阶段 1）store/publisher 的 L0 域面：语料资产复用 release_asset
 * （V60，§8.2"优先复用现有表"）——目录资产（RUNBOOK_CATALOG）按 (kind,digest) 精确解析、
 * 文档资产（RUNBOOK_DOC）按目录登记 digest 取文。四条红线钉在域面：
 * <ul>
 *   <li>完整性 = digest 寻址（R12）：文档缺失/条目身份不符 → 明确不可验证，结构上
 *       不可能"读 latest 补齐"（没有 latest 可读）；</li>
 *   <li>快照两绑定（R07）：Run 持 catalog digest + snapshot 引用，新发布换 digest
 *       不换旧引用（新 Run 读新快照）；</li>
 *   <li>有效期窗（R03）：store 面排除过期/未生效条目；</li>
 *   <li>密钥键名 fail-closed（INV-AM5-5）：新 kind 继承资产注册面同一道闸。</li>
 * </ul>
 */
class RunbookCorpusTest {

    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");
    private static final String MISSING_HEX = "aa".repeat(32);

    /** 内存资产仓储：(kind,digest) 幂等语义同 Postgres 实现，loads 计数供零触达断言 */
    private static class MemAssets implements ReleaseAssetRepository {
        final Map<String, ReleaseAsset> rows = new LinkedHashMap<>();
        int loads;

        private static String key(String kind, Digest digest) {
            return kind + "|" + digest.hex();
        }

        @Override
        public boolean insert(ReleaseAsset asset) {
            return rows.putIfAbsent(key(asset.kind(), asset.assetDigest()), asset) == null;
        }

        @Override
        public Optional<ReleaseAsset> findByDigest(String kind, Digest digest) {
            loads++;
            return Optional.ofNullable(rows.get(key(kind, digest)));
        }
    }

    private static RunbookCorpusPublisher.DocInput doc(String id, String description,
            String text) {
        return new RunbookCorpusPublisher.DocInput(id, id + " 处置手册", description, text,
                List.of("kafka"), null, null);
    }

    /** T01：发布→加载往返（R01 版本固定面：条目 doc_digest 与取文 digest 一致可回读） */
    @Test
    @DisplayName("T01：发布两文档→目录→加载往返，doc_digest 与登记一致")
    void publishThenLoadRoundTripPinsVersionedCorpus() {
        MemAssets assets = new MemAssets();
        RunbookCorpusPublisher publisher = new RunbookCorpusPublisher(assets);
        RunbookCorpusStore store = new RunbookCorpusStore(assets);

        Digest corpus = publisher.publish(List.of(
                        doc("kafka-consumer-lag", "消费组 lag 越限排查", "1) 查分区分配 2) 对比吞吐"),
                        doc("db-connection-pool", "连接池耗尽排查", "1) 查活跃连接 2) 查慢查询")),
                "en07", NOW);
        RunbookCorpusStore.CorpusSnapshot snapshot = store.load(corpus);

        assertThat(snapshot.catalogDigest()).isEqualTo(corpus);
        assertThat(snapshot.entries()).extracting(
                        RunbookCorpusStore.CatalogEntry::runbookId)
                .containsExactlyInAnyOrder("kafka-consumer-lag", "db-connection-pool");
        for (RunbookCorpusStore.CatalogEntry entry : snapshot.entries()) {
            RunbookCorpusStore.RunbookDocument document = store.document(snapshot, entry);
            assertThat(document.runbookId()).isEqualTo(entry.runbookId());
            assertThat(document.docDigest()).isEqualTo(entry.docDigest());
            assertThat(document.text()).as("正文可回读").isNotBlank();
        }
    }

    /** T02：同语料重发幂等——digest 相同、资产零新行（内容寻址注册幂等锚） */
    @Test
    @DisplayName("T02：同语料重发幂等，同 digest 零新行")
    void republishSameCorpusIsIdempotent() {
        MemAssets assets = new MemAssets();
        RunbookCorpusPublisher publisher = new RunbookCorpusPublisher(assets);
        List<RunbookCorpusPublisher.DocInput> docs = List.of(
                doc("kafka-consumer-lag", "消费组 lag 越限排查", "1) 查分区分配"));

        Digest first = publisher.publish(docs, "en07", NOW);
        Digest second = publisher.publish(docs, "en07", NOW);

        assertThat(second).isEqualTo(first);
        assertThat(assets.rows).as("1 文档 + 1 目录，重发零新行").hasSize(2);
    }

    /** T03：一字之差即新身份（S10 面）；旧目录并存不覆盖，原文不被篡改版污染 */
    @Test
    @DisplayName("T03：正文一字之差→新目录 digest，旧快照原样并存")
    void oneWordChangeYieldsNewIdentityAndOldSnapshotStaysIntact() {
        MemAssets assets = new MemAssets();
        RunbookCorpusPublisher publisher = new RunbookCorpusPublisher(assets);
        RunbookCorpusStore store = new RunbookCorpusStore(assets);

        Digest v1 = publisher.publish(List.of(doc("rb", "原描述", "原文正文")), "en07", NOW);
        Digest v2 = publisher.publish(List.of(doc("rb", "原描述", "篡改后正文")), "en07", NOW);

        assertThat(v2).isNotEqualTo(v1);
        assertThat(store.load(v1).entries().get(0))
                .as("旧目录仍指原文档，篡改版不污染旧身份")
                .satisfies(e -> assertThat(store.document(store.load(v1), e).text())
                        .isEqualTo("原文正文"));
        assertThat(store.load(v2).entries().get(0))
                .satisfies(e -> assertThat(store.document(store.load(v2), e).text())
                        .isEqualTo("篡改后正文"));
    }

    /** T04（R07 E 面域锚）：Run 构造期 pin 两绑定——catalog digest + snapshot 引用；
     * 新发布换 digest 不换旧引用，新 Run 读新快照 */
    @Test
    @DisplayName("T04：快照两绑定——新发布不换旧引用（Run 中更新语料下轮才生效的域面）")
    void snapshotBindingSurvivesNewPublication() {
        MemAssets assets = new MemAssets();
        RunbookCorpusPublisher publisher = new RunbookCorpusPublisher(assets);
        RunbookCorpusStore store = new RunbookCorpusStore(assets);

        Digest v1 = publisher.publish(List.of(doc("rb", "v1 描述", "v1 正文")), "en07", NOW);
        RunbookCorpusStore.CorpusSnapshot pinned = store.load(v1);
        Digest v2 = publisher.publish(List.of(doc("rb", "v2 描述", "v1 正文")), "en07", NOW);
        RunbookCorpusStore.CorpusSnapshot next = store.load(v2);

        assertThat(next.catalogDigest()).isNotEqualTo(pinned.catalogDigest());
        assertThat(pinned.entries().get(0).description()).as("旧引用不静默换参").isEqualTo("v1 描述");
        assertThat(next.entries().get(0).description()).isEqualTo("v2 描述");
        assertThat(store.document(pinned, pinned.entries().get(0)).docDigest())
                .isEqualTo(pinned.entries().get(0).docDigest());
    }

    /** T05（R03 域面）：有效期窗——过期/未生效条目被 activeEntries 排除 */
    @Test
    @DisplayName("T05：activeEntries 排除过期与未生效条目，无窗条目恒在")
    void activeEntriesExcludeOutOfWindowDocuments() {
        MemAssets assets = new MemAssets();
        RunbookCorpusPublisher publisher = new RunbookCorpusPublisher(assets);
        RunbookCorpusStore store = new RunbookCorpusStore(assets);

        Digest corpus = publisher.publish(List.of(
                        new RunbookCorpusPublisher.DocInput("expired-doc", "t", "d", "x",
                                List.of(), NOW.minusSeconds(3_600), NOW.minusSeconds(60)),
                        new RunbookCorpusPublisher.DocInput("future-doc", "t", "d", "x",
                                List.of(), NOW.plusSeconds(60), null),
                        new RunbookCorpusPublisher.DocInput("always-doc", "t", "d", "x",
                                List.of(), null, null)),
                "en07", NOW);
        RunbookCorpusStore.CorpusSnapshot snapshot = store.load(corpus);

        assertThat(store.activeEntries(snapshot, NOW))
                .extracting(RunbookCorpusStore.CatalogEntry::runbookId)
                .containsExactly("always-doc");
        assertThat(snapshot.entries()).as("全量条目仍在目录中（排除≠删除）").hasSize(3);
    }

    /** T06（R02 区分面）：目录资产缺席 → CorpusUnavailable（≠ 无匹配 ≠ 完整性失败） */
    @Test
    @DisplayName("T06：目录资产缺席→CorpusUnavailable，不猜不补")
    void missingCatalogAssetIsUnavailable() {
        MemAssets assets = new MemAssets();
        RunbookCorpusStore store = new RunbookCorpusStore(assets);

        assertThatThrownBy(() -> store.load(new Digest(MISSING_HEX)))
                .isInstanceOf(RunbookCorpusStore.CorpusUnavailableException.class);
    }

    /** T07（R12）：目录登记的文档资产缺席 → CorpusIntegrity——明确不可验证，
     * 结构上不存在"读 latest 补齐"的路径 */
    @Test
    @DisplayName("T07：文档资产缺席→CorpusIntegrity，不回退补齐")
    void missingDocumentAssetIsIntegrityFailure() {
        MemAssets assets = new MemAssets();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("runbook_id", "rb");
        entry.put("title", "t");
        entry.put("description", "d");
        entry.put("doc_digest", MISSING_HEX);
        ReleaseAsset catalog = ReleaseAsset.of(ReleaseAsset.KIND_RUNBOOK_CATALOG,
                Map.of("schema_version", "1", "documents", List.of(entry)),
                "en07", NOW);
        assets.insert(catalog);
        RunbookCorpusStore store = new RunbookCorpusStore(assets);

        assertThatThrownBy(() -> store.load(catalog.assetDigest()))
                .isInstanceOf(RunbookCorpusStore.CorpusIntegrityException.class)
                .hasMessageContaining("rb");
    }

    /** T08（R12 变体）：目录条目身份与文档资产不符（claimed id ≠ 实际 id）→ 拒绝取文 */
    @Test
    @DisplayName("T08：目录条目与文档身份不符→CorpusIntegrity")
    void identityMismatchBetweenCatalogAndDocumentFails() {
        MemAssets assets = new MemAssets();
        RunbookCorpusPublisher publisher = new RunbookCorpusPublisher(assets);
        publisher.publish(List.of(doc("real-id", "d", "x")), "en07", NOW);
        ReleaseAsset document = assets.rows.values().stream()
                .filter(a -> ReleaseAsset.KIND_RUNBOOK_DOC.equals(a.kind())).findFirst().orElseThrow();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("runbook_id", "claimed-id");
        entry.put("title", "t");
        entry.put("description", "d");
        entry.put("doc_digest", document.assetDigest().hex());
        ReleaseAsset catalog = ReleaseAsset.of(ReleaseAsset.KIND_RUNBOOK_CATALOG,
                Map.of("schema_version", "1", "documents", List.of(entry)),
                "en07", NOW);
        assets.insert(catalog);
        RunbookCorpusStore store = new RunbookCorpusStore(assets);

        // 装载即验整卷：身份不符在 load 期即拒（不产出部分可用快照）
        assertThatThrownBy(() -> store.load(catalog.assetDigest()))
                .isInstanceOf(RunbookCorpusStore.CorpusIntegrityException.class);
    }

    /** T09（红线）：新 kind 继承密钥键名 fail-closed（INV-AM5-5 资产面同一道闸） */
    @Test
    @DisplayName("T09：RUNBOOK_DOC 内容带密钥键名→注册即拒")
    void runbookDocRejectsSecretKeyMaterial() {
        assertThatThrownBy(() -> ReleaseAsset.of(ReleaseAsset.KIND_RUNBOOK_DOC,
                Map.of("runbook_id", "rb", "title", "t", "description", "d", "text", "x",
                        "api_key", "sk-123"),
                "en07", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
