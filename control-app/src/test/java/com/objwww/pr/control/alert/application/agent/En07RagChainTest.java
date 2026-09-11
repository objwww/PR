package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.rag.RunbookCorpusPublisher;
import com.objwww.pr.control.alert.application.rag.RunbookCorpusStore;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolExecutor;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import com.objwww.pr.control.infrastructure.rag.FetchRunbookExecutor;
import com.objwww.pr.control.infrastructure.rag.RunbookCatalogSearchExecutor;
import com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EN-07（R01/R05 E 脸）受控 RAG 链：catalog 搜索 → fetch 取文两跳全经 Gateway 唯一
 * 咽喉（真执行器 + 内存资产仓储）——登记条目按 description 由模型匹配面渲染，fetch
 * 只收登记 id；证据类型 runbook.catalog / runbook.reference 各落一条（版本面双 digest
 * 固定，R01），正文携带"仅参考、不构成根因结论"标记（R05：RAG 结果只进 Findings
 * 参考区，永不单独支撑 ROOT_CAUSE）。
 */
class En07RagChainTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID TASK = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();
    private static final String TIME_RANGE = "2026-09-10T00:00:00Z/2026-09-10T00:05:00Z";
    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MemLedger ledger = new MemLedger();
    private final MemEvidence evidence = new MemEvidence();

    /** T01（R01 链）：catalog→fetch 两跳真执行器；证据双 digest 版本面 + R05 参考标记 */
    @Test
    @DisplayName("T01：catalog→fetch 两跳，runbook.reference 证据带双 digest 与参考标记")
    void catalogThenFetchChainProducesVersionedReferenceEvidence() {
        MemAssets assets = new MemAssets();
        RunbookCorpusStore store = new RunbookCorpusStore(assets);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Digest corpus = new RunbookCorpusPublisher(assets).publish(List.of(
                new RunbookCorpusPublisher.DocInput("kafka-consumer-lag",
                        "Kafka 消费积压处置", "消费组 lag 越限时的分区与吞吐排查步骤",
                        "1) 检查分区分配 2) 对比消费吞吐", List.of("kafka"), null, null)),
                "en07-chain", NOW);
        String registeredDigest = store.load(corpus).entries().get(0).docDigest();

        ToolRegistry registry = new ToolRegistry(List.of(
                reg(DirectReadToolCatalog.TOOL_RUNBOOK_CATALOG,
                        new RunbookCatalogSearchExecutor(store, corpus, clock)),
                reg(DirectReadToolCatalog.TOOL_RUNBOOK_FETCH,
                        new FetchRunbookExecutor(store, corpus, clock))));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ToolGateway gateway = new ToolGateway(registry, policyFor(
                DirectReadToolCatalog.TOOL_RUNBOOK_CATALOG,
                DirectReadToolCatalog.TOOL_RUNBOOK_FETCH), pool,
                Clock.systemUTC(), null);
        DirectReadToolAgent catalog = agent(registry, gateway,
                DirectReadToolCatalog.TOOL_RUNBOOK_CATALOG, "runbook.catalog");
        DirectReadToolAgent fetch = agent(registry, gateway,
                DirectReadToolCatalog.TOOL_RUNBOOK_FETCH, "runbook.reference");

        // 观测驱动顺序：目录按 description 匹配 →（命中 id 喂给取文）正文
        SingleToolEvidenceAgent.AgentResult hop1 = catalog.investigate(
                context(1), Map.of("match", "lag"));
        SingleToolEvidenceAgent.AgentResult hop2 = fetch.investigate(
                context(2), Map.of("runbook_id", "kafka-consumer-lag"));

        assertThat(List.of(hop1.outcome(), hop2.outcome()))
                .containsOnly(SingleToolEvidenceAgent.AgentOutcome.EVIDENCE_PRODUCED);
        assertThat(evidence.rows).hasSize(2);
        assertThat(evidence.rows.stream().map(EvidenceEnvelope::evidenceType))
                .containsExactly("runbook.catalog", "runbook.reference");
        assertThat(evidence.rows).allSatisfy(e -> assertThat(e.source()).isEqualTo("rag"));
        JsonNode reference = read(evidence.rows.get(1).canonicalPayload());
        assertThat(reference.toString())
                .as("R01：版本面 doc_digest + corpus_digest 固定")
                .contains(registeredDigest)
                .contains(corpus.hex());
        assertThat(reference.toString())
                .as("R05：资料仅参考，不构成根因结论")
                .contains("参考").contains("根因");
        assertThat(ledger.rows).hasSize(2);
        assertThat(ledger.rows).allSatisfy(r ->
                assertThat(r.state()).isEqualTo(ToolInvocationState.SUCCESS));
        pool.shutdownNow();
    }

    /** T02（R04 链面）：fetch 未登记 id → INVALID_ARGS 终止，零证据零伪造 */
    @Test
    @DisplayName("T02：fetch 未登记 id → INVALID_ARGS，零证据")
    void fetchUnregisteredIdTerminatesWithoutEvidence() {
        MemAssets assets = new MemAssets();
        RunbookCorpusStore store = new RunbookCorpusStore(assets);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Digest corpus = new RunbookCorpusPublisher(assets).publish(List.of(
                new RunbookCorpusPublisher.DocInput("kafka-consumer-lag",
                        "Kafka 消费积压处置", "排查步骤", "步骤正文", List.of(), null, null)),
                "en07-chain", NOW);
        ToolRegistry registry = new ToolRegistry(List.of(
                reg(DirectReadToolCatalog.TOOL_RUNBOOK_FETCH,
                        new FetchRunbookExecutor(store, corpus, clock))));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ToolGateway gateway = new ToolGateway(registry,
                policyFor(DirectReadToolCatalog.TOOL_RUNBOOK_FETCH), pool,
                Clock.systemUTC(), null);
        DirectReadToolAgent fetch = agent(registry, gateway,
                DirectReadToolCatalog.TOOL_RUNBOOK_FETCH, "runbook.reference");

        assertThatThrownBy(() -> fetch.investigate(context(1),
                        Map.of("runbook_id", "ghost-doc")))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasFieldOrPropertyWithValue("reason", ToolControlReason.INVALID_ARGS);
        assertThat(evidence.rows).as("零伪造证据").isEmpty();
        assertThat(ledger.rows).hasSize(1);
        assertThat(ledger.rows.get(0).state()).isEqualTo(ToolInvocationState.FAILED);
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ 辅助

    private static ToolRegistry.Registration reg(String toolName, ToolExecutor executor) {
        ToolDefinition definition = switch (toolName) {
            case DirectReadToolCatalog.TOOL_RUNBOOK_CATALOG ->
                    DirectReadToolCatalog.runbookCatalogSearch(4_000, 65_536);
            case DirectReadToolCatalog.TOOL_RUNBOOK_FETCH ->
                    DirectReadToolCatalog.runbookFetch(4_000, 65_536);
            default -> throw new IllegalStateException(toolName);
        };
        return new ToolRegistry.Registration(definition, executor::execute);
    }

    private DirectReadToolAgent agent(ToolRegistry registry, ToolGateway gateway,
            String toolName, String evidenceType) {
        return new DirectReadToolAgent(profile(toolName),
                DirectReadToolCatalog.spec(toolName, evidenceType, "rag"),
                registry, gateway, evidence, ledger, MAPPER);
    }

    private static AgentProfile profile(String toolName) {
        Map<String, Object> schema = Map.of("type", "object");
        Map<BudgetKind, Long> budget = Map.of(BudgetKind.TOOL_CALL, 10L);
        return new AgentProfile("en07-" + toolName, "1", "prompt", "am4-native-v1",
                Set.of(toolName), budget, schema);
    }

    private static ToolPolicy policyFor(String... tools) {
        return new ToolPolicy(Set.of(tools));
    }

    private static SingleToolEvidenceAgent.CallContext context(long callSeq) {
        return new SingleToolEvidenceAgent.CallContext(RUN, TASK, ATTEMPT, callSeq, 3L,
                null, TIME_RANGE);
    }

    private static JsonNode read(String canonical) {
        try {
            return MAPPER.readTree(canonical);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 内存资产仓储（(kind,digest) 幂等语义同 Postgres 实现） */
    private static class MemAssets implements ReleaseAssetRepository {
        final Map<String, com.objwww.pr.control.release.domain.model.ReleaseAsset> rows =
                new LinkedHashMap<>();

        @Override
        public boolean insert(com.objwww.pr.control.release.domain.model.ReleaseAsset asset) {
            return rows.putIfAbsent(asset.kind() + "|" + asset.assetDigest().hex(), asset) == null;
        }

        @Override
        public Optional<com.objwww.pr.control.release.domain.model.ReleaseAsset> findByDigest(
                String kind, Digest digest) {
            return Optional.ofNullable(rows.get(kind + "|" + digest.hex()));
        }

        @Override
        public java.util.List<com.objwww.pr.control.release.domain.model.ReleaseAsset> listRecent(
                String kind, int limit) {
            return java.util.List.of();
        }
    }

    /** 调用账本内存件（En05DirectToolChainTest 同形） */
    private static class MemLedger implements RcaToolInvocationLedger {
        record Row(UUID operationId, UUID runId, UUID taskId, UUID attemptId, long callSeq,
                String toolName, String toolVersion, String actionDigest,
                ToolInvocationState state, ToolReasonCode reason) {
        }

        final List<Row> rows = new ArrayList<>();

        @Override
        public void open(InvocationIdentity identity) {
            rows.add(new Row(identity.operationId(), identity.runId(), identity.taskId(),
                    identity.attemptId(), identity.callSeq(), identity.toolName(),
                    identity.toolVersion(), identity.actionDigest(),
                    ToolInvocationState.PENDING, null));
        }

        @Override
        public boolean succeed(UUID operationId) {
            return settle(operationId, ToolInvocationState.SUCCESS, null);
        }

        @Override
        public boolean fail(UUID operationId, ToolInvocationState terminal,
                ToolReasonCode reasonCode) {
            return settle(operationId, terminal, reasonCode);
        }

        private boolean settle(UUID id, ToolInvocationState state, ToolReasonCode reason) {
            for (int k = 0; k < rows.size(); k++) {
                Row row = rows.get(k);
                if (row.operationId().equals(id) && row.state() == ToolInvocationState.PENDING) {
                    rows.set(k, new Row(row.operationId(), row.runId(), row.taskId(),
                            row.attemptId(), row.callSeq(), row.toolName(), row.toolVersion(),
                            row.actionDigest(), state, reason));
                    return true;
                }
            }
            return false;
        }
    }

    /** 证据仓储内存件 */
    private static class MemEvidence implements EvidenceRepository {
        final List<EvidenceEnvelope> rows = new ArrayList<>();

        @Override
        public void insert(EvidenceEnvelope envelope) {
            rows.add(envelope);
        }

        @Override
        public Optional<EvidenceEnvelope> findById(UUID id) {
            return rows.stream().filter(e -> e.evidenceId().equals(id)).findFirst();
        }

        @Override
        public List<EvidenceEnvelope> findByRunId(UUID runId) {
            return rows.stream().filter(e -> e.runId().equals(runId)).toList();
        }
    }
}
