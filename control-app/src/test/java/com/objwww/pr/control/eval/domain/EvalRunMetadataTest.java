package com.objwww.pr.control.eval.domain;

import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3-14：十项可复现元数据——configDigest 重跑一致（同配置同 digest）、逐字段敏感
 * （任一项变化 digest 变化）、"未配置(null)" 与 "配置为 0" 可区分。
 * EN-09：评分器版本（grader_version）入身份面——历史分数按评分器版本归属（E11），
 * null = EN-09 前历史批次（可区分、不可冒充新版证据）。
 */
class EvalRunMetadataTest {

    private static EvalRunMetadata sample() {
        return new EvalRunMetadata(1, "eval-ds-1", Digest.sha256Of("registry"), 1,
                "deepseek-v3", "am3-rca-v2", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), new BigDecimal("0.7"), new BigDecimal("0.9"),
                4096, 123L, 123L,
                "litellm/internal-v1#key-eval", Digest.sha256Of("rules"),
                "scenario-driver-v1", "grader-v1");
    }

    @Test
    @DisplayName("重跑一致：同配置两次构造 configDigest 相同且为 64 位 sha256")
    void configDigestIsStableAcrossRebuilds() {
        Digest first = sample().configDigest();
        Digest second = sample().configDigest();

        assertThat(first.value()).isEqualTo(second.value()).hasSize(64);
    }

    @Test
    @DisplayName("逐字段敏感：任一元数据变化 → digest 变化（含可空采样参数与种子）")
    void configDigestIsFieldSensitive() {
        Digest base = sample().configDigest();

        assertThat(new EvalRunMetadata(2, "eval-ds-1", Digest.sha256Of("registry"), 1,
                "deepseek-v3", "am3-rca-v2", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), new BigDecimal("0.7"), new BigDecimal("0.9"),
                4096, 123L, 123L,
                "litellm/internal-v1#key-eval", Digest.sha256Of("rules"),
                "scenario-driver-v1", "grader-v1").configDigest().value()).isNotEqualTo(base.value());
        assertThat(new EvalRunMetadata(1, "eval-ds-2", Digest.sha256Of("registry"), 1,
                "deepseek-v3", "am3-rca-v2", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), new BigDecimal("0.7"), new BigDecimal("0.9"),
                4096, 123L, 123L,
                "litellm/internal-v1#key-eval", Digest.sha256Of("rules"),
                "scenario-driver-v1", "grader-v1").configDigest().value()).isNotEqualTo(base.value());
        assertThat(new EvalRunMetadata(1, "eval-ds-1", Digest.sha256Of("other"), 1,
                "deepseek-v3", "am3-rca-v2", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), new BigDecimal("0.7"), new BigDecimal("0.9"),
                4096, 123L, 123L,
                "litellm/internal-v1#key-eval", Digest.sha256Of("rules"),
                "scenario-driver-v1", "grader-v1").configDigest().value()).isNotEqualTo(base.value());
        assertThat(new EvalRunMetadata(1, "eval-ds-1", Digest.sha256Of("registry"), 1,
                "deepseek-v3", "am3-rca-v2", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), null, new BigDecimal("0.9"),
                4096, 123L, 123L,
                "litellm/internal-v1#key-eval", Digest.sha256Of("rules"),
                "scenario-driver-v1", "grader-v1").configDigest().value()).isNotEqualTo(base.value());
        assertThat(new EvalRunMetadata(1, "eval-ds-1", Digest.sha256Of("registry"), 1,
                "deepseek-v3", "am3-rca-v2", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), new BigDecimal("0.7"), new BigDecimal("0.9"),
                4096, 123L, 456L,
                "litellm/internal-v1#key-eval", Digest.sha256Of("rules"),
                "scenario-driver-v1", "grader-v1").configDigest().value()).isNotEqualTo(base.value());
        assertThat(new EvalRunMetadata(1, "eval-ds-1", Digest.sha256Of("registry"), 1,
                "deepseek-v3", "am3-rca-v2", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), new BigDecimal("0.7"), new BigDecimal("0.9"),
                4096, 123L, 123L,
                "litellm/internal-v1#key-other", Digest.sha256Of("rules"),
                "scenario-driver-v1", "grader-v1").configDigest().value()).isNotEqualTo(base.value());
    }

    @Test
    @DisplayName("未配置 ≠ 0：temperature null 与 0.0 的 digest 不同；数值用 toPlainString 规范化")
    void nullSamplingParamDiffersFromZero() {
        EvalRunMetadata withNull = new EvalRunMetadata(1, "d", Digest.sha256Of("r"), 1,
                "m", "p", Digest.sha256Of("pd"), Digest.sha256Of("td"),
                null, null, null, null, null,
                "pf", Digest.sha256Of("ar"), "sd", null);
        EvalRunMetadata withZero = new EvalRunMetadata(1, "d", Digest.sha256Of("r"), 1,
                "m", "p", Digest.sha256Of("pd"), Digest.sha256Of("td"),
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, null,
                "pf", Digest.sha256Of("ar"), "sd", null);

        assertThat(withNull.configDigest().value())
                .isNotEqualTo(withZero.configDigest().value());
        // 0.7 与 0.70 同值不同刻度 → toPlainString 归一后 digest 一致
        assertThat(new EvalRunMetadata(1, "d", Digest.sha256Of("r"), 1,
                "m", "p", Digest.sha256Of("pd"), Digest.sha256Of("td"),
                new BigDecimal("0.70"), null, null, null, null,
                "pf", Digest.sha256Of("ar"), "sd", null).configDigest().value())
                .isEqualTo(new EvalRunMetadata(1, "d", Digest.sha256Of("r"), 1,
                        "m", "p", Digest.sha256Of("pd"), Digest.sha256Of("td"),
                        new BigDecimal("0.7"), null, null, null, null,
                        "pf", Digest.sha256Of("ar"), "sd", null).configDigest().value());
    }

    @Test
    @DisplayName("EN-09 评分器版本：入 canonical 身份面；换版本 digest 变；null=EN-09 前历史批次可区分")
    void graderVersionEntersConfigIdentity() {
        Digest base = sample().configDigest();
        assertThat(sample().canonicalMap()).containsEntry("grader_version", "grader-v1");

        // 换评分器版本 = 换身份（E11：历史分数按版本归属，重评分另起新记录）
        Digest other = new EvalRunMetadata(1, "eval-ds-1", Digest.sha256Of("registry"), 1,
                "deepseek-v3", "am3-rca-v2", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), new BigDecimal("0.7"), new BigDecimal("0.9"),
                4096, 123L, 123L,
                "litellm/internal-v1#key-eval", Digest.sha256Of("rules"),
                "scenario-driver-v1", "grader-v2").configDigest();
        assertThat(other.value()).isNotEqualTo(base.value());

        // null（EN-09 前历史批次）与显式版本可区分（"未配置可区分"既有约定）
        EvalRunMetadata legacy = new EvalRunMetadata(1, "d", Digest.sha256Of("r"), 1,
                "m", "p", Digest.sha256Of("pd"), Digest.sha256Of("td"),
                null, null, null, null, null, "pf", Digest.sha256Of("ar"), "sd", null);
        assertThat(legacy.canonicalMap()).containsEntry("grader_version", "null");
        assertThat(new EvalRunMetadata(1, "d", Digest.sha256Of("r"), 1,
                "m", "p", Digest.sha256Of("pd"), Digest.sha256Of("td"),
                null, null, null, null, null, "pf", Digest.sha256Of("ar"), "sd", "g")
                .configDigest().value()).isNotEqualTo(legacy.configDigest().value());
    }

    @Test
    @DisplayName("防御：必填项 null / 版本号非正抛出")
    void rejectsIncompleteMetadata() {
        assertThatThrownBy(() -> new EvalRunMetadata(1, null, Digest.sha256Of("r"), 1,
                "m", "p", Digest.sha256Of("pd"), Digest.sha256Of("td"),
                null, null, null, null, null, "pf", Digest.sha256Of("ar"), "sd", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("dataset_version");
        assertThatThrownBy(() -> new EvalRunMetadata(0, "d", Digest.sha256Of("r"), 1,
                "m", "p", Digest.sha256Of("pd"), Digest.sha256Of("td"),
                null, null, null, null, null, "pf", Digest.sha256Of("ar"), "sd", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema_version");
        assertThatThrownBy(() -> new EvalRunMetadata(1, "d", Digest.sha256Of("r"), 0,
                "m", "p", Digest.sha256Of("pd"), Digest.sha256Of("td"),
                null, null, null, null, null, "pf", Digest.sha256Of("ar"), "sd", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lexicon_version");
    }
}
