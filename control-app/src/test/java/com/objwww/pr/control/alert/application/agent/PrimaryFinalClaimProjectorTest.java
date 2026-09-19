package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.claim.ClaimIdentity;
import com.objwww.pr.control.alert.domain.claim.ClaimProjection;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主 FINAL 投影 v2 UT（A0 补充方案 §2 第 5/6 条/AS-06/08/11）：投影消费准入判定
 * ——kind 不再直推 TRUE、来源数量不再机械推 MULTI_SOURCE_CONSISTENT；CONTEXT 引用
 * 来源不计入支持面；SUPPORTS+REFUTES 同行=自相矛盾；组间冲突保留呈堂。
 */
class PrimaryFinalClaimProjectorTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");

    /** 捕获 append 判定的最小 ClaimStore */
    private static final class CapturingClaims implements ClaimStore {
        final List<ClaimVerdict> appended = new ArrayList<>();

        @Override
        public ClaimAppendResult append(UUID runId, ClaimVerdict verdict) {
            appended.add(verdict);
            return new ClaimAppendResult(ClaimProjection.Outcome.CREATED,
                    "fp", "hash", null, 0, 1);
        }

        @Override
        public long markUnresolved(UUID runId, ClaimIdentity identity,
                String policyVersion) {
            return 1;
        }

        @Override
        public List<ClaimRow> findByRunId(UUID runId) {
            return List.of();
        }
    }

    private static final class FakeEvidence implements EvidenceRepository {
        final Map<UUID, EvidenceEnvelope> rows = new LinkedHashMap<>();

        @Override
        public void insert(EvidenceEnvelope envelope) {
            rows.put(envelope.evidenceId(), envelope);
        }

        @Override
        public Optional<EvidenceEnvelope> findById(UUID evidenceId) {
            return Optional.ofNullable(rows.get(evidenceId));
        }

        @Override
        public List<EvidenceEnvelope> findByRunId(UUID runId) {
            return List.copyOf(rows.values());
        }
    }

    private static EvidenceEnvelope envelope(UUID id, String source) {
        return new EvidenceEnvelope(id, RUN, UUID.randomUUID(), "logs.aggregate",
                "am4-evidence.v1", 1, source, Map.of(), null, null,
                "{\"status\":\"success\"}", "0".repeat(64));
    }

    private static Map<String, Object> row(String kind, String statement,
            List<String> refs, List<Map<String, Object>> roles) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("claim_key", "c1");
        row.put("kind", kind);
        row.put("statement", statement);
        row.put("evidence_refs", refs);
        if (roles != null) {
            row.put("evidence_roles", roles);
        }
        row.put("admission_note", "");
        return row;
    }

    private static Map<String, Object> role(String ref, String role) {
        return Map.of("ref", ref, "role", role);
    }

    private static PrimaryCheckpoint checkpoint(List<Map<String, Object>> claims) {
        return new PrimaryCheckpoint(UUID.randomUUID(), RUN, 0,
                PrimaryCheckpoint.Phase.PRIMARY_READY, 2, 2, 0, "digest", null, null,
                claims, List.of(), null, NOW);
    }

    @Test
    @DisplayName("AS-06：v1 旧行（无 evidence_roles）→ ROOT_CAUSE 投 UNKNOWN，不冒认一致")
    void legacyRowWithoutRolesProjectsUnknownNotTrue() {
        UUID loki = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(loki, "loki"));
        CapturingClaims claims = new CapturingClaims();
        PrimaryFinalClaimProjector projector =
                new PrimaryFinalClaimProjector(claims, evidence);

        int n = projector.project(RUN, checkpoint(List.of(
                row("ROOT_CAUSE", "支付失败", List.of(loki.toString()), null))),
                "snap", 1, "tr");

        assertThat(n).isEqualTo(1);
        ClaimVerdict v = claims.appended.get(0);
        assertThat(v.status().name()).isEqualTo("UNKNOWN");
        assertThat(v.evidenceBasis().name())
                .as("未确认支持不冒认 MULTI_SOURCE_CONSISTENT")
                .isEqualTo("SINGLE_SOURCE");
        assertThat(v.policyVersion())
                .isEqualTo(PrimaryFinalClaimProjector.POLICY_VERSION);
        assertThat(v.policyVersion()).isEqualTo("r7-primary-v2");
    }

    @Test
    @DisplayName("两个不同 source 的 SUPPORTS → TRUE+MULTI_SOURCE_CONSISTENT（真双源支持）")
    void twoIndependentSupportSourcesConfirm() {
        UUID loki = UUID.randomUUID();
        UUID prom = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(loki, "loki"));
        evidence.insert(envelope(prom, "prometheus"));
        CapturingClaims claims = new CapturingClaims();
        PrimaryFinalClaimProjector projector =
                new PrimaryFinalClaimProjector(claims, evidence);

        projector.project(RUN, checkpoint(List.of(
                row("ROOT_CAUSE", "下游调用失败", List.of(loki.toString(), prom.toString()),
                        List.of(role(loki.toString(), "SUPPORTS"),
                                role(prom.toString(), "SUPPORTS"))))),
                "snap", 1, "tr");

        ClaimVerdict v = claims.appended.get(0);
        assertThat(v.status().name()).isEqualTo("TRUE");
        assertThat(v.evidenceBasis().name()).isEqualTo("MULTI_SOURCE_CONSISTENT");
        assertThat(v.sources()).containsExactlyInAnyOrder("loki", "prometheus");
    }

    @Test
    @DisplayName("AS-06：SUPPORTS(loki)+CONTEXT(prometheus) → TRUE 但 SINGLE_SOURCE——背景不算第二支持")
    void contextSourceDoesNotFabricateMultiSourceConsistency() {
        UUID loki = UUID.randomUUID();
        UUID prom = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(loki, "loki"));
        evidence.insert(envelope(prom, "prometheus"));
        CapturingClaims claims = new CapturingClaims();
        PrimaryFinalClaimProjector projector =
                new PrimaryFinalClaimProjector(claims, evidence);

        projector.project(RUN, checkpoint(List.of(
                row("ROOT_CAUSE", "失败+无关背景", List.of(loki.toString(), prom.toString()),
                        List.of(role(loki.toString(), "SUPPORTS"),
                                role(prom.toString(), "CONTEXT"))))),
                "snap", 1, "tr");

        ClaimVerdict v = claims.appended.get(0);
        assertThat(v.status().name()).isEqualTo("TRUE");
        assertThat(v.evidenceBasis().name()).isEqualTo("SINGLE_SOURCE");
        assertThat(v.sources()).as("CONTEXT 来源不进支持来源集").containsExactly("loki");
    }

    @Test
    @DisplayName("AS-08：同一底层信号两工具（同 source）声明双 SUPPORTS → 归并不伪造双源")
    void sameUnderlyingSignalMergesNotCountsTwice() {
        UUID row1 = UUID.randomUUID();
        UUID row2 = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(row1, "loki"));
        evidence.insert(envelope(row2, "loki")); // logs.query 与 logs.aggregate 同源
        CapturingClaims claims = new CapturingClaims();
        PrimaryFinalClaimProjector projector =
                new PrimaryFinalClaimProjector(claims, evidence);

        projector.project(RUN, checkpoint(List.of(
                row("ROOT_CAUSE", "同一 Loki 信号两行", List.of(row1.toString(), row2.toString()),
                        List.of(role(row1.toString(), "SUPPORTS"),
                                role(row2.toString(), "SUPPORTS"))))),
                "snap", 1, "tr");

        ClaimVerdict v = claims.appended.get(0);
        assertThat(v.status().name()).isEqualTo("TRUE");
        assertThat(v.evidenceBasis().name())
                .as("同 source 归并：两工具读取≠两独立来源")
                .isEqualTo("SINGLE_SOURCE");
    }

    @Test
    @DisplayName("同行并存 SUPPORTS+REFUTES → 自相矛盾 UNKNOWN")
    void supportAndRefutesInSameRowContradict() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(a, "loki"));
        evidence.insert(envelope(b, "prometheus"));
        CapturingClaims claims = new CapturingClaims();
        PrimaryFinalClaimProjector projector =
                new PrimaryFinalClaimProjector(claims, evidence);

        projector.project(RUN, checkpoint(List.of(
                row("ROOT_CAUSE", "既支持又反驳", List.of(a.toString(), b.toString()),
                        List.of(role(a.toString(), "SUPPORTS"),
                                role(b.toString(), "REFUTES"))))),
                "snap", 1, "tr");

        ClaimVerdict v = claims.appended.get(0);
        assertThat(v.status().name()).isEqualTo("UNKNOWN");
        assertThat(v.evidenceBasis().name()).isEqualTo("SINGLE_SOURCE");
    }

    @Test
    @DisplayName("AS-11：同 claimKey 组间状态冲突 → UNKNOWN+MULTI_SOURCE_CONFLICT 呈堂保留")
    void groupConflictPreservedAsConfrontation() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(a, "loki"));
        evidence.insert(envelope(b, "prometheus"));
        CapturingClaims claims = new CapturingClaims();
        PrimaryFinalClaimProjector projector =
                new PrimaryFinalClaimProjector(claims, evidence);

        projector.project(RUN, checkpoint(List.of(
                row("ROOT_CAUSE", "主张根因", List.of(a.toString()),
                        List.of(role(a.toString(), "SUPPORTS"))),
                row("EXCLUSION", "排除该根因", List.of(b.toString()),
                        List.of(role(b.toString(), "REFUTES"))))),
                "snap", 1, "tr");

        assertThat(claims.appended).as("同组恰一行呈堂").hasSize(1);
        ClaimVerdict v = claims.appended.get(0);
        assertThat(v.status().name()).isEqualTo("UNKNOWN");
        assertThat(v.evidenceBasis().name()).isEqualTo("MULTI_SOURCE_CONFLICT");
    }

    @Test
    @DisplayName("EXCLUSION+REFUTES → FALSE；支持来源取 REFUTES 方向引用")
    void exclusionWithRefutesProjectsFalse() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(a, "loki"));
        evidence.insert(envelope(b, "change_event"));
        CapturingClaims claims = new CapturingClaims();
        PrimaryFinalClaimProjector projector =
                new PrimaryFinalClaimProjector(claims, evidence);

        projector.project(RUN, checkpoint(List.of(
                row("EXCLUSION", "回滚后告警消失", List.of(a.toString(), b.toString()),
                        List.of(role(a.toString(), "CONTEXT"),
                                role(b.toString(), "REFUTES"))))),
                "snap", 1, "tr");

        ClaimVerdict v = claims.appended.get(0);
        assertThat(v.status().name()).isEqualTo("FALSE");
        assertThat(v.sources()).as("FALSE 的方向来源=REFUTES 引用")
                .containsExactly("change_event");
    }

    // ---------------------------------------------- 结构化根因三元组透传（V147）

    @Test
    @DisplayName("提案行携带 root_cause 三元组 → 投影进 ClaimVerdict.rootCause")
    void rootCauseTripleProjected() {
        UUID loki = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(loki, "loki"));
        CapturingClaims claims = new CapturingClaims();
        PrimaryFinalClaimProjector projector =
                new PrimaryFinalClaimProjector(claims, evidence);

        Map<String, Object> row = row("ROOT_CAUSE", "payment 扣款按比例失败",
                List.of(loki.toString()), List.of(role(loki.toString(), "SUPPORTS")));
        row.put("root_cause", Map.of("component", "payment",
                "fault_type", "BUSINESS_ERROR_RATE",
                "reason_code", "PAYMENT_CHARGE_FAILURE"));

        projector.project(RUN, checkpoint(List.of(row)), "snap", 1, "tr");

        ClaimVerdict v = claims.appended.get(0);
        assertThat(v.rootCause()).isNotNull();
        assertThat(v.rootCause().component()).isEqualTo("payment");
        assertThat(v.rootCause().faultType()).isEqualTo("BUSINESS_ERROR_RATE");
        assertThat(v.rootCause().reasonCode()).isEqualTo("PAYMENT_CHARGE_FAILURE");
    }

    @Test
    @DisplayName("无 root_cause 键/形状非法 → rootCause=null 诚实降级（不冒充评分面）")
    void missingOrMalformedTripleDegradesToNull() {
        UUID loki = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(loki, "loki"));
        CapturingClaims claims = new CapturingClaims();
        PrimaryFinalClaimProjector projector =
                new PrimaryFinalClaimProjector(claims, evidence);

        // 无 root_cause 键（v1 旧行）
        projector.project(RUN, checkpoint(List.of(
                row("ROOT_CAUSE", "无三元组旧行", List.of(loki.toString()),
                        List.of(role(loki.toString(), "SUPPORTS"))))),
                "snap", 1, "tr");
        assertThat(claims.appended.get(0).rootCause()).isNull();

        // 形状非法（缺字段）→ null 诚实降级，不抛错打断整案投影
        Map<String, Object> malformed = row("ROOT_CAUSE", "缺字段三元组",
                List.of(loki.toString()), List.of(role(loki.toString(), "SUPPORTS")));
        malformed.put("root_cause", Map.of("component", "payment"));
        projector.project(RUN, checkpoint(List.of(malformed)), "snap", 2, "tr");
        assertThat(claims.appended.get(1).rootCause()).isNull();
    }

    // ---------------------------------------------- 症状码透传（V151）

    @Test
    @DisplayName("提案行携带 symptom_codes → 投影进 ClaimVerdict.symptomCodes；缺席 → null 未声明")
    void symptomCodesProjected() {
        UUID loki = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(loki, "loki"));
        CapturingClaims claims = new CapturingClaims();
        PrimaryFinalClaimProjector projector =
                new PrimaryFinalClaimProjector(claims, evidence);

        Map<String, Object> row = row("SYMPTOM", "ArenaDuplicateOrders firing",
                List.of(loki.toString()), List.of(role(loki.toString(), "SUPPORTS")));
        row.put("symptom_codes", List.of("ArenaDuplicateOrders"));

        projector.project(RUN, checkpoint(List.of(row)), "snap", 1, "tr");
        assertThat(claims.appended.get(0).symptomCodes())
                .containsExactly("ArenaDuplicateOrders");

        // 无 symptom_codes 键（旧行）→ null 未声明（报告面诚实空数组）
        projector.project(RUN, checkpoint(List.of(
                row("SYMPTOM", "无症状码旧行", List.of(loki.toString()),
                        List.of(role(loki.toString(), "SUPPORTS"))))),
                "snap", 2, "tr");
        assertThat(claims.appended.get(1).symptomCodes()).isNull();
    }
}
