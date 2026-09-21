package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.agent.AgentPhase;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.agent.ContextSummary;
import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.agent.RoleRuntimeKind;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.TaskExecutionBinding;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JE-01：装配器选材应用测试——选材在 EVIDENCE_LIMIT 截断前的全池上做（JEV-01
 * 早期反证回窗）、SHADOW 不换输入、替换消费存根、无选材零漂移。
 */
class JevSelectionAssemblyTest {

    private static final Instant NOW = Instant.parse("2026-09-11T09:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final UUID runId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final ContextAssemblerTest.MemEvidence evidence = new ContextAssemblerTest.MemEvidence();
    private final ContextAssemblerTest.MemDelegations delegations = new ContextAssemblerTest.MemDelegations();
    private final ContextAssemblerTest.MemToolLedger toolLedger = new ContextAssemblerTest.MemToolLedger();

    private ContextAssembler assembler(ContextAssembler.AlertMaterial material) {
        return new ContextAssembler(evidence, toolLedger, delegations,
                run -> material, MAPPER);
    }

    private ContextAssembler assemblerWithSummary(
            ContextAssembler.AlertMaterial material, boolean replaceOmitted,
            ContextSummary summary) {
        return new ContextAssembler(evidence, toolLedger, delegations,
                run -> material, null, null, null, null,
                summaryId -> java.util.Optional.ofNullable(summary), null,
                replaceOmitted, CLOCK, MAPPER);
    }

    private RoleRunner.RoleDriveRequest request() {
        RcaTask task = new RcaTask(taskId, runId, RcaTask.PRIMARY_INVESTIGATE,
                RcaTaskState.READY, 5, NOW, NOW, Instant.MAX, null, null, 0, 0, 2,
                NOW, NOW, 0);
        TaskExecutionBinding binding = new TaskExecutionBinding(taskId, runId, 0,
                RcaTask.PRIMARY_INVESTIGATE, "primary", "1",
                Digest.sha256Of("primary").hex(), null, null, List.of(),
                Map.of("type", "object"), null, true,
                TaskExecutionBinding.FailurePolicy.DEAD_ON_FAILURE, NOW);
        AgentProfile profile = new AgentProfile("primary", "1", "prompt", "pv",
                Set.of("logs.query"), Map.of(BudgetKind.STEP, 8L),
                Map.of("type", "object"), Map.of(), AgentPhase.PRIMARY,
                RoleRuntimeKind.BOUNDED_LLM, Set.of(), 8, "single-pass");
        return new RoleRunner.RoleDriveRequest(task, binding, profile,
                new com.objwww.pr.control.alert.application.agent
                        .SingleToolEvidenceAgent.CallContext(runId, taskId,
                        UUID.randomUUID(), 0, 0, null, "1757574000/1757577600"),
                "1757574000", "1757577600");
    }

    private PrimaryCheckpoint checkpoint() {
        return PrimaryCheckpoint.initial(taskId, runId, 0, NOW);
    }

    /** 25 条证据：i 越小越新；最旧 5 条在默认 20 条窗口外 */
    private void seedPool(int count) {
        for (int i = 0; i < count; i++) {
            evidence.rows.add(EvidenceEnvelope.create(UUID.randomUUID(), runId,
                    taskId, "logs.query", EvidenceEnvelope.SCHEMA_VERSION, 0,
                    "loki", Map.of(), NOW.minusSeconds(3600 - i),
                    NOW.minusSeconds(3500 - i),
                    Map.of("data", Map.of("result", List.of(Map.of(
                            "ts", "2026-09-11T08:59:00Z", "service", "checkout",
                            "line", "COUNTER-EVIDENCE marker row-" + i))))));
        }
    }

    private ContextAssembler.EvidenceSnapshot snapshot() {
        return assembler(ContextAssembler.AlertMaterial.unknown())
                .evidenceSnapshot(runId);
    }

    private static JsonNode envelopeOf(String prompt) throws Exception {
        int start = prompt.indexOf('\n') + 1;
        int end = prompt.lastIndexOf('}') + 1;
        return MAPPER.readTree(prompt.substring(start, end));
    }

    @Test
    void 选材应用_窗口外早期证据被选回_池序保持() throws Exception {
        seedPool(25);
        ContextAssembler.EvidenceSnapshot snap = snapshot();
        String oldest = snap.rows().get(24).evidenceId().toString();
        String newest = snap.rows().get(0).evidenceId().toString();
        Set<String> pinned = new LinkedHashSet<>(Set.of(oldest, newest));
        ContextAssembler.EvidenceSelection selection =
                new ContextAssembler.EvidenceSelection(pinned, true, "jev:select");

        ContextAssembler.Assembly assembly = assembler(
                ContextAssembler.AlertMaterial.unknown())
                .assemble(request(), checkpoint(), 2, snapshot(),
                        ContextAssembler.AlertMaterial.unknown(), selection);

        assertThat(assembly.includedRefs()).containsExactlyInAnyOrder(oldest, newest);
        assertThat(assembly.includedRefs().size()).isLessThanOrEqualTo(20);
        assertThat(assembly.omittedRefs()).hasSize(23).containsAnyOf(
                snap.rows().get(1).evidenceId().toString(),
                snap.rows().get(23).evidenceId().toString());
        // 选回的早期反证载荷入模（JEV-01 的核心收益面）
        assertThat(assembly.prompt()).contains("marker row-24");
    }

    @Test
    void SHADOW_不应用选材_窗口照旧() throws Exception {
        seedPool(25);
        ContextAssembler.EvidenceSnapshot snap = snapshot();
        // snapshot 为 timeEnd 降序：get(24) = 最旧（默认 20 条窗口外，seed 行 i=0）
        String oldest = snap.rows().get(24).evidenceId().toString();
        ContextAssembler.EvidenceSelection selection =
                new ContextAssembler.EvidenceSelection(Set.of(oldest), false,
                        "jev:shadow");

        ContextAssembler.Assembly assembly = assembler(
                ContextAssembler.AlertMaterial.unknown())
                .assemble(request(), checkpoint(), 2, snapshot(),
                        ContextAssembler.AlertMaterial.unknown(), selection);

        assertThat(assembly.includedRefs()).doesNotContain(oldest);
        assertThat(assembly.includedRefs()).hasSize(20);
        assertThat(assembly.prompt()).doesNotContain("marker row-0");
    }

    @Test
    void 无选材_新旧装配路径零漂移() throws Exception {
        seedPool(3);
        ContextAssembler.AlertMaterial material =
                new ContextAssembler.AlertMaterial("A", "svc", "P1", "s");
        ContextAssembler assembler = assembler(material);
        ContextAssembler.Assembly baseline = assembler.assemble(request(),
                checkpoint(), 2);
        ContextAssembler.Assembly throughSnapshot = assembler.assemble(request(),
                checkpoint(), 2, assembler.evidenceSnapshot(runId), material, null);
        assertThat(baseline.prompt()).isEqualTo(throughSnapshot.prompt());
        assertThat(baseline.snapshotDigest())
                .isEqualTo(throughSnapshot.snapshotDigest());
    }

    @Test
    void 替换消费_被摘要省略的证据渲染存根() throws Exception {
        seedPool(2);
        ContextAssembler.EvidenceSnapshot snap = snapshot();
        String omittedRef = snap.rows().get(1).evidenceId().toString();
        ContextSummary summary = ContextSummary.of(UUID.randomUUID(), runId,
                taskId, ContextCompactionService.SCHEMA_VERSION,
                Digest.sha256Of("source").value(), 0, 3,
                Digest.sha256Of("prompt").value(), "test-model", 100, 40,
                List.of(), List.of(omittedRef),
                "已验证摘要文本", "REFS_VALIDATED", "llm:test", null, NOW);
        ContextAssembler assembler = assemblerWithSummary(
                ContextAssembler.AlertMaterial.unknown(), true, summary);
        // CL-08 消费指针：检查点钉上 current_summary_id 后摘要行才会进入装配
        PrimaryCheckpoint checkpoint = checkpoint()
                .withSummaryConsumed(summary.id(), NOW);

        ContextAssembler.Assembly assembly = assembler.assemble(request(),
                checkpoint, 2, snapshot(),
                ContextAssembler.AlertMaterial.unknown(), null);
        JsonNode envelope = envelopeOf(assembly.prompt());
        JsonNode evidenceRows = envelope.get("evidence");
        JsonNode stub = null;
        for (JsonNode row : evidenceRows) {
            if (row.get("ref").asText().equals(omittedRef)) {
                stub = row;
            }
        }
        assertThat(stub).as("被摘要省略的证据 = 存根呈现").isNotNull();
        assertThat(stub.get("summarized").asBoolean()).isTrue();
        assertThat(stub.has("observations")).isFalse();
        // 摘要槽在信封，note 标明替换消费语义
        assertThat(envelope.get("validated_summary").get("summary_id").asText())
                .isEqualTo(summary.id().toString());
        assertThat(envelope.get("validated_summary").get("note").asText())
                .contains("JE-01");
        // 关闭替换 = 同一快照渲染全载荷（附加注入语义零漂移）
        ContextAssembler additive = assemblerWithSummary(
                ContextAssembler.AlertMaterial.unknown(), false, summary);
        JsonNode additiveEnvelope = envelopeOf(additive.assemble(request(),
                checkpoint, 2, snapshot(),
                ContextAssembler.AlertMaterial.unknown(), null).prompt());
        boolean fullPayload = false;
        for (JsonNode row : additiveEnvelope.get("evidence")) {
            if (row.get("ref").asText().equals(omittedRef)
                    && row.has("observations")) {
                fullPayload = true;
            }
        }
        assertThat(fullPayload).as("关闭替换消费 = 原载荷照常入模").isTrue();
    }
}
