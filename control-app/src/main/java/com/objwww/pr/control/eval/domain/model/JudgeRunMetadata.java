package com.objwww.pr.control.eval.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 裁判运行元数据冻结记录（ME-T09/D09 步骤 3；纯数据，L0 零框架依赖）。
 *
 * <p>一次裁判运行的复现锚：裁判模型/版本、prompt digest、rubric 版本/digest、
 * 输入 digest、盲化候选名——落档后任何一致率/偏差统计都可回指同一锚。
 * temperature=0 也不视为完全确定性，因此 digest 是冻结面而非可选项：
 * prompt/rubric/输入任一变化 = 新的 digest = 新一次运行，旧记录不改写。
 * 盲化候选名（{@link #blindedCandidateLabel}）承载 A/B 匿名标签，位置交换
 * 一致性检查（{@code PositionBiasCheck}）按此标签对账。
 */
public record JudgeRunMetadata(String judgeModel,
                               String judgeModelVersion,
                               String promptDigest,
                               String rubricVersion,
                               String rubricDigest,
                               String inputDigest,
                               String blindedCandidateLabel) {

    /** digest 契约：小写 sha256 hex（64 位），可带 "sha256:" 前缀 */
    private static final Pattern DIGEST = Pattern.compile("^(sha256:)?[0-9a-f]{64}$");

    public JudgeRunMetadata {
        judgeModel = nonBlank(judgeModel, "judgeModel");
        judgeModelVersion = nonBlank(judgeModelVersion, "judgeModelVersion");
        rubricVersion = nonBlank(rubricVersion, "rubricVersion");
        blindedCandidateLabel = nonBlank(blindedCandidateLabel, "blindedCandidateLabel");
        promptDigest = digest(promptDigest, "promptDigest");
        rubricDigest = digest(rubricDigest, "rubricDigest");
        inputDigest = digest(inputDigest, "inputDigest");
    }

    private static String nonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " 不得为 null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为 blank");
        }
        return value;
    }

    private static String digest(String value, String field) {
        nonBlank(value, field);
        if (!DIGEST.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " 须为 sha256 hex（64 位，"
                    + "可带 sha256: 前缀）: " + value);
        }
        return value;
    }
}
