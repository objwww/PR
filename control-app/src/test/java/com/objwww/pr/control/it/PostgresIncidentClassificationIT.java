package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.classification.IncidentCategory;
import com.objwww.pr.control.alert.domain.classification.IncidentClassifier;
import com.objwww.pr.control.alert.domain.repository.IncidentCategoryRepository.OverrideAuditRow;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.Facets;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.IncidentDetail;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.IncidentPage;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentCategoryRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentQueryReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PermissionDeniedDataAccessException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UX-01 验收（真 PG，failsafe *IT；本机无 docker 自动跳过——交付标 NOT_RUN）：
 * V82 incident 分类列约束（词表 CHECK / 生成列生效面 / 两族列同生同灭）、
 * incident_category_override 授权矩阵（control_app 只增读、update/delete 拒、
 * publisher/notify/eval 显式拒）、审计 insert-only + 幂等锚 uq 撞键、
 * 读面 category 过滤与 facet 分桶、CAS 修订锚。
 */
class PostgresIncidentClassificationIT extends PostgresITBase {

    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");

    private PostgresIncidentCategoryRepository categories;
    private PostgresIncidentQueryReader reader;

    @BeforeEach
    void setUpRepositories() {
        categories = new PostgresIncidentCategoryRepository(controlJdbc);
        reader = new PostgresIncidentQueryReader(controlJdbc, new ObjectMapper());
    }

    // ------------------------------------------------------------------ 列与约束

    @Test
    @DisplayName("V82 列：规则分类落 rule_*，生成列生效面 = coalesce(override, rule, UNCLASSIFIED)")
    void ruleClassificationAndGeneratedEffectiveColumns() {
        UUID id = UUID.randomUUID();
        insertIncident(id, "k-cpu");

        // 存量行：rule 面 NULL → 生效面 UNCLASSIFIED / RULE（不回填历史）
        assertThat(effectiveOf(id)).isEqualTo(Map.of("category", "UNCLASSIFIED",
                "source", "RULE"));

        categories.applyRuleClassification(id, IncidentCategory.INFRA,
                "INFRA-ALERTNAME", IncidentClassifier.RULE_VERSION, NOW);
        assertThat(effectiveOf(id)).isEqualTo(Map.of("category", "INFRA",
                "source", "RULE"));

        categories.setOverride(id, IncidentCategory.SECURITY, "sec-op", "人工确认", NOW, 0);
        assertThat(effectiveOf(id)).isEqualTo(Map.of("category", "SECURITY",
                "source", "OVERRIDE"));

        categories.clearOverride(id, 1);
        assertThat(effectiveOf(id)).isEqualTo(Map.of("category", "INFRA",
                "source", "RULE"));
    }

    @Test
    @DisplayName("V82 CHECK：词表外分类直拒；override 四列部分填写直拒；理由空白直拒")
    void checkConstraintsRejectOutOfVocabularyAndPartialWrites() {
        UUID id = UUID.randomUUID();
        insertIncident(id, "k-check");

        assertThatThrownBy(() -> controlJdbc.sql(
                "update incident set rule_category = 'NOPE' where id = :id")
                .param("id", id).update())
                .hasMessageContaining("ck_incident_rule_category");
        // override 部分填写（缺 actor/reason/at）→ 同生同灭约束拒
        assertThatThrownBy(() -> controlJdbc.sql(
                "update incident set override_category = 'DATA' where id = :id")
                .param("id", id).update())
                .hasMessageContaining("ck_incident_override_whole");
        // 空白理由直拒
        assertThatThrownBy(() -> controlJdbc.sql("""
                update incident set override_category = 'DATA', override_actor = 'op',
                    override_reason = '   ', override_at = now() where id = :id
                """)
                .param("id", id).update())
                .hasMessageContaining("ck_incident_override_reason");
        // 生成列不可写
        assertThatThrownBy(() -> controlJdbc.sql(
                "update incident set category = 'DATA' where id = :id")
                .param("id", id).update())
                .hasMessageContaining("can only be updated to DEFAULT");
    }

    // ------------------------------------------------------------------ 授权矩阵

