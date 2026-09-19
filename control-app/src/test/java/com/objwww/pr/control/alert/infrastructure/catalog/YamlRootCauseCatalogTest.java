package com.objwww.pr.control.alert.infrastructure.catalog;

import com.objwww.pr.control.alert.domain.agent.RootCauseCatalogPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * YamlRootCauseCatalog UT（根因码表，root_cause_hit 评分贯通面）：canonical 码按
 * 场景装订（reason_code→fault_type 属主 + components.bound_scenarios 反查组件）、
 * 无组件绑定的条目诚实跳过、文件缺失/解析失败 → WARN + 空表（不阻断告警主链）。
 */
class YamlRootCauseCatalogTest {

    private static final String LEXICON = """
            lexicon_version: 2
            components:
              - code: payment
                bound_scenarios: [S1, S2]
                description: 支付服务
              - code: order-arena
                bound_scenarios: [S3]
                description: 订单靶场
            fault_types:
              - code: BUSINESS_ERROR_RATE
                bound_scenario: S1
                description: 业务链路错误率
              - code: IDEMPOTENCY_BYPASS
                bound_scenario: S3
                description: 幂等失效
            reason_codes:
              - code: PAYMENT_CHARGE_FAILURE
                fault_type: BUSINESS_ERROR_RATE
                bound_scenario: S1
                description: 扣款按比例失败
              - code: DUPLICATE_CREATE_SAME_INTENT
                fault_type: IDEMPOTENCY_BYPASS
                bound_scenario: S3
                description: 同 intent 重复创单
              - code: ORPHAN_NO_COMPONENT
                fault_type: IDEMPOTENCY_BYPASS
                bound_scenario: S99
                description: 无组件绑定的场景（词表只增不改注释绑定面）
            """;

    @Test
    @DisplayName("正常装订：每 reason_code 一行 canonical 三元组 + 描述；synonyms 不下发")
    void parsesCanonicalEntries() {
        RootCauseCatalogPort catalog =
                YamlRootCauseCatalog.parse(new StringReader(LEXICON));

        assertThat(catalog.entries()).hasSize(2);
        RootCauseCatalogPort.Entry s1 = catalog.entries().get(0);
        assertThat(s1.component()).isEqualTo("payment");
        assertThat(s1.faultType()).isEqualTo("BUSINESS_ERROR_RATE");
        assertThat(s1.reasonCode()).isEqualTo("PAYMENT_CHARGE_FAILURE");
        assertThat(s1.description()).isEqualTo("扣款按比例失败");
        RootCauseCatalogPort.Entry s3 = catalog.entries().get(1);
        assertThat(s3.component()).isEqualTo("order-arena");
        assertThat(s3.reasonCode()).isEqualTo("DUPLICATE_CREATE_SAME_INTENT");
    }

    @Test
    @DisplayName("无组件绑定的条目跳过（诚实不完整，不臆造组件码）")
    void unboundScenarioSkipped() {
        RootCauseCatalogPort catalog =
                YamlRootCauseCatalog.parse(new StringReader(LEXICON));

        assertThat(catalog.entries())
                .noneMatch(e -> e.reasonCode().equals("ORPHAN_NO_COMPONENT"));
    }

    @Test
    @DisplayName("结构非法 → parse fail-fast（装配面由 loadOrEmpty 兜底）")
    void malformedLexiconFailsFast() {
        assertThatThrownBy(() -> YamlRootCauseCatalog.parse(new StringReader("x: 1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> YamlRootCauseCatalog.parse(new StringReader("""
                components: []
                reason_codes:
                  - fault_type: BUSINESS_ERROR_RATE
                """)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("文件缺失/解析失败 → WARN + 空码表（不抛错阻断告警主链）")
    void missingFileDegradesToEmpty() {
        RootCauseCatalogPort missing = YamlRootCauseCatalog.loadOrEmpty(
                new DefaultResourceLoader(), "classpath:eval/no-such-lexicon.yml");
        assertThat(missing.entries()).isEmpty();

        RootCauseCatalogPort unparsable = YamlRootCauseCatalog.loadOrEmpty(
                new DefaultResourceLoader(), "classpath:application.yml");
        assertThat(unparsable.entries()).isEmpty();
    }
}
