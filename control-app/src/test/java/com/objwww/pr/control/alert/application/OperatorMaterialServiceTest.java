package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.OperatorMaterial;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.OperatorMaterialRepository;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MC31/32 人工材料入口（MA-07 人为补充材料审计面）：EVIDENCE_LINK 必带来源；
 * MC32 CAS——同 baseRevision 并发提交至多一个生效、败方冲突可见；incident 已
 * RESOLVED 后提交 = REJECTED_LATE 明确终态；认证端身份缺席快速失败。
 */
class OperatorMaterialServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T11:00:00Z");

    private final UUID incidentId = UUID.randomUUID();
    private final AlertInMemoryStores.Incidents incidents = new AlertInMemoryStores.Incidents();
    private final MemMaterials materials = new MemMaterials();

    private OperatorMaterialService service;

    @BeforeEach
    void wire() {
        incidents.insert(new Incident(incidentId, "alertname=HighError|service=checkout",
                IncidentStatus.FIRING, 0, NOW, NOW, null, null, null, 0, 0, 0,
                null, NOW, NOW, NOW, NOW));
        service = new OperatorMaterialService(materials, incidents, directTx(),
                () -> NOW);
    }

    private OperatorMaterialService.Submission submission(String sourceRef) {
        return new OperatorMaterialService.Submission(incidentId, null,
                sourceRef == null
                        ? OperatorMaterial.Kind.JUDGMENT
                        : OperatorMaterial.Kind.EVIDENCE_LINK,
                sourceRef, "发布窗口内的变更材料", 0);
    }

    @Test
    @DisplayName("MC31：EVIDENCE_LINK 必带 source_ref（材料有来源面）")
    void evidenceLinkRequiresSourceRef() {
        assertThatThrownBy(() -> service.submit(
                new OperatorMaterialService.Submission(incidentId, null,
                        OperatorMaterial.Kind.EVIDENCE_LINK, null, "发布变更材料", 0),
                "op-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source_ref");
    }

    @Test
    @DisplayName("MC31：JUDGMENT（无引用判断）可准入但 kind 单列——不绕过 Claim 准入的信封面前置")
    void judgmentAdmittedButTypedSeparately() {
        OperatorMaterialService.Verdict verdict = service.submit(
                new OperatorMaterialService.Submission(incidentId, null,
                        OperatorMaterial.Kind.JUDGMENT, null, "肯定是发布导致的", 0),
                "op-1");

        assertThat(verdict.outcome()).isEqualTo(OperatorMaterialService.Verdict.Outcome.ACCEPTED);
        assertThat(verdict.material().kind()).isEqualTo(OperatorMaterial.Kind.JUDGMENT);
        assertThat(verdict.material().revision()).isEqualTo(1);
    }

    @Test
    @DisplayName("MC32：同 baseRevision 二次提交 → CONFLICT 可见（至多一个生效）")
    void sameBaseRevisionConflictsVisibly() {
        assertThat(service.submit(submission("https://vcs/commit/a"), "op-1")
                .outcome()).isEqualTo(OperatorMaterialService.Verdict.Outcome.ACCEPTED);

        OperatorMaterialService.Verdict second = service.submit(submission("https://vcs/commit/b"), "op-2");

        assertThat(second.outcome()).isEqualTo(OperatorMaterialService.Verdict.Outcome.CONFLICT);
        assertThat(second.currentRevision()).as("冲突应答携带当前版本可见").isEqualTo(1);
        assertThat(second.material()).isNull();
        assertThat(materials.rows).as("败方不落行").hasSize(1);
    }

    @Test
    @DisplayName("MC32：前进行进式提交（读当前版本再提交）逐个生效")
    void sequentialSubmissionsAdvanceRevision() {
        assertThat(service.submit(submission("https://vcs/c1"), "op-1")
                .currentRevision()).isEqualTo(1);
        OperatorMaterialService.Verdict third = service.submit(
                new OperatorMaterialService.Submission(incidentId, null,
                        OperatorMaterial.Kind.OBSERVATION, null, "观察：网关 5xx 集中", 1),
                "op-2");

        assertThat(third.outcome()).isEqualTo(OperatorMaterialService.Verdict.Outcome.ACCEPTED);
        assertThat(third.material().revision()).isEqualTo(2);
        assertThat(materials.currentRevision(incidentId)).isEqualTo(2);
    }

    @Test
    @DisplayName("MC32：incident 已 RESOLVED 后提交 → REJECTED_LATE 明确终态（审计落行）")
    void submissionAfterResolvedIsLateAndTerminal() {
        incidents.update(new Incident(incidentId, "alertname=HighError|service=checkout",
                IncidentStatus.RESOLVED, 0, NOW, NOW, NOW, null, null, 0, 0, 0,
                null, NOW, NOW, NOW, NOW));

        OperatorMaterialService.Verdict verdict = service.submit(submission("https://vcs/c9"), "op-1");

        assertThat(verdict.outcome()).isEqualTo(OperatorMaterialService.Verdict.Outcome.LATE);
        assertThat(verdict.material().admission())
                .isEqualTo(OperatorMaterial.Admission.REJECTED_LATE);
        assertThat(verdict.currentRevision()).as("材料集版本单调推进（明确终态不挂起）")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("认证面：operator 由认证端传入，缺席快速失败（客户端不可自报身份）")
    void operatorIdentityRequired() {
        assertThatThrownBy(() -> service.submit(submission("https://vcs/x"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("operator");
    }

    // ------------------------------------------------------------------ 假件

    private static TransactionOperations directTx() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }

            @Override
            public void executeWithoutResult(Consumer<TransactionStatus> action) {
                action.accept(null);
            }
        };
    }

    static final class MemMaterials implements OperatorMaterialRepository {
        final List<OperatorMaterial> rows = new ArrayList<>();

        @Override
        public void insert(OperatorMaterial material) {
            rows.add(material);
        }

        @Override
        public List<OperatorMaterial> findByIncident(UUID id) {
            return rows.stream().filter(m -> m.incidentId().equals(id))
                    .sorted(java.util.Comparator.comparingInt(OperatorMaterial::revision))
                    .toList();
        }

        @Override
        public int currentRevision(UUID id) {
            return rows.stream().filter(m -> m.incidentId().equals(id))
                    .mapToInt(OperatorMaterial::revision).max().orElse(0);
        }

        @Override
        public List<OperatorMaterial> findAcceptedByRun(UUID runId) {
            return rows.stream().filter(m -> runId.equals(m.runId())
                    && m.admission() == OperatorMaterial.Admission.ACCEPTED).toList();
        }
    }
}
