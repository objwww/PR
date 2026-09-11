package com.objwww.pr.control.infrastructure.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolExecutor;
import com.objwww.pr.control.alert.application.rag.RunbookCorpusPublisher;
import com.objwww.pr.control.alert.application.rag.RunbookCorpusStore;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;
import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EN-07 RAG 三执行器的 L0 面（固定语料卡 R01～R12 的可执行子集）：
 * <ul>
 *   <li>R01：fetch_runbook 命中登记 id → 正文 + doc_digest/corpus_digest 版本面；</li>
 *   <li>R02：NO_DATA（无相关文档）与 SOURCE_UNAVAILABLE（语料不可达）分码区分；</li>
 *   <li>R03：过适用期 → excluded + 排除原因展示，不作有效建议；</li>
 *   <li>R04：路径穿越/任意 URL/未登记 id → INVALID_ARGS，模式面零资产触达；</li>
 *   <li>R05：资料仅参考——正文响应携带"不构成根因结论/不得改变调查策略"标记；</li>
 *   <li>R10：history_rca_search 越权 service 发出前拒（prom 同律，预算零扣）；</li>
 *   <li>R12：完整性失败 → INTEGRITY 明确不可验证，不读 latest 补齐。</li>
 * </ul>
 */
class RunbookExecutorsTest {

    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MISSING_HEX = "bb".repeat(32);

    /** 内存资产仓储（loads 计数供"零资产触达"断言，R04） */
    private static class MemAssets implements ReleaseAssetRepository {
        final Map<String, ReleaseAsset> rows = new LinkedHashMap<>();
        int loads;

        @Override
        public boolean insert(ReleaseAsset asset) {
            return rows.putIfAbsent(asset.kind() + "|" + asset.assetDigest().hex(), asset) == null;
        }

        @Override
        public Optional<ReleaseAsset> findByDigest(String kind, Digest digest) {
            loads++;
            return Optional.ofNullable(rows.get(kind + "|" + digest.hex()));
        }

        @Override
        public List<ReleaseAsset> listRecent(String kind, int limit) {
            return List.of();
        }
    }

    private MemAssets assets;
    private Digest corpus;
    private RunbookCorpusStore store;
    private Clock clock;

    @BeforeEach
    void seedCorpus() {
        assets = new MemAssets();
        store = new RunbookCorpusStore(assets);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        corpus = new RunbookCorpusPublisher(assets).publish(List.of(
                        new RunbookCorpusPublisher.DocInput("kafka-consumer-lag",
                                "Kafka 消费积压处置", "消费组 lag 越限时的分区与吞吐排查步骤",
                                "1) 检查分区分配 2) 对比消费吞吐", List.of("kafka", "java"),
                                null, null),
                        new RunbookCorpusPublisher.DocInput("legacy-tls-rotate",
                                "旧版证书轮换", "已退役的 TLS 轮换步骤，仅存档",
                                "1) 生成 CSR 2) 替换证书", List.of("tls"),
                                NOW.minusSeconds(86_400 * 30), NOW.minusSeconds(86_400))),
                "en07", NOW);
    }

    private static ToolExecutor.ToolExecution exec(Map<String, Object> args) {
        return new ToolExecutor.ToolExecution(args, Long.MAX_VALUE, 65_536);
    }

    private static JsonNode bodyOf(FetchRunbookExecutor executor, String runbookId) {
        byte[] body = executor.execute(exec(Map.of("runbook_id", runbookId)));
        return read(body);
    }

