package com.objwww.pr.control.alert.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写作规约共享检出单测（BA-177）：两种把握锚（agent 原文硬措辞 / BA-175 确定性摘要
 * 「结论置信度：高/中/未定论」）+ PROMPT_SECTION 与检出锚同源锁定（段内必须含
 * 「把握：HIGH」硬措辞字样，防 prompt 文本改了检出锚没改）。
 */
class ReportWritingRubricTest {

    @Test
    void phraseAnchorDetectsAllThreeLevels() {
        assertThat(ReportWritingRubric.confidenceLevelOf("……把握：HIGH（多源一致且机理通顺）"))
                .contains("HIGH");
        assertThat(ReportWritingRubric.confidenceLevelOf("把握:MEDIUM（多源一致）"))
                .contains("MEDIUM");
        assertThat(ReportWritingRubric.confidenceLevelOf("把握： LOW（单源证据）"))
                .contains("LOW");
    }

    @Test
    void deterministicSummaryAnchorMapsToLevels() {
        assertThat(ReportWritingRubric.confidenceLevelOf(
                "确认根因：……。结论置信度：高——全部断言闭环，可据以处置。")).contains("HIGH");
        assertThat(ReportWritingRubric.confidenceLevelOf(
                "结论置信度：中——仍有 2 条断言未闭环，建议补充取证后再处置")).contains("MEDIUM");
        assertThat(ReportWritingRubric.confidenceLevelOf(
                "结论置信度：未定论——无确认根因，不建议据此处置")).contains("LOW");
    }

    @Test
    void noAnchorIsEmptyNotGuessed() {
        assertThat(ReportWritingRubric.confidenceLevelOf("无把握措辞的正文")).isEmpty();
        assertThat(ReportWritingRubric.confidenceLevelOf(null)).isEmpty();
        assertThat(ReportWritingRubric.confidenceLevelOf("  ")).isEmpty();
    }

    @Test
    void phraseAnchorWinsWhenBothPresent() {
        assertThat(ReportWritingRubric.confidenceLevelOf(
                "把握：LOW（单源证据）……结论置信度：高——")).contains("LOW");
    }

    @Test
    void promptSectionCarriesSixElementsAndConfidenceAnchor() {
        String section = ReportWritingRubric.PROMPT_SECTION;
        // 六要素叙事链 + v9 三增量（把握硬措辞/反例/禁黑话）在场锁定
        assertThat(section).contains("发生了什么", "根因是什么", "凭什么判断", "影响多大",
                "有多大把握", "建议怎么办");
        assertThat(section).contains("把握：HIGH", "把握：MEDIUM", "把握：LOW");
        assertThat(section).contains("反例（不合格，出现即返工）");
        assertThat(section).contains("禁黑话");
        // 检出锚与 prompt 文本同源：段内硬措辞必须能被共享检出认下
        assertThat(ReportWritingRubric.confidenceLevelOf(section)).contains("HIGH");
    }
}
