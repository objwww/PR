package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.claim.ClaimReducer;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Audit-only characterization of known gaps, NOT acceptance of production behavior.
 * After fixing each gap, replace the corresponding assertion with the desired contract.
 * No live service or database is contacted.
 */
class HarnessAuditCharacterizationTest {
    private final UUID run = UUID.randomUUID();
    private final EvidenceRepository evidence = mock(EvidenceRepository.class);
    private final ClaimStore claims = mock(ClaimStore.class);
    private final RcaToolInvocationLedger ledger = mock(RcaToolInvocationLedger.class);

    private MetricsAgent metrics(String body) {
        var registry = new ToolRegistry(List.of(new ToolRegistry.Registration(
                MetricsAgent.toolDefinition(1000, 4096), e -> new byte[0])));
        var profile = new AgentProfile("metrics", "1", "audit", "1",
                Set.of(MetricsAgent.TOOL_NAME), Map.of(), Map.of("type", "object"));
        return new MetricsAgent(profile, registry, invocation ->
                new ToolGateway.ToolInvocationResult(ToolGateway.ToolInvocationResult.Kind.EXECUTED,
                        "audit", body.getBytes(StandardCharsets.UTF_8)), evidence, ledger,
                new ObjectMapper());
    }

    private SingleToolEvidenceAgent.CallContext context() {
        return new SingleToolEvidenceAgent.CallContext(run, UUID.randomUUID(), UUID.randomUUID(),
                1, 1, new com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest(
                        "ab".repeat(32)), "100/200");
    }

    @Test
    void malformedResponseIsClassifiedModelVisibleAndSettlesLedgerFailed() {
        // EX-A4a（F16）契约替换（原表征缺陷：整段无 catch → 账本 PENDING 悬挂 + 异常裸抛）：
        // 响应不可解析=模型可见族 → 账本 FAILED+归因、AgentResult FAILED、不裸抛
        var result = metrics("not-json").investigate(context(),
                new MetricsAgent.MetricsQuery("up", "100", "200", "30s"));
        assertThat(result.outcome()).isEqualTo(SingleToolEvidenceAgent.AgentOutcome.FAILED);
        assertThat(result.errorClass()).isEqualTo("REMOTE_UNAVAILABLE");
        verify(ledger).open(any());
        verify(ledger).fail(any(), eq(ToolInvocationState.FAILED), any(ToolReasonCode.class));
        verify(ledger, never()).succeed(any());
    }

    @Test
    void successfulToolProducesRawEvidenceButNoClaim() {
        var result = metrics("{\"status\":\"success\",\"data\":{\"result\":[{\"value\":1}]}}")
                .investigate(context(), new MetricsAgent.MetricsQuery("up", "100", "200", "30s"));
        var captured = org.mockito.ArgumentCaptor.forClass(EvidenceEnvelope.class);
        verify(evidence).insert(captured.capture());
        assertThat(result.outcome()).isEqualTo(SingleToolEvidenceAgent.AgentOutcome.EVIDENCE_PRODUCED);
        assertThat(captured.getValue().scope()).doesNotContainKey("claim_key");
        // EX-A4a（F16）+ EX-A3（F09）：证据落库 → checkpoint 引用随账（markResultRef）
        // → 账本 SUCCESS 后置（次序颠倒 = 崩溃窗内恢复面读不到 result_ref）
        var inOrder = inOrder(evidence, ledger);
        inOrder.verify(evidence).insert(any());
        inOrder.verify(ledger).markResultRef(any(), any());
        inOrder.verify(ledger).succeed(any());
        // EX-A4a（F05）：黑板=冻结成员——原始数据入黑板但无 claim_key 注记 → 零断言
        var snapshots = new AlertInMemoryStores.Snapshots();
        var envelope = captured.getValue();
        snapshots.freeze(new com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository
                .FrozenSnapshot(UUID.randomUUID(), run, "cd".repeat(32), 1, "cfg", "tools",
                null), List.of(new com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository
                .SnapshotMemberRow(envelope.evidenceId(), envelope.evidenceType(),
                envelope.payloadDigest())));
        when(evidence.findById(envelope.evidenceId())).thenReturn(Optional.of(envelope));
        var nativeAgent = new NativeRcaAgent(evidence, snapshots, claims,
                new ClaimReducer(Set.of(), "audit"));
        assertThat(nativeAgent.investigate(run, null,
                new com.objwww.pr.control.alert.domain.identity.EvidenceSnapshotDigest(
                        "cd".repeat(32)), 1).verdicts()).isEmpty();
    }

