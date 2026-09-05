package com.objwww.pr.control.eval.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3-11~13 备料：同义词词典冻结匹配语义（M-02 全串精确等值 / M-03 trim+ASCII casefold /
 * M-04 白名单外 NO_MATCH 不抛错 / reason_code 从属 fault_type 校验；M-01/05/06 属
 * 装载与公式层职责，由 GoldenScenarioRegistryTest 与 ScenarioMetricsTest 分担）。
 * 样本与 deploy/alert/eval/synonym-lexicon-v1.yml 同构（条目形态/负样本字段一致）。
 */
class SynonymLexiconTest {

    private static final String LEXICON = """
            lexicon_version: 1
            future_extension_key: 忽略我
            components:
              - code: payment
                bound_scenarios: [S1, S2]
                synonyms:
                  - payment-svc
                  - 支付服务
                negative_examples:
                  - checkout
              - code: order-arena
                bound_scenarios: [S3, S4, S5]
                synonyms:
                  - 靶场
                negative_examples:
                  - checkout
            fault_types:
              - code: BUSINESS_ERROR_RATE
                chaos_family: null
                bound_scenario: S1
                synonyms:
                  - business error rate
                  - 业务错误率升高
                  - 支付失败率升高
                negative_examples:
                  - dependency unreachable
              - code: DEPENDENCY_UNREACHABLE
                chaos_family: null
                bound_scenario: S2
                synonyms:
                  - 依赖不可达
                  - connection refused
                negative_examples:
                  - 按比例失败
            reason_codes:
              - code: PAYMENT_CHARGE_FAILURE
                fault_type: BUSINESS_ERROR_RATE
                bound_scenario: S1
                synonyms:
                  - 扣款失败
                  - charge failure
                negative_examples:
                  - payment unreachable
              - code: PAYMENT_SERVICE_UNREACHABLE
                fault_type: DEPENDENCY_UNREACHABLE
                bound_scenario: S2
                synonyms:
                  - payment unreachable
                negative_examples:
                  - 扣款失败
            negative_samples:
              - disk full
              - memory leak
              - 网络分区
            """;

    private static final SynonymLexicon LEX = SynonymLexicon.load(LEXICON);

    @Test
    @DisplayName("M-02/03：canonical 命中 + 同义词命中 + 去空白与 ASCII 大小写折叠（中文原样）")
    void canonicalAndSynonymHitsWithNormalization() {
        assertThat(LEX.componentMatches("payment", "payment")).isTrue();
        assertThat(LEX.componentMatches("payment", "payment-svc")).isTrue();
        assertThat(LEX.componentMatches("payment", "  PAYMENT  ")).isTrue();
        assertThat(LEX.componentMatches("payment", "Payment-Svc")).isTrue();
        assertThat(LEX.componentMatches("order-arena", "靶场")).isTrue();

        assertThat(LEX.faultTypeMatches("BUSINESS_ERROR_RATE", "BUSINESS_ERROR_RATE")).isTrue();
        assertThat(LEX.faultTypeMatches("BUSINESS_ERROR_RATE", "business error rate")).isTrue();
        assertThat(LEX.faultTypeMatches("BUSINESS_ERROR_RATE", "业务错误率升高")).isTrue();
        assertThat(LEX.faultTypeMatches("DEPENDENCY_UNREACHABLE", " connection refused ")).isTrue();
    }

    @Test
    @DisplayName("M-02：只认全串精确等值——子串/前缀/超集/正则片段一律 NO_MATCH")
    void exactFullStringOnlyNoFuzzyMatching() {
        assertThat(LEX.componentMatches("payment", "payment-svc-2")).isFalse();
        assertThat(LEX.componentMatches("payment", "pay")).isFalse();
        assertThat(LEX.componentMatches("payment", "payments")).isFalse();
        assertThat(LEX.faultTypeMatches("BUSINESS_ERROR_RATE", "BUSINESS_ERROR")).isFalse();
        assertThat(LEX.faultTypeMatches("BUSINESS_ERROR_RATE", "business error rate spike")).isFalse();
        assertThat(LEX.faultTypeMatches("BUSINESS_ERROR_RATE", "BUSINESS_ERROR_RATE%")).isFalse();
        assertThat(LEX.faultTypeMatches("BUSINESS_ERROR_RATE", "业务错误率")).isFalse();
        assertThat(LEX.reasonCodeMatches("PAYMENT_CHARGE_FAILURE", "BUSINESS_ERROR_RATE",
                "扣款失败率")).isFalse();
    }

