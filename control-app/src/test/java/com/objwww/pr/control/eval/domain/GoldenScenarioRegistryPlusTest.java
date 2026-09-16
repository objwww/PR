package com.objwww.pr.control.eval.domain;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P2 执行集接通：plus 合并回放案例——场景头承自基座、digest 覆盖合并面、重键拒绝。 */
class GoldenScenarioRegistryPlusTest {

    private static final String BASE = """
            registry_version: 1
            schema_version: 1
            lexicon_binding: "lex-v1"
            scenarios:
              - scenario_id: S1
                driver: FlagdScenarioDriver
                expected_root_cause:
                  component: payment
                  fault_type: BUSINESS_ERROR_RATE
                  reason_code: PAYMENT_CHARGE_FAILURE
                expected_symptom_codes:
                  - checkout
                timing:
                  preheat_seconds: 5
                  hold_seconds: 10
                  max_firing_wait_seconds: 10
                  max_resolved_wait_seconds: 10
                  cleanup_timeout_seconds: 5
            """;

    private static GoldenCase replayCase(String id) {
        return new GoldenCase(id, "回放", "ReplayScenarioDriver", null, "fam",
                new TypedRootCause("order", "BUSINESS_ERROR_RATE", "ORDER_STUCK"),
                List.of("op-smoke-x"), null, null,
                new GoldenCase.Timing(0, 900, 120, 300, 60),
                GoldenCase.KIND_REPLAY);
    }

    @Test
    @DisplayName("plus 合并：版本头承自基座、执行面=注入+回放、contentDigest 覆盖合并面")
    void plusMergesWithBaseHeaderAndDigestCoversExtras() {
        GoldenScenarioRegistry base = GoldenScenarioRegistry.load(BASE);
        GoldenScenarioRegistry merged = base.plus(List.of(replayCase("case-1")));

        assertThat(merged.registryVersion()).isEqualTo(1);
        assertThat(merged.lexiconBinding()).isEqualTo("lex-v1");
        assertThat(merged.scenarios()).hasSize(2);
        assertThat(merged.byScenarioId("case-1").replay()).isTrue();
        assertThat(merged.contentDigest().value())
                .isNotEqualTo(base.contentDigest().value());
        // 基座本体不变（不可变合并）
        assertThat(base.scenarios()).hasSize(1);
    }

    @Test
    @DisplayName("场景键冲突（YAML 与回放案例重叠）→ 拒绝（执行归属必须可判定）")
    void duplicateScenarioIdRejected() {
        GoldenScenarioRegistry base = GoldenScenarioRegistry.load(BASE);

        assertThatThrownBy(() -> base.plus(List.of(replayCase("S1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("场景键冲突");
    }
}