    @Test
    void annotatedEvidenceWithMatchingInputDigestIsAcceptedAgainstRunInputIdentity() {
        // EX-A0 契约翻转（原表征：输入身份比对输出快照 → 注记证据必被丢弃）：
        // 输入身份与 run 输入身份一致 → 采纳，Claim 绑定输出快照身份
        var row = row(Map.of("claim_key", "checkout-dependency-failure", "claim_status", "TRUE",
                "investigation_input_digest", "ab".repeat(32)));
        // EX-A4a（F05）：黑板=冻结快照成员——成员行+证据行齐备才可断言
        var snapshots = new AlertInMemoryStores.Snapshots();
        snapshots.freeze(new com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository
                .FrozenSnapshot(UUID.randomUUID(), run, "cd".repeat(32), 1, "cfg", "tools",
                null), List.of(new com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository
                .SnapshotMemberRow(row.evidenceId(), row.evidenceType(), row.payloadDigest())));
        when(evidence.findById(row.evidenceId())).thenReturn(Optional.of(row));
        var nativeAgent = new NativeRcaAgent(evidence, snapshots, claims,
                new ClaimReducer(Set.of(), "audit"));
        var verdicts = nativeAgent.investigate(run,
                new com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest(
                        "ab".repeat(32)),
                new com.objwww.pr.control.alert.domain.identity.EvidenceSnapshotDigest(
                        "cd".repeat(32)), 1).verdicts();
        assertThat(verdicts).hasSize(1);
        assertThat(verdicts.get(0).snapshotDigest()).isEqualTo("cd".repeat(32));
    }

    @Test
    void investigateWithoutFrozenSnapshotFailsExplicitly() {
        // EX-A4a（F05）契约替换（原表征"无快照绑定免成员查"随成员精确读作废）：
        // 快照行缺失 = 黑板契约 fail-closed 显式失败，不静默出空结论
        var nativeAgent = new NativeRcaAgent(evidence,
                new AlertInMemoryStores.Snapshots(),
                claims, new ClaimReducer(Set.of(), "audit"));
        assertThatThrownBy(() -> nativeAgent.investigate(run, null,
                new com.objwww.pr.control.alert.domain.identity.EvidenceSnapshotDigest(
                        "ee".repeat(32)), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("快照行缺失");
    }

    private EvidenceEnvelope row(Map<String, Object> scope) {
        var completeScope = new java.util.LinkedHashMap<String, Object>(scope);
        completeScope.put("scope", "checkout");
        completeScope.put("time_range", "100/200");
        return EvidenceEnvelope.create(UUID.randomUUID(), run, UUID.randomUUID(), "metrics.query_range",
                EvidenceEnvelope.SCHEMA_VERSION, 1, "prometheus", completeScope, null, null, Map.of("value", 1));
    }

    @Test
    void corroboratedSymptomIsTreatedAsConfirmedRootCause() {
        var reducer = new ClaimReducer(Set.of(), "audit");
        var rows = List.of(
                new com.objwww.pr.control.alert.domain.claim.Claim("checkout-error-rate-high",
                        com.objwww.pr.control.alert.domain.claim.ClaimStatus.TRUE,
                        "symptom only", "checkout", "100/200", 1, List.of("e1"), "metrics", "snapshot"),
                new com.objwww.pr.control.alert.domain.claim.Claim("checkout-error-rate-high",
                        com.objwww.pr.control.alert.domain.claim.ClaimStatus.TRUE,
                        "symptom only", "checkout", "100/200", 1, List.of("e2"), "logs", "snapshot"));
        var report = com.objwww.pr.control.alert.domain.claim.ReportAssembler.assemble(
                "snapshot", reducer.reduce(rows));
        assertThat(report.hasConfirmedRootCause()).isTrue();
        assertThat(com.objwww.pr.control.alert.application.NativeReportAdapter.adapt(report).analysisJson())
                .contains("checkout-error-rate-high");
    }
}
