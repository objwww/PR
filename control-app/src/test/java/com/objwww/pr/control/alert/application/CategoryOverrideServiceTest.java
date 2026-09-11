package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.classification.IncidentCategory;
import com.objwww.pr.control.alert.domain.repository.IncidentCategoryRepository.OverrideAuditRow;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CategoryOverrideService 单测（UX-01；假端口 + withoutTransaction 直通）：
 * override 审计行完整（谁/从哪类到哪类/理由/revision 链）、理由必填、
 * 修订锚 409 零副作用、幂等重放原行返回、显式撤销回落规则面、
 * 规则重分类不覆盖人工值（生效面 = override ?? rule ?? UNCLASSIFIED）。
 */
class CategoryOverrideServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");

    private final AlertInMemoryStores stores = new AlertInMemoryStores();
    private final CategoryOverrideService service = new CategoryOverrideService(
            stores.categories, TransactionOperations.withoutTransaction(), () -> NOW);

    private UUID incidentId;

    @BeforeEach
    void seedIncident() {
        incidentId = UUID.randomUUID();
        // 规则面已分类（等价投影路径落 rule_*；override_revision=0）
        stores.categories.applyRuleClassification(incidentId, IncidentCategory.INFRA,
                "INFRA-ALERTNAME", "ux01-rules-v1", NOW);
    }

    @Test
    void overrideWritesCompleteAuditRowAndKeepsRuleColumns() {
        var result = service.override(incidentId, "APPLICATION", "误判：实为应用自身异常",
                0, "k-1", "operator-a").orElseThrow();

        assertThat(result.state()).isEqualTo(CategoryOverrideService.State.APPLIED);
        assertThat(result.category()).isEqualTo("APPLICATION");
        assertThat(result.categorySource()).isEqualTo("OVERRIDE");
        assertThat(result.revision()).isEqualTo(1);
        assertThat(result.replayed()).isFalse();

        OverrideAuditRow audit = stores.categories.auditRows(incidentId).get(0);
        assertThat(audit.id()).isEqualTo(result.auditId());
        assertThat(audit.action()).isEqualTo("SET");
        assertThat(audit.fromCategory()).isEqualTo("INFRA");        // 动作前生效面
        assertThat(audit.toCategory()).isEqualTo("APPLICATION");
        assertThat(audit.actor()).isEqualTo("operator-a");
        assertThat(audit.reason()).isEqualTo("误判：实为应用自身异常");
        assertThat(audit.expectedRevision()).isZero();
        assertThat(audit.resultRevision()).isEqualTo(1);
        assertThat(audit.createdAt()).isEqualTo(NOW);

        // 规则面不被覆盖（两族列分离）；生效面 = override
        var state = stores.categories.state(incidentId);
        assertThat(state.ruleCategory).isEqualTo("INFRA");
        assertThat(state.overrideCategory).isEqualTo("APPLICATION");
        assertThat(state.effective()).isEqualTo("APPLICATION");
    }

    @Test
    void blankReasonIsRejectedBeforeAnyWrite() {
        assertThatThrownBy(() -> service.override(incidentId, "DATA", "   ",
                0, "k-2", "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason 必填");
        assertThat(stores.categories.auditRows(incidentId)).isEmpty();
        assertThat(stores.categories.state(incidentId).overrideCategory).isNull();
    }

    @Test
    void invalidCategoryAndMissingAnchorsAreRejected() {
        assertThatThrownBy(() -> service.override(incidentId, "NOPE", "r", 0, "k", "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("category 非法");
        assertThatThrownBy(() -> service.override(incidentId, "DATA", "r", null, "k", "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedRevision");
        assertThatThrownBy(() -> service.override(incidentId, "DATA", "r", 0, " ", "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    void staleRevisionIsRejectedWithZeroSideEffects() {
        service.override(incidentId, "DATA", "首判", 0, "k-1", "op-a");

        var stale = service.override(incidentId, "NETWORK", "过期客户端重试",
                0, "k-3", "op-b").orElseThrow();

        assertThat(stale.state()).isEqualTo(CategoryOverrideService.State.REJECTED_STALE);
        assertThat(stale.revision()).isEqualTo(1);                  // 返回当前真 revision
        assertThat(stores.categories.auditRows(incidentId)).hasSize(1);
        assertThat(stores.categories.state(incidentId).overrideCategory).isEqualTo("DATA");
    }

    @Test
    void idempotentReplayReturnsOriginalAuditRow() {
        var first = service.override(incidentId, "DATA", "首判", 0, "k-1", "op-a")
                .orElseThrow();
        var replay = service.override(incidentId, "DATA", "首判", 0, "k-1", "op-a")
                .orElseThrow();

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.auditId()).isEqualTo(first.auditId());
        assertThat(replay.revision()).isEqualTo(1);
        assertThat(stores.categories.auditRows(incidentId)).hasSize(1);
        assertThat(stores.categories.state(incidentId).overrideRevision).isEqualTo(1);
    }

    @Test
    void revokeFallsBackToRuleCategoryWithAuditTrail() {
        service.override(incidentId, "DATA", "首判", 0, "k-1", "op-a");

        var revoked = service.revoke(incidentId, "复核后认可规则分类", 1, "k-4", "op-b")
                .orElseThrow();

        assertThat(revoked.state()).isEqualTo(CategoryOverrideService.State.APPLIED);
        assertThat(revoked.action()).isEqualTo("REVOKE");
        assertThat(revoked.category()).isEqualTo("INFRA");          // 回落规则面
        assertThat(revoked.categorySource()).isEqualTo("RULE");
        assertThat(revoked.revision()).isEqualTo(2);

        var state = stores.categories.state(incidentId);
        assertThat(state.overrideCategory).isNull();
        assertThat(state.effective()).isEqualTo("INFRA");
        assertThat(stores.categories.auditRows(incidentId)).hasSize(2);
        OverrideAuditRow revokeRow = stores.categories.auditRows(incidentId).get(1);
        assertThat(revokeRow.action()).isEqualTo("REVOKE");
        assertThat(revokeRow.fromCategory()).isEqualTo("DATA");
        assertThat(revokeRow.toCategory()).isNull();
        assertThat(revokeRow.resultRevision()).isEqualTo(2);
    }

    @Test
    void revokeWithoutExistingOverrideIsRejected() {
        assertThatThrownBy(() -> service.revoke(incidentId, "无的撤销", 0, "k-5", "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无人工 override");
    }

    /** EU47 核心：override 后规则回填/再投影，override 保留且生效面不变 */
    @Test
    void ruleReclassificationNeverCoversOverride() {
        service.override(incidentId, "SECURITY", "安全事件人工确认", 0, "k-1", "sec-op");

        // 规则重分类（规则版本演进/标签变化）只写 rule_* 面
        stores.categories.applyRuleClassification(incidentId, IncidentCategory.NETWORK,
                "NETWORK-ALERTNAME", "ux01-rules-v2", NOW.plusSeconds(60));

        var state = stores.categories.state(incidentId);
        assertThat(state.ruleCategory).isEqualTo("NETWORK");        // 规则面可追溯地更新
        assertThat(state.overrideCategory).isEqualTo("SECURITY");   // 人工值原样保留
        assertThat(state.overrideRevision).isEqualTo(1);            // revision 不被规则面推进
        assertThat(state.effective()).isEqualTo("SECURITY");
        assertThat(stores.categories.auditRows(incidentId)).hasSize(1);
    }

    @Test
    void unknownIncidentReturnsEmpty() {
        assertThat(service.override(UUID.randomUUID(), "DATA", "r", 0, "k", "op"))
                .isEmpty();
    }
}
