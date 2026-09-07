package com.objwww.pr.control.alert.application.holmes;

import com.objwww.pr.control.alert.domain.claim.Claim;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holmes Baseline Adapter 单测（AM4 M4-31，TDD 先行）：Holmes 报告包转成与 Native
 * 路径相同的 Evidence/Claim 边界（M4-34 Shadow 对比的输入契约）；Adapter 失败可见
 * （FAILED 结局带原因，禁静默吞）。
 */
class HolmesEvidenceAdapterTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID TASK = UUID.randomUUID();
    private static final String SNAPSHOT = "cd".repeat(32);
    private static final String PACKAGE = """
            {
              "schema_version": 2,
              "summary": "db-primary CPU 异常与凌晨变更时间吻合",
              "root_cause": {"component": "db-primary", "fault_type": "resource_saturation",
                             "reason_code": "CPU_SATURATED"},
              "claims": [
                {"claim_type": "db.cpu_anomaly", "status": "TRUE",
                 "component": "db-primary", "fault_type": "resource_saturation",
                 "symptom_codes": ["CPU>90"], "evidence_refs": ["metrics:A1"]},
                {"claim_type": "db.recent_change", "status": "UNKNOWN",
                 "component": "db-primary", "fault_type": "change_correlation",
                 "symptom_codes": [], "evidence_refs": []}
              ]
            }
            """;

    private final HolmesEvidenceAdapter adapter = new HolmesEvidenceAdapter();

    @Test
    void holmesClaimsMapToAm4ClaimBoundary() {
        HolmesEvidenceAdapter.AdaptResult result = adapter.adapt(RUN, TASK, 3L, SNAPSHOT,
                "am4-evidence.v1", PACKAGE);

        assertThat(result.outcome()).isEqualTo(HolmesEvidenceAdapter.AdaptOutcome.ADAPTED);
        assertThat(result.claims()).hasSize(2);
        Claim first = result.claims().get(0);
        assertThat(first.claimKey()).isEqualTo("db.cpu_anomaly");
        assertThat(first.status()).isEqualTo(ClaimStatus.TRUE);
        assertThat(first.scope()).isEqualTo("db-primary");
        assertThat(first.source()).isEqualTo("holmes");
        assertThat(first.snapshotDigest()).isEqualTo(SNAPSHOT);
        assertThat(first.observedGeneration()).isEqualTo(3L);
        assertThat(first.evidenceRefs()).containsExactly("metrics:A1");
    }

    @Test
    void emptyEvidenceRefsBridgeToPackageEnvelope() {
        HolmesEvidenceAdapter.AdaptResult result = adapter.adapt(RUN, TASK, 3L, SNAPSHOT,
                "am4-evidence.v1", PACKAGE);

        // 因果型断言可不挂证据——桥接到本包证据信封（Claim 契约禁空 refs）
        assertThat(result.claims().get(1).evidenceRefs())
                .containsExactly(result.evidence().evidenceId().toString());
    }

    @Test
    void packageItselfBecomesEvidenceEnvelope() {
        HolmesEvidenceAdapter.AdaptResult result = adapter.adapt(RUN, TASK, 3L, SNAPSHOT,
                "am4-evidence.v1", PACKAGE);

        EvidenceEnvelope envelope = EvidenceEnvelope.verify(result.evidence());
        assertThat(envelope.runId()).isEqualTo(RUN);
        assertThat(envelope.evidenceType()).isEqualTo("holmes.report");
        assertThat(envelope.source()).isEqualTo("holmes");
        assertThat(envelope.observedGeneration()).isEqualTo(3L);
        assertThat(envelope.canonicalPayload()).contains("db.cpu_anomaly");
    }

    @Test
    void packageWithoutClaimsIsHonestNoClaims() {
        String empty = "{\"schema_version\":2,\"summary\":\"nothing\",\"claims\":[]}";

        HolmesEvidenceAdapter.AdaptResult result = adapter.adapt(RUN, TASK, 3L, SNAPSHOT,
                "am4-evidence.v1", empty);

        assertThat(result.outcome()).isEqualTo(HolmesEvidenceAdapter.AdaptOutcome.NO_CLAIMS);
        assertThat(result.claims()).isEmpty();
    }

    @Test
    void malformedPackageFailsVisibly() {
        HolmesEvidenceAdapter.AdaptResult broken = adapter.adapt(RUN, TASK, 3L, SNAPSHOT,
                "am4-evidence.v1", "{not-json");
        assertThat(broken.outcome()).isEqualTo(HolmesEvidenceAdapter.AdaptOutcome.FAILED);
        assertThat(broken.failReason()).isNotBlank();

        HolmesEvidenceAdapter.AdaptResult missingClaims = adapter.adapt(RUN, TASK, 3L,
                SNAPSHOT, "am4-evidence.v1", "{\"schema_version\":2,\"summary\":\"x\"}");
        assertThat(missingClaims.outcome()).isEqualTo(HolmesEvidenceAdapter.AdaptOutcome.FAILED);

        HolmesEvidenceAdapter.AdaptResult badStatus = adapter.adapt(RUN, TASK, 3L, SNAPSHOT,
                "am4-evidence.v1",
                "{\"claims\":[{\"claim_type\":\"k\",\"status\":\"MAYBE\",\"component\":\"c\","
                        + "\"fault_type\":\"f\",\"symptom_codes\":[],\"evidence_refs\":[]}]}");
        assertThat(badStatus.outcome()).isEqualTo(HolmesEvidenceAdapter.AdaptOutcome.FAILED);
    }
}
