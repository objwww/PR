package com.objwww.pr.control.alert.domain.mutation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ScopeSnapshot 单测（PB-B2）：canonical JSON 固定键序、hash 确定性、字段敏感性
 * （env/team/version/policy 任一漂移 = 锚变化 → 审批期作废语义可测）。
 */
class ScopeSnapshotTest {

    private ScopeSnapshot snapshot() {
        return new ScopeSnapshot("checkout", "res://demo/checkout", "demo", "payments",
                "service", 3, "pb-prod-v1");
    }

    @Test
    void pbS01_同字段同hash_确定性() {
        assertThat(snapshot().hash()).isEqualTo(snapshot().hash());
        assertThat(snapshot().toCanonicalJson()).isEqualTo(snapshot().toCanonicalJson());
    }

    @Test
    void pbS02_键序固定_可审计比对() {
        assertThat(snapshot().toCanonicalJson()).isEqualTo(
                "{\"requested_key\":\"checkout\",\"resource_uid\":\"res://demo/checkout\","
                        + "\"canonical_env\":\"demo\",\"canonical_team\":\"payments\","
                        + "\"resource_kind\":\"service\",\"resource_version\":3,"
                        + "\"policy_version\":\"pb-prod-v1\"}");
    }

    @Test
    void pbS03_任一授权事实漂移_锚即变化() {
        String base = snapshot().hash();
        assertThat(new ScopeSnapshot("checkout", "res://demo/checkout", "prod", "payments",
                "service", 3, "pb-prod-v1").hash()).isNotEqualTo(base); // env 漂移
        assertThat(new ScopeSnapshot("checkout", "res://demo/checkout", "demo", "web",
                "service", 3, "pb-prod-v1").hash()).isNotEqualTo(base); // team 漂移
        assertThat(new ScopeSnapshot("checkout", "res://demo/checkout", "demo", "payments",
                "service", 4, "pb-prod-v1").hash()).isNotEqualTo(base); // version 漂移
        assertThat(new ScopeSnapshot("checkout", "res://demo/checkout", "demo", "payments",
                "service", 3, "pb-prod-v2").hash()).isNotEqualTo(base); // policy 漂移（审批期作废语义）
        assertThat(new ScopeSnapshot("checkout-api", "res://demo/checkout", "demo",
                "payments", "service", 3, "pb-prod-v1").hash()).isNotEqualTo(base); // 请求键入快照（身份同 UID）
    }
}