    @Test
    @DisplayName("M-04：白名单外 = NO_MATCH 不抛错；跨类 synonym/负样本/全局 negative_samples 回归")
    void outOfWhitelistIsNoMatchNotThrow() {
        assertThat(LEX.componentMatches("payment", "checkout")).isFalse();
        assertThat(LEX.faultTypeMatches("BUSINESS_ERROR_RATE", "dependency unreachable"))
                .isFalse();
        assertThat(LEX.faultTypeMatches("DEPENDENCY_UNREACHABLE", "按比例失败")).isFalse();
        assertThat(LEX.reasonCodeMatches("PAYMENT_CHARGE_FAILURE", "BUSINESS_ERROR_RATE",
                "payment unreachable")).isFalse();

        for (String noise : List.of("disk full", "memory leak", "网络分区", "", "   ")) {
            assertThat(LEX.componentMatches("payment", noise)).isFalse();
            assertThat(LEX.componentMatches("order-arena", noise)).isFalse();
            assertThat(LEX.faultTypeMatches("BUSINESS_ERROR_RATE", noise)).isFalse();
            assertThat(LEX.faultTypeMatches("DEPENDENCY_UNREACHABLE", noise)).isFalse();
            assertThat(LEX.reasonCodeMatches("PAYMENT_CHARGE_FAILURE", "BUSINESS_ERROR_RATE",
                    noise)).isFalse();
        }
        assertThat(LEX.componentMatches("payment", null)).isFalse();
        assertThat(LEX.faultTypeMatches("BUSINESS_ERROR_RATE", null)).isFalse();
        assertThatCode(() -> LEX.componentMatches("payment", "磁盘写满")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("reason_code 从属校验：同串换 owner fault_type 即不命中（跨场景配对禁止）")
    void reasonCodeOwnershipEnforced() {
        assertThat(LEX.reasonCodeMatches("PAYMENT_CHARGE_FAILURE", "BUSINESS_ERROR_RATE",
                "PAYMENT_CHARGE_FAILURE")).isTrue();
        assertThat(LEX.reasonCodeMatches("PAYMENT_CHARGE_FAILURE", "BUSINESS_ERROR_RATE",
                "扣款失败")).isTrue();

        assertThat(LEX.reasonCodeMatches("PAYMENT_CHARGE_FAILURE", "DEPENDENCY_UNREACHABLE",
                "PAYMENT_CHARGE_FAILURE")).isFalse();
        assertThat(LEX.reasonCodeMatches("PAYMENT_CHARGE_FAILURE", "BUSINESS_ERROR_RATE",
                "payment unreachable")).isFalse();
        assertThat(LEX.reasonCodeMatches("PAYMENT_SERVICE_UNREACHABLE", "DEPENDENCY_UNREACHABLE",
                "payment unreachable")).isTrue();
        assertThat(LEX.reasonCodeMatches("NO_SUCH_CODE", "BUSINESS_ERROR_RATE",
                "PAYMENT_CHARGE_FAILURE")).isFalse();
    }

    @Test
    @DisplayName("装载：lexicon_version 透出、未知顶层键忽略；缺维度/缺版本/根非映射拒绝")
    void loadRejectsIncompleteLexicons() {
        assertThat(LEX.lexiconVersion()).isEqualTo(1);

        assertThatThrownBy(() -> SynonymLexicon.load(LEXICON.replace(
                "fault_types:", "fault_type_list:")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("维度");
        assertThatThrownBy(() -> SynonymLexicon.load(LEXICON.replace(
                "reason_codes:", "reason_code_list:")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason_codes");
        assertThatThrownBy(() -> SynonymLexicon.load(LEXICON.replace(
                "lexicon_version: 1", "lexicon_v: 1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lexicon_version");
        assertThatThrownBy(() -> SynonymLexicon.load(new StringReader("- a\n- b\n")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("映射");
    }
}
