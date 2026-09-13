package com.objwww.pr.control.infrastructure.nativeexec;

import com.objwww.pr.control.alert.application.agent.PrimaryFinalClaimProjector;
import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.claim.EvidenceBasis;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MC22/P0-1 对峙呈堂（投影面，A0 补充方案 §2 升版 v2）：同 claimKey TRUE/FALSE
 * 双断言不再 REVISED 覆盖，强制 UNKNOWN+MULTI_SOURCE_CONFLICT 单行呈堂（与
 * ClaimReducer 无法裁决分支同语义）；同态多行合并引用；零引用跳过不变。
 * v2 差异：状态判定消费 evidence_roles 准入判定（行断言须带 SUPPORTS/REFUTES
 * 才达 TRUE/FALSE）；冲突行来源集不再冒认并集（引用并集保留在 evidence_refs）。
 */
class PrimaryFinalClaimProjectorConflictTest {

    private static final Instant NOW = Instant.parse("2026-09-12T13:00:00Z");
    private static final UUID RUN = UUID.randomUUID();
    private static final int GENERATION = 1;
    private static final String TIME_RANGE = "2026-09-10T00:00:00Z/2026-09-10T00:05:00Z";

    private final NativeInvestigationExecutorTest.TestClaims claims =
            new NativeInvestigationExecutorTest.TestClaims();
    private final NativeInvestigationExecutorTest.TestEvidence evidence =
            new NativeInvestigationExecutorTest.TestEvidence();
    private PrimaryFinalClaimProjector projector;

    @BeforeEach
    void wire() {
        projector = new PrimaryFinalClaimProjector(claims, evidence);
    }

    @Test
    @DisplayName("MC22：同 claimKey TRUE/FALSE 双断言 → 单行 UNKNOWN+CONFLICT，全组并集呈堂")
    void conflictSameClaimKeyForcedUnknownWithUnionEvidence() {
        UUID e1 = seedEvidence("prometheus");
        UUID e2 = seedEvidence("loki");
        PrimaryCheckpoint checkpoint = checkpointWith(List.of(
                row("c1", "ROOT_CAUSE", "X 是根因", List.of(e1),
                        List.of(role(e1, "SUPPORTS")), null),
                row("c1", "EXCLUSION", "已排除 X", List.of(e2),
                        List.of(role(e2, "REFUTES")), "note-b")));

        int appended = projector.project(RUN, checkpoint,
                Digest.sha256Of("snap").hex(), GENERATION, TIME_RANGE);

        assertThat(appended).as("对峙组恰一行——反证不被 REVISED 覆盖").isEqualTo(1);
        var claim = claims.appended.get(0);
        assertThat(claim.status()).isEqualTo(ClaimStatus.UNKNOWN);
        assertThat(claim.evidenceBasis()).isEqualTo(EvidenceBasis.MULTI_SOURCE_CONFLICT);
        // ClaimVerdict 构造即对 evidenceRefs 排序去重（内容哈希可复现）——并集无序断言
        assertThat(claim.evidenceRefs())
                .containsExactlyInAnyOrder(e1.toString(), e2.toString());
        assertThat(claim.sources()).as("v2：冲突行来源不冒认（引用并集在 refs 面）").isEmpty();
        assertThat(claim.reason()).as("双方陈述同场呈堂（审计完整）")
                .contains("对峙呈堂").contains("X 是根因").contains("已排除 X");
    }

    @Test
    @DisplayName("同 claimKey 同态重复提交：合并引用单行投影（同族 REVISED 漂移同堵）")
    void unanimousDuplicateMergesRefsIntoOneRow() {
        UUID e1 = seedEvidence("prometheus");
        UUID e2 = seedEvidence("loki");
        PrimaryCheckpoint checkpoint = checkpointWith(List.of(
                row("c1", "ROOT_CAUSE", "双源同述", List.of(e1),
                        List.of(role(e1, "SUPPORTS")), null),
                row("c1", "ROOT_CAUSE", "双源同述", List.of(e2),
                        List.of(role(e2, "SUPPORTS")), null)));

        int appended = projector.project(RUN, checkpoint,
                Digest.sha256Of("snap").hex(), GENERATION, TIME_RANGE);

        assertThat(appended).isEqualTo(1);
        var claim = claims.appended.get(0);
        assertThat(claim.status()).isEqualTo(ClaimStatus.TRUE);
        assertThat(claim.evidenceBasis()).isEqualTo(EvidenceBasis.MULTI_SOURCE_CONSISTENT);
        assertThat(claim.evidenceRefs())
                .containsExactlyInAnyOrder(e1.toString(), e2.toString());
    }

