package com.objwww.pr.control.eval.domain;

import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3-10：GoldenCase 适配器——eval-scenarios.yml 白名单字段装载、未知键忽略、
 * 缺陷注册表拒绝、内容 digest 稳定性（M3-14 可复现元数据的来源）。
 */
class GoldenScenarioRegistryTest {

    /** 与 deploy/alert/eval/eval-scenarios.yml 同构的最小样本（S1=AM0 flagd / S3=靶场） */
    private static final String REGISTRY = """
            registry_version: 1
            schema_version: 1
            lexicon_binding: "synonym-lexicon-v1.yml (lexicon_version: 1)"
            future_extension_key: 忽略我
            scenarios:
              - scenario_id: S1
                name: paymentFailure=50%
                driver: FlagdScenarioDriver
                chaos_family: null
                target: payment
                expected_root_cause:
                  component: payment
                  fault_type: BUSINESS_ERROR_RATE
                  reason_code: PAYMENT_CHARGE_FAILURE
                expected_symptom_codes:
                  - checkout
                timing:
                  preheat_seconds: 60
                  hold_seconds: 600
                  max_firing_wait_seconds: 600
                  max_resolved_wait_seconds: 600
                  cleanup_timeout_seconds: 120
              - scenario_id: S3
                name: F1 幂等失效
                driver: ArenaChaosScenarioDriver
                chaos_family: F1
                target: order-arena chaos- 前缀评测流量
                expected_root_cause:
                  component: order-arena
                  fault_type: IDEMPOTENCY_BYPASS
                  reason_code: DUPLICATE_CREATE_SAME_INTENT
                expected_symptom_codes:
                  - ArenaDuplicateOrders
                timing:
                  preheat_seconds: 60
                  hold_seconds: 600
                  max_firing_wait_seconds: 300
                  max_resolved_wait_seconds: 600
                  cleanup_timeout_seconds: 120
            """;

    @Test
    @DisplayName("装载：白名单字段逐项映射；未知顶层键忽略（文件只增不改不破装载）")
    void loadsWhitelistFieldsAndIgnoresUnknownKeys() {
        GoldenScenarioRegistry registry = GoldenScenarioRegistry.load(REGISTRY);

        assertThat(registry.registryVersion()).isEqualTo(1);
        assertThat(registry.schemaVersion()).isEqualTo(1);
        assertThat(registry.lexiconBinding()).contains("synonym-lexicon-v1");
        assertThat(registry.scenarios()).hasSize(2);

        GoldenCase s1 = registry.byScenarioId("S1");
        assertThat(s1.driver()).isEqualTo("FlagdScenarioDriver");
        assertThat(s1.chaosFamily()).isNull();
        assertThat(s1.expectedRootCause().component()).isEqualTo("payment");
        assertThat(s1.expectedRootCause().faultType()).isEqualTo("BUSINESS_ERROR_RATE");
        assertThat(s1.expectedRootCause().reasonCode()).isEqualTo("PAYMENT_CHARGE_FAILURE");
        assertThat(s1.expectedSymptomCodes()).containsExactly("checkout");
        assertThat(s1.timing().preheatSeconds()).isEqualTo(60);
        assertThat(s1.timing().holdSeconds()).isEqualTo(600);

        GoldenCase s3 = registry.byScenarioId("S3");
        assertThat(s3.chaosFamily()).isEqualTo("F1");
        assertThat(s3.expectedSymptomCodes()).containsExactly("ArenaDuplicateOrders");
        assertThat(s3.timing().maxFiringWaitSeconds()).isEqualTo(300);
    }

    @Test
    @DisplayName("contentDigest：同内容 digest 稳定；场景行内容变化 → digest 变化")
    void contentDigestIsStableAndSensitive() {
        Digest first = GoldenScenarioRegistry.load(REGISTRY).contentDigest();
        Digest second = GoldenScenarioRegistry.load(REGISTRY).contentDigest();
        assertThat(first.value()).isEqualTo(second.value()).hasSize(64);

        String mutated = REGISTRY.replace("BUSINESS_ERROR_RATE", "DEPENDENCY_UNREACHABLE");
        assertThat(GoldenScenarioRegistry.load(mutated).contentDigest().value())
                .isNotEqualTo(first.value());
    }

    @Test
    @DisplayName("拒绝面：缺期望根因 / 缺 timing / 缺 scenario_id / 根不是映射")
    void rejectsIncompleteRegistries() {
        assertThatThrownBy(() -> GoldenScenarioRegistry.load(
                REGISTRY.replace("expected_root_cause:", "expected_cause:")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected_root_cause");

        assertThatThrownBy(() -> GoldenScenarioRegistry.load(
                REGISTRY.replace("timing:", "timings:")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timing");

        assertThatThrownBy(() -> GoldenScenarioRegistry.load(
                REGISTRY.replace("scenario_id: S1", "id: S1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scenario_id");

        assertThatThrownBy(() -> GoldenScenarioRegistry.load("- just\n- a\n- list\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("映射");
    }

    @Test
    @DisplayName("未注册场景查询抛出（防止把错误场景的答案配给评分）")
    void unknownScenarioQueryRejected() {
        GoldenScenarioRegistry registry = GoldenScenarioRegistry.load(REGISTRY);

        assertThatThrownBy(() -> registry.byScenarioId("S99"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("S99");
    }
}