    private static JsonNode read(byte[] body) {
        try {
            return MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------ fetch_runbook

    /** F01（R01）：命中登记 id → 正文 + 双 digest 版本面 + 参考标记（R05） */
    @Test
    @DisplayName("F01：fetch 命中登记 id——正文往返 + doc/corpus 双 digest + 参考标记")
    void fetchRegisteredIdReturnsTextWithVersionFace() {
        FetchRunbookExecutor executor = new FetchRunbookExecutor(store, corpus, clock);
        String registeredDigest = store.load(corpus).entries().stream()
                .filter(e -> e.runbookId().equals("kafka-consumer-lag")).findFirst().orElseThrow()
                .docDigest();

        JsonNode row = bodyOf(executor, "kafka-consumer-lag")
                .path("data").path("result").get(0);

        assertThat(row.path("text").asText()).contains("检查分区分配");
        assertThat(row.path("doc_digest").asText()).isEqualTo(registeredDigest);
        assertThat(row.path("corpus_digest").asText()).isEqualTo(corpus.hex());
        assertThat(row.path("note").asText())
                .as("R05：资料仅参考，不构成根因结论")
                .contains("参考").contains("根因");
    }

    /** F04（R04）：路径穿越/任意 URL → INVALID_ARGS 且模式面零资产触达 */
    @Test
    @DisplayName("F04：../ 与 http URL → INVALID_ARGS，零资产触达")
    void traversalAndUrlAreRejectedBeforeAnyAssetRead() {
        FetchRunbookExecutor executor = new FetchRunbookExecutor(store, corpus, clock);

        for (String hostile : List.of("../etc/passwd", "http://evil.example/runbook",
                "..\\windows\\system32", "a/b", "a b")) {
            assertThatThrownBy(() -> bodyOf(executor, hostile))
                    .as(hostile)
                    .isInstanceOf(ToolControlPlaneException.class)
                    .hasFieldOrPropertyWithValue("reason", ToolControlReason.INVALID_ARGS);
        }
        assertThat(assets.loads).as("R04：任意路径/URL 在资产面前拒（零触达）").isZero();
    }

    /** F04c（R04）：合法形状但未登记 id → INVALID_ARGS（不猜不补，仅目录一次读） */
    @Test
    @DisplayName("F04c：未登记 id → INVALID_ARGS，仅目录读、文档零读")
    void unregisteredIdIsInvalidArgsWithCatalogOnlyRead() {
        FetchRunbookExecutor executor = new FetchRunbookExecutor(store, corpus, clock);

        assertThatThrownBy(() -> bodyOf(executor, "ghost-doc"))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("未在目录登记");
        assertThat(assets.loads).as("装载即验整卷：1 目录 + 2 文档，正文零外泄").isEqualTo(3);
    }

    /** F03（R03）：过适用期 → 排除行（EXPIRED + 窗口展示），无正文，账本面 SUCCESS */
    @Test
    @DisplayName("F03：过期 runbook → EXPIRED 排除行，无正文")
    void expiredRunbookIsExcludedWithReason() {
        FetchRunbookExecutor executor = new FetchRunbookExecutor(store, corpus, clock);

        JsonNode row = bodyOf(executor, "legacy-tls-rotate")
                .path("data").path("result").get(0);

        assertThat(row.path("status").asText()).isEqualTo("EXPIRED");
        assertThat(row.path("effective_until").asText()).isEqualTo(
                NOW.minusSeconds(86_400).toString());
        assertThat(row.has("text")).as("过期不作有效建议，不给正文").isFalse();
        assertThat(row.path("note").asText()).contains("有效期");
    }

    /** F02（R02）：目录 digest 缺席 → SOURCE_UNAVAILABLE（与 NO_DATA 分码区分） */
    @Test
    @DisplayName("F02：语料目录缺席 → SOURCE_UNAVAILABLE")
    void missingCorpusIsSourceUnavailable() {
        FetchRunbookExecutor executor = new FetchRunbookExecutor(store,
                new Digest(MISSING_HEX), clock);

        assertThatThrownBy(() -> bodyOf(executor, "kafka-consumer-lag"))
                .isInstanceOf(ToolModelVisibleException.class)
                .hasFieldOrPropertyWithValue("reason", ToolModelVisibleReason.SOURCE_UNAVAILABLE);
    }

    /** F12（R12）：登记文档缺席 → INTEGRITY 明确不可验证，不读 latest 补齐 */
    @Test
    @DisplayName("F12：登记文档缺失 → INTEGRITY 不可验证，不回退补齐")
    void missingRegisteredDocumentIsIntegrityFailure() {
        MemAssets broken = new MemAssets();
        LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
        entry.put("runbook_id", "rb");
        entry.put("title", "t");
        entry.put("description", "d");
        entry.put("doc_digest", MISSING_HEX);
        ReleaseAsset catalog = ReleaseAsset.of(ReleaseAsset.KIND_RUNBOOK_CATALOG,
                Map.of("schema_version", "1", "documents", List.of(entry)), "en07", NOW);
        broken.insert(catalog);
        RunbookCorpusStore brokenStore = new RunbookCorpusStore(broken);

        FetchRunbookExecutor executor = new FetchRunbookExecutor(brokenStore,
                catalog.assetDigest(), clock);

        assertThatThrownBy(() -> bodyOf(executor, "rb"))
                .isInstanceOf(ToolModelVisibleException.class)
                .hasFieldOrPropertyWithValue("reason",
                        ToolModelVisibleReason.SOURCE_UNAVAILABLE)
                .hasMessageContaining("INTEGRITY");
    }

    // ---------------------------------------------------- runbook_catalog_search

    /** C01（R01 链首）：match 命中（大小写不敏感）→ ACTIVE 条目带 digest */
    @Test
    @DisplayName("C01：match 命中→ACTIVE 条目带 doc_digest")
    void catalogMatchReturnsActiveEntries() {
        RunbookCatalogSearchExecutor executor =
                new RunbookCatalogSearchExecutor(store, corpus, clock);

        JsonNode hit = read(executor.execute(exec(Map.of("match", "KAFKA"))));

        assertThat(hit.path("data").path("result")).hasSize(1);
        assertThat(hit.path("data").path("result").get(0).path("runbook_id").asText())
                .isEqualTo("kafka-consumer-lag");
        assertThat(hit.path("data").path("result").get(0).path("status").asText())
                .isEqualTo("ACTIVE");
        assertThat(hit.path("data").path("result").get(0).path("doc_digest").asText())
                .hasSize(64);
    }

    /** C02（R02）：无相关文档 → NO_DATA（与 SOURCE_UNAVAILABLE 分码区分） */
    @Test
    @DisplayName("C02：无匹配 → NO_DATA（≠ SOURCE_UNAVAILABLE）")
    void noMatchIsNoDataNotSourceUnavailable() {
        RunbookCatalogSearchExecutor executor =
                new RunbookCatalogSearchExecutor(store, corpus, clock);

        assertThatThrownBy(() -> executor.execute(exec(Map.of("match", "zzz"))))
                .isInstanceOf(ToolModelVisibleException.class)
                .hasFieldOrPropertyWithValue("reason", ToolModelVisibleReason.NO_DATA);
    }

    /** C03（R03 显示面）：全量列表中过期条目标 EXPIRED（排除原因可见） */
    @Test
    @DisplayName("C03：全量列表过期条目标 EXPIRED 展示排除原因")
    void fullListingMarksExpiredEntries() {
        RunbookCatalogSearchExecutor executor =
                new RunbookCatalogSearchExecutor(store, corpus, clock);

        JsonNode body = read(executor.execute(exec(Map.of())));

        assertThat(body.path("data").path("result")).hasSize(2);
        assertThat(body.path("data").path("result").get(1).path("runbook_id").asText())
                .isEqualTo("legacy-tls-rotate");
        assertThat(body.path("data").path("result").get(1).path("status").asText())
                .isEqualTo("EXPIRED");
        assertThat(body.path("data").path("result").get(1).has("text"))
                .as("列表面不带正文（R05 最小披露）").isFalse();
    }

    /** C04：tag 过滤 */
    @Test
    @DisplayName("C04：tag 过滤命中单条")
    void tagFilterNarrowsResults() {
        RunbookCatalogSearchExecutor executor =
                new RunbookCatalogSearchExecutor(store, corpus, clock);

        JsonNode body = read(executor.execute(exec(Map.of("tag", "tls"))));

        assertThat(body.path("data").path("result")).hasSize(1);
        assertThat(body.path("data").path("result").get(0).path("runbook_id").asText())
                .isEqualTo("legacy-tls-rotate");
    }

    /** C05（R02 对面）：目录缺席时 catalog 搜索 → SOURCE_UNAVAILABLE（与 C02 成对） */
    @Test
    @DisplayName("C05：目录缺席 → SOURCE_UNAVAILABLE（与 NO_MATCH 成对区分）")
    void missingCatalogOnSearchIsSourceUnavailable() {
        RunbookCatalogSearchExecutor executor =
                new RunbookCatalogSearchExecutor(store, new Digest(MISSING_HEX), clock);

        assertThatThrownBy(() -> executor.execute(exec(Map.of("match", "kafka"))))
                .isInstanceOf(ToolModelVisibleException.class)
                .hasFieldOrPropertyWithValue("reason",
                        ToolModelVisibleReason.SOURCE_UNAVAILABLE);
    }

    // ---------------------------------------------------- history_rca_search（静态面）

    /** H10（R10）：越权 service 发出前拒（prom 同律：INVALID_ARGS，预算零扣） */
    @Test
    @DisplayName("H10：service 越权 → INVALID_ARGS 发出前拒（R10 先行过滤）")
    void outOfScopeServiceRejectedBeforeQuery() {
        assertThatThrownBy(() -> HistoryRcaSearchExecutor.parseArgs(
                        Map.of("service", "payment-api",
                                "since", "2026-09-01T00:00:00Z",
                                "until", "2026-09-10T00:00:00Z"),
                        Set.of("order-service")))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasFieldOrPropertyWithValue("reason", ToolControlReason.INVALID_ARGS)
                .hasMessageContaining("权限范围");
    }

    /** H02：缺 service / 窗超 30d → INVALID_ARGS */
    @Test
    @DisplayName("H02：缺 service 与窗超限 → INVALID_ARGS")
    void missingServiceAndOversizedWindowRejected() {
        assertThatThrownBy(() -> HistoryRcaSearchExecutor.parseArgs(
                        Map.of("since", "2026-09-01T00:00:00Z",
                                "until", "2026-09-10T00:00:00Z"),
                        Set.of("order-service")))
                .isInstanceOf(ToolControlPlaneException.class);
        assertThatThrownBy(() -> HistoryRcaSearchExecutor.parseArgs(
                        Map.of("service", "order-service",
                                "since", "2026-07-01T00:00:00Z",
                                "until", "2026-09-10T00:00:00Z"),
                        Set.of("order-service")))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasFieldOrPropertyWithValue("reason", ToolControlReason.INVALID_ARGS);
    }

    /** H06（R06 E 面）：渲染携带"历史结论≠当前根因/保留反证"参考标记 */
    @Test
    @DisplayName("H06：渲染带历史参考标记与反证纪律（R06）")
    void renderCarriesHistoryReferenceMarker() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("report_id", "11111111-1111-1111-1111-111111111111");
        row.put("run_id", "22222222-2222-2222-2222-222222222222");
        row.put("incident_key", "alertname=HighLatency|service=order-service");
        row.put("prior_summary", "上次根因：下游超时");
        row.put("created_at", "2026-09-01T00:00:00Z");

        byte[] body = HistoryRcaSearchExecutor.render(List.of(row), false, 65_536);
        JsonNode json = read(body);

        assertThat(json.path("status").asText()).isEqualTo("success");
        assertThat(json.path("data").path("note").asText())
                .contains("参考").contains("根因").contains("反证");
        assertThat(json.path("data").path("result").get(0).path("prior_summary").asText())
                .isEqualTo("上次根因：下游超时");
    }

    /** H03：allowlist 空集构造期硬失败（fail-closed，prom 同律） */
    @Test
    @DisplayName("H03：空 allowlist 构造期硬失败")
    void emptyAllowlistFailsFast() {
        assertThatThrownBy(() -> new HistoryRcaSearchExecutor(null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
