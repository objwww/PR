package com.objwww.pr.control.eval.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BA-190 批件有效 run-tag 派生（2026-09-19 批件 efde9e17/44f220ef 空 tag 撞
 * uq_chaos_scenario 全灭假绿定谳）：配置非空原样；空 = "r"+evalRunId 前 12 位 hex
 * （BA-191 由 8 位加宽，降低跨批理论碰撞面），幂等、逐批唯一、字符集天然合法。
 */
class EvalRunTagsTest {

    @Test
    @DisplayName("配置 tag 非空：原样返回（与 evalRunId 无关，旧语义零漂移）")
    void configuredTagWins() {
        assertThat(EvalRunTags.effective("p6g025339", UUID.randomUUID()))
                .isEqualTo("p6g025339");
        assertThat(EvalRunTags.effective("P6-GATE", UUID.randomUUID()))
                .isEqualTo("P6-GATE");
    }

    @Test
    @DisplayName("空 tag（null/空串/空白）：按 evalRunId 派生 r+12hex，同 id 幂等")
    void blankTagDerivesFromEvalRunId() {
        UUID id = UUID.fromString("efde9e17-44f2-40ef-9abc-0123456789ab");

        assertThat(EvalRunTags.effective("", id)).isEqualTo("refde9e1744f2");
        assertThat(EvalRunTags.effective(null, id)).isEqualTo("refde9e1744f2");
        assertThat(EvalRunTags.effective("   ", id)).isEqualTo("refde9e1744f2");
        // 幂等：同 evalRunId 任何时刻派生同值（崩溃重放稳定身份 → 同 tag）
        assertThat(EvalRunTags.effective("", id))
                .isEqualTo(EvalRunTags.effective("", id));
    }

    @Test
    @DisplayName("逐批唯一：不同 evalRunId 派生不同 tag；字符集落在 scenario id 合法面")
    void distinctRunsDistinctTagsAndLegalCharset() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        String tagA = EvalRunTags.effective("", a);
        String tagB = EvalRunTags.effective("", b);

        assertThat(tagA).isNotEqualTo(tagB);
        // [a-z0-9] 子集——effectiveScenarioId 的剔字规则不会改变派生 tag
        assertThat(tagA).matches("r[0-9a-f]{12}");
        assertThat(tagA.toLowerCase().replaceAll("[^a-z0-9-]", "")).isEqualTo(tagA);
    }

    @Test
    @DisplayName("空 tag 且 evalRunId 缺失：显式失败（兜底派生无身份来源，不猜不凑）")
    void blankTagWithoutRunIdFailsClosed() {
        assertThatThrownBy(() -> EvalRunTags.effective("", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("evalRunId");
    }

    @Test
    @DisplayName("剔除后为空的配置 tag（全下划线/全中文）：按 evalRunId 派生，不绕过兜底")
    void sanitizeEmptyTagDerivesFromEvalRunId() {
        UUID id = UUID.fromString("efde9e17-44f2-40ef-9abc-0123456789ab");

        // effectiveScenarioId 的 [^a-z0-9-] 剔除会把这些值剥成空串——若只看 isBlank，
        // 注入侧 tag 剥空退化为 chaos-eval-{sid}-r{round}，撞永存台账的缝仍在
        assertThat(EvalRunTags.effective("___", id)).isEqualTo("refde9e1744f2");
        assertThat(EvalRunTags.effective("故障演练", id)).isEqualTo("refde9e1744f2");
    }

    @Test
    @DisplayName("部分合法的配置 tag：原样返回（剔除发生在注入/解析两侧同律，匹配不断裂）")
    void partiallyLegalTagPassesThrough() {
        assertThat(EvalRunTags.effective("T_01", UUID.randomUUID())).isEqualTo("T_01");
    }
}
