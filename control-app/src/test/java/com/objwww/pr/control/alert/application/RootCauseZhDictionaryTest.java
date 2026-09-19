package com.objwww.pr.control.alert.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RootCauseZhDictionary UT：三张表覆盖与 synonym-lexicon-v1.yml（lexicon_version 2，
 * 15 条 fault_type）/ scenarioZh.js ALERTNAME_ZH 对齐；未命中一律回退原文/null，
 * 绝不猜测翻译。
 */
class RootCauseZhDictionaryTest {

    /** lexicon_version 2 全量 15 条 fault_type（UPPER_SNAKE 5 + business.* 10） */
    private static final List<String> ALL_FAULT_TYPES = List.of(
            "BUSINESS_ERROR_RATE", "DEPENDENCY_UNREACHABLE", "IDEMPOTENCY_BYPASS",
            "ILLEGAL_STATE_TRANSITION", "UNCERTAIN_TIMEOUT",
            "business.order_lost", "business.payment_pending", "business.duplicate_charge",
            "business.reconciliation_mismatch", "business.inventory_oversell",
            "business.event_lost", "business.duplicate_fulfillment",
            "business.kpi_degradation", "business.zero_flow", "business.fulfillment_timeout");

    @Test
    @DisplayName("fault_type 15 条全覆盖：中文名/解释/通用处置方向三件齐全")
    void faultTypeCoversAllLexiconV2Codes() {
        assertThat(ALL_FAULT_TYPES).hasSize(15);
        for (String code : ALL_FAULT_TYPES) {
            RootCauseZhDictionary.FaultTypeZh zh = RootCauseZhDictionary.faultType(code);
            assertThat(zh).as("词典命中 " + code).isNotNull();
            assertThat(zh.name()).as(code + " 中文名").isNotBlank();
            assertThat(zh.explanation()).as(code + " 一句话解释").isNotBlank();
            assertThat(zh.remediation()).as(code + " 通用处置方向").isNotBlank();
        }
        assertThat(RootCauseZhDictionary.faultTypeName("IDEMPOTENCY_BYPASS"))
                .isEqualTo("幂等失效");
        assertThat(RootCauseZhDictionary.faultTypeName("business.duplicate_charge"))
                .isEqualTo("重复扣款");
    }

    @Test
    @DisplayName("未命中回退：faultType→null、name 面→原文码（不猜测翻译）")
    void unknownCodesFallBackToRaw() {
        assertThat(RootCauseZhDictionary.faultType("DISK_FULL")).isNull();
        assertThat(RootCauseZhDictionary.faultType(null)).isNull();
        assertThat(RootCauseZhDictionary.faultTypeName("DISK_FULL")).isEqualTo("DISK_FULL");
        assertThat(RootCauseZhDictionary.symptomName("UnknownAlert")).isEqualTo("UnknownAlert");
        assertThat(RootCauseZhDictionary.componentName("svc-x")).isEqualTo("svc-x");
        assertThat(RootCauseZhDictionary.sourceName("kafka")).isEqualTo("kafka");
    }

    @Test
    @DisplayName("症状/组件/来源翻译钉（与 scenarioZh.js、lexicon components 节同义）")
    void symptomComponentSourceTranslations() {
        assertThat(RootCauseZhDictionary.symptomName("ArenaDuplicateOrders"))
                .isEqualTo("订单重复创建");
        assertThat(RootCauseZhDictionary.symptomName("checkout")).isEqualTo("结算可用性烧损");
        assertThat(RootCauseZhDictionary.componentName("payment")).isEqualTo("支付服务");
        assertThat(RootCauseZhDictionary.componentName("order-arena")).isEqualTo("订单服务");
        assertThat(RootCauseZhDictionary.componentName("checkout")).isEqualTo("结算服务");
        assertThat(RootCauseZhDictionary.sourceName("prometheus")).isEqualTo("指标");
        assertThat(RootCauseZhDictionary.sourceName("logs")).isEqualTo("日志");
        assertThat(RootCauseZhDictionary.sourceName("change")).isEqualTo("变更记录");
    }
}