    @Test
    @DisplayName("授权矩阵：control_app 审计表只增读；update/delete 拒；publisher/notify/eval 显式拒")
    void auditTableGrantMatrix() {
        UUID id = UUID.randomUUID();
        insertIncident(id, "k-grant");
        categories.appendAudit(auditRow(id, "k-1"));
        assertThat(categories.findAuditByIdempotencyKey(id, "k-1")).isPresent();

        // control_app 零 update/delete 开口（insert-only）
        assertThatThrownBy(() -> controlJdbc.sql(
                "update incident_category_override set reason = 'x' where incident_id = :id")
                .param("id", id).update())
                .isInstanceOf(PermissionDeniedDataAccessException.class);
        assertThatThrownBy(() -> controlJdbc.sql(
                "delete from incident_category_override where incident_id = :id")
                .param("id", id).update())
                .isInstanceOf(PermissionDeniedDataAccessException.class);

        // publisher/notify/eval 显式归零（eval_app 不写告警域）
        for (var jdbc : new org.springframework.jdbc.core.simple.JdbcClient[]{
                publisherJdbc, notifyJdbc, evalJdbc}) {
            assertThatThrownBy(() -> jdbc.sql("""
                    insert into incident_category_override (id, incident_id, action,
                        to_category, actor, reason, expected_revision, result_revision,
                        idempotency_key, created_at)
                    values (:aid, :iid, 'SET', 'DATA', 'x', 'x', 0, 1, 'k-x', now())
                    """)
                    .param("aid", UUID.randomUUID()).param("iid", id).update())
                    .isInstanceOf(PermissionDeniedDataAccessException.class);
        }
    }

    @Test
    @DisplayName("幂等锚 uq(incident_id, idempotency_key) 撞键直拒")
    void idempotencyAnchorUnique() {
        UUID id = UUID.randomUUID();
        insertIncident(id, "k-idem");
        categories.appendAudit(auditRow(id, "k-dup"));
        assertThatThrownBy(() -> categories.appendAudit(auditRow(id, "k-dup")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("CAS：override_revision 不符零行生效（防御面）")
    void casRevisionGuard() {
        UUID id = UUID.randomUUID();
        insertIncident(id, "k-cas");
        assertThat(categories.setOverride(id, IncidentCategory.DATA, "op", "r", NOW, 5))
                .isFalse();
        assertThat(effectiveOf(id)).isEqualTo(Map.of("category", "UNCLASSIFIED",
                "source", "RULE"));
    }

    // ------------------------------------------------------------------ 读面

    @Test
    @DisplayName("读面：列表 category 过滤 + facet 生效面分桶 + 详情命中依据/override 快照")
    void readFaceCategoryProjection() {
        UUID infra = UUID.randomUUID();
        UUID sec = UUID.randomUUID();
        insertIncident(infra, "k-infra");
        insertIncident(sec, "k-sec");
        categories.applyRuleClassification(infra, IncidentCategory.INFRA,
                "INFRA-ALERTNAME", "ux01-rules-v1", NOW);
        categories.applyRuleClassification(sec, IncidentCategory.INFRA,
                "INFRA-ALERTNAME", "ux01-rules-v1", NOW);
        categories.setOverride(sec, IncidentCategory.SECURITY, "sec-op", "人工确认", NOW, 0);

        IncidentPage all = reader.listIncidents(null, null, null, null, null, null, 50);
        assertThat(all.items()).hasSize(2);
        IncidentPage onlySecurity = reader.listIncidents(null, null, null, null,
                "SECURITY", null, 50);
        assertThat(onlySecurity.items()).hasSize(1);
        assertThat(onlySecurity.items().get(0).category()).isEqualTo("SECURITY");
        assertThat(onlySecurity.items().get(0).categorySource()).isEqualTo("OVERRIDE");
        assertThat(onlySecurity.total()).isEqualTo(1);

        Facets facets = reader.facets(null, null, null);
        assertThat(facets.category())
                .containsEntry("INFRA", 1L)
                .containsEntry("SECURITY", 1L);

        IncidentDetail detail = reader.detail(sec).orElseThrow();
        assertThat(detail.row().category()).isEqualTo("SECURITY");
        assertThat(detail.categoryDetail().ruleId()).isEqualTo("INFRA-ALERTNAME");
        assertThat(detail.categoryDetail().ruleVersion()).isEqualTo("ux01-rules-v1");
        assertThat(detail.categoryDetail().classifiedAt()).isEqualTo(NOW);
        assertThat(detail.categoryDetail().overrideActor()).isEqualTo("sec-op");
        assertThat(detail.categoryDetail().overrideReason()).isEqualTo("人工确认");
        assertThat(detail.categoryDetail().overrideRevision()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, String> effectiveOf(UUID id) {
        return controlJdbc.sql("select category, category_source from incident where id = :id")
                .param("id", id)
                .query((rs, i) -> Map.of("category", rs.getString("category"),
                        "source", rs.getString("category_source")))
                .single();
    }

    private OverrideAuditRow auditRow(UUID incidentId, String key) {
        return new OverrideAuditRow(UUID.randomUUID(), incidentId, "SET",
                "UNCLASSIFIED", "DATA", "op", "理由", 0, 1, key, NOW);
    }

    private void insertIncident(UUID id, String key) {
        controlJdbc.sql("""
                insert into incident (id, incident_key, status, episode_started_at,
                    received_count, distinct_event_count, notification_count,
                    first_seen_at, last_event_at, created_at, updated_at)
                values (:id, :key, 'FIRING', :at, 1, 1, 0, :at, :at, :at, :at)
                """)
                .param("id", id)
                .param("key", key)
                .param("at", Timestamp.from(NOW))
                .update();
    }
}
