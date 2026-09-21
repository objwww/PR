package com.objwww.pr.control.eval.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JudgeRunMetadata 冻结记录契约（ME-T09/D09 步骤 3）：裁判模型/版本、prompt/
 * rubric/输入 digest、盲化候选名全必填；digest 须为 sha256 hex——temperature=0
 * 也不视为完全确定性，digest 是复现锚而非可选项。
 */
class JudgeRunMetadataTest {

    private static final String HEX64 = "a".repeat(64);

    @Test
    void validMetadataCarriesAllFreezeAnchors() {
        JudgeRunMetadata meta = new JudgeRunMetadata("qwen3-max", "2026-09-01",
                HEX64, "evidence-rubric-v1", "sha256:" + "b".repeat(64), HEX64, "candidate-X");

        assertThat(meta.judgeModel()).isEqualTo("qwen3-max");
        assertThat(meta.judgeModelVersion()).isEqualTo("2026-09-01");
        assertThat(meta.promptDigest()).isEqualTo(HEX64);
        assertThat(meta.rubricVersion()).isEqualTo("evidence-rubric-v1");
        assertThat(meta.rubricDigest()).isEqualTo("sha256:" + "b".repeat(64));
        assertThat(meta.inputDigest()).isEqualTo(HEX64);
        assertThat(meta.blindedCandidateLabel()).isEqualTo("candidate-X");
    }

    @Test
    void rejectsMalformedDigestAndBlankAnchors() {
        assertThatThrownBy(() -> new JudgeRunMetadata("m", "v", "not-a-digest",
                "r", HEX64, HEX64, "c")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("promptDigest");
        assertThatThrownBy(() -> new JudgeRunMetadata("m", "v", HEX64,
                "r", "ABCDEF".repeat(11), HEX64, "c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rubricDigest");
        assertThatThrownBy(() -> new JudgeRunMetadata(" ", "v", HEX64,
                "r", HEX64, HEX64, "c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JudgeRunMetadata("m", "v", HEX64,
                "r", HEX64, HEX64, " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
