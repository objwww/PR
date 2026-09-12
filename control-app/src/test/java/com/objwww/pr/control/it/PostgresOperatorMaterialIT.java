package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.OperatorMaterialService;
import com.objwww.pr.control.alert.domain.model.OperatorMaterial;
import com.objwww.pr.control.alert.domain.repository.OperatorMaterialRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresOperatorMaterialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MC31/32 人工材料入口真 PG 数据面（L1；本机无 docker 自动跳过）：V96 形状与
 * CHECK（kind 词表/EVIDENCE_LINK 必带 source/内容长度）、uq(incident,revision)
 * = MC32 CAS 串行化点、RESOLVED 后 REJECTED_LATE 明确终态、control_app 授权面
 * 与 publisher 零权限。CAS/终态裁决语义的封闭用例见 OperatorMaterialServiceTest（L0）。
 */
class PostgresOperatorMaterialIT extends PostgresITBase {

    private static final Instant NOW = Instant.parse("2026-09-12T12:30:00Z");

    private UUID incidentId;
    private OperatorMaterialRepository materials;
    private OperatorMaterialService service;

    @BeforeEach
    void seed() {
        materials = new PostgresOperatorMaterialRepository(controlJdbc, new ObjectMapper());
        incidentId = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO incident(id, incident_key, status, generation,
                    episode_started_at, first_seen_at, last_event_at, created_at, updated_at)
                VALUES (:id, :key, 'FIRING', 0, now(), now(), now(), now(), now())
                """).param("id", incidentId)
                .param("key", "alertname=HighErrorRate|service=mc31-" + incidentId)
                .update();
        service = new OperatorMaterialService(materials,
                new com.objwww.pr.control.infrastructure.persistence
                        .PostgresIncidentRepository(controlJdbc),
                controlTx, AlertClock.system());
    }

    private OperatorMaterialService.Submission submission(String sourceRef, int base) {
        return new OperatorMaterialService.Submission(incidentId, null,
                sourceRef == null ? OperatorMaterial.Kind.JUDGMENT
                        : OperatorMaterial.Kind.EVIDENCE_LINK,
                sourceRef, "操作者补充材料", base);
    }

    @Test
    @DisplayName("MC31/32：ACCEPTED 落行 revision 单调；uq(incident,revision) 兜底直插")
    void acceptedRowsMonotonicRevisionUnique() {
        OperatorMaterialService.Verdict first = service.submit(
                submission("https://vcs.example.com/commit/a", 0), "op-1");
        OperatorMaterialService.Verdict second = service.submit(
                new OperatorMaterialService.Submission(incidentId, null,
                        OperatorMaterial.Kind.OBSERVATION, null, "观察材料", 1), "op-2");

        assertThat(first.outcome()).isEqualTo(OperatorMaterialService.Verdict.Outcome.ACCEPTED);
        assertThat(second.material().revision()).isEqualTo(2);
        assertThat(materials.currentRevision(incidentId)).isEqualTo(2);

        assertThatThrownBy(() -> controlJdbc.sql("""
                        INSERT INTO incident_operator_material(id, incident_id, operator,
                            kind, content, base_revision, revision, admission, created_at)
                        VALUES (:id, :inc, 'op-x', 'OBSERVATION', '并发行', 0, 1,
                            'ACCEPTED', now())
                        """).param("id", UUID.randomUUID()).param("inc", incidentId)
                .update()).as("uq(incident_id, revision) 兜底非行锁路径")
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("MC32：CAS 冲突不落行且应答带当前版本；RESOLVED 后 REJECTED_LATE")
    void conflictAndLateFaces() {
        service.submit(submission("https://vcs/c1", 0), "op-1");

        OperatorMaterialService.Verdict conflict = service.submit(
                submission("https://vcs/c2", 0), "op-2");
        assertThat(conflict.outcome()).isEqualTo(OperatorMaterialService.Verdict.Outcome.CONFLICT);
        assertThat(conflict.currentRevision()).isEqualTo(1);
        assertThat(materials.findByIncident(incidentId)).as("冲突方不落行").hasSize(1);

        adminJdbc.sql("UPDATE incident SET status = 'RESOLVED' WHERE id = :id")
                .param("id", incidentId).update();
        OperatorMaterialService.Verdict late = service.submit(
                submission("https://vcs/c3", 1), "op-1");
        assertThat(late.outcome()).isEqualTo(OperatorMaterialService.Verdict.Outcome.LATE);
        assertThat(late.material().admission())
                .isEqualTo(OperatorMaterial.Admission.REJECTED_LATE);
        assertThat(materials.currentRevision(incidentId)).as("版本照常单调（明确终态）")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("MC31：EVIDENCE_LINK 无 source 与超长内容被 DB CHECK 拒（词表/长度背书）")
    void databaseChecksEnforceSourceAndLength() {
        assertThatThrownBy(() -> controlJdbc.sql("""
                        INSERT INTO incident_operator_material(id, incident_id, operator,
                            kind, content, base_revision, revision, admission, created_at)
                        VALUES (:id, :inc, 'op-1', 'EVIDENCE_LINK', '无来源链接', 0, 1,
                            'ACCEPTED', now())
                        """).param("id", UUID.randomUUID()).param("inc", incidentId)
                .update()).as("ck_mc31_material_source")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> controlJdbc.sql("""
                        INSERT INTO incident_operator_material(id, incident_id, operator,
                            kind, content, base_revision, revision, admission, created_at)
                        VALUES (:id, :inc, 'op-1', 'OBSERVATION', :content, 0, 1,
                            'ACCEPTED', now())
                        """).param("id", UUID.randomUUID()).param("inc", incidentId)
                .param("content", "X".repeat(20_001))
                .update()).as("ck_mc31_material_content 长度上限")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> controlJdbc.sql("""
                        INSERT INTO incident_operator_material(id, incident_id, operator,
                            kind, content, base_revision, revision, admission, created_at)
                        VALUES (:id, :inc, 'op-1', 'RUMOR', '非法词表', 0, 1,
                            'ACCEPTED', now())
                        """).param("id", UUID.randomUUID()).param("inc", incidentId)
                .update()).as("ck_mc31_material_kind 词表")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("授权面：control_app 只增读；publisher_app 零权限（V96 矩阵）")
    void grantsFollowLeastPrivilege() {
        service.submit(submission("https://vcs/ok", 0), "op-1");

        assertThat(controlJdbc.sql("SELECT count(*) FROM incident_operator_material")
                .query(Long.class).single()).isEqualTo(1L);
        assertThatThrownBy(() -> publisherJdbc.sql(
                        "SELECT count(*) FROM incident_operator_material")
                .query(Long.class).single())
                .hasStackTraceContaining("permission denied");
    }
}