    @Test
    @DisplayName("单行 v2 判定：ROOT_CAUSE+SUPPORTS→TRUE/EXCLUSION+REFUTES→FALSE；不同键互不并组")
    void singleRowMappingByRoles() {
        UUID e1 = seedEvidence("prometheus");
        UUID e2 = seedEvidence("loki");
        PrimaryCheckpoint checkpoint = checkpointWith(List.of(
                row("c1", "ROOT_CAUSE", "双源", List.of(e1, e2),
                        List.of(role(e1, "SUPPORTS"), role(e2, "SUPPORTS")), null),
                row("c2", "EXCLUSION", "已排除", List.of(e1),
                        List.of(role(e1, "REFUTES")), null)));

        int appended = projector.project(RUN, checkpoint,
                Digest.sha256Of("snap").hex(), GENERATION, TIME_RANGE);

        assertThat(appended).isEqualTo(2);
        assertThat(claims.appended.get(0).status()).isEqualTo(ClaimStatus.TRUE);
        assertThat(claims.appended.get(0).evidenceBasis())
                .isEqualTo(EvidenceBasis.MULTI_SOURCE_CONSISTENT);
        assertThat(claims.appended.get(1).status()).isEqualTo(ClaimStatus.FALSE);
        assertThat(claims.appended.get(1).evidenceBasis())
                .isEqualTo(EvidenceBasis.SINGLE_SOURCE);
    }

    @Test
    @DisplayName("零引用跳过不变（无证据不成断言，留检查点审计面）")
    void zeroRefRowsStillSkipped() {
        UUID e1 = seedEvidence("prometheus");
        PrimaryCheckpoint checkpoint = checkpointWith(List.of(
                Map.of("claim_key", "c1", "kind", "ROOT_CAUSE",
                        "statement", "零引用", "evidence_refs", List.of()),
                Map.of("claim_key", "c2", "kind", "ROOT_CAUSE",
                        "statement", "有引用", "evidence_refs", List.of(e1))));

        int appended = projector.project(RUN, checkpoint,
                Digest.sha256Of("snap").hex(), GENERATION, TIME_RANGE);

        assertThat(appended).isEqualTo(1);
        assertThat(claims.appended.get(0).claimKey()).isEqualTo("c2");
    }

    // ------------------------------------------------------------------ 夹具

    private static Map<String, Object> role(UUID ref, String roleName) {
        return Map.of("ref", ref.toString(), "role", roleName);
    }

    private static Map<String, Object> row(String claimKey, String kind, String statement,
            List<UUID> refs, List<Map<String, Object>> roles, String admissionNote) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("claim_key", claimKey);
        row.put("kind", kind);
        row.put("statement", statement);
        row.put("evidence_refs", List.copyOf(refs));
        row.put("evidence_roles", roles);
        if (admissionNote != null) {
            row.put("admission_note", admissionNote);
        }
        return row;
    }

    private PrimaryCheckpoint checkpointWith(List<Map<String, Object>> rows) {
        return new PrimaryCheckpoint(UUID.randomUUID(), UUID.randomUUID(), 0,
                PrimaryCheckpoint.Phase.PRIMARY_READY, 3, 2, 0, null, null, null,
                rows, List.of(), null, NOW);
    }

    private UUID seedEvidence(String source) {
        UUID id = UUID.randomUUID();
        evidence.rows.add(EvidenceEnvelope.create(id, RUN, UUID.randomUUID(),
                "logs.aggregate", EvidenceEnvelope.SCHEMA_VERSION, 0, source,
                Map.of(), NOW.minusSeconds(60), NOW, Map.of("k", "v")));
        return id;
    }
}
