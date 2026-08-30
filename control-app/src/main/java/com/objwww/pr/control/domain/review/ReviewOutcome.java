package com.objwww.pr.control.domain.review;

import com.objwww.pr.control.domain.ai.TokenUsage;

import java.util.List;
import java.util.Objects;

/**
 * 一轮评审的产出：工程映射后的 findings + 全量统计（丢弃/截断/畸形都必须记数，
 * "确定性清单"原则——不存在悄悄不看的文件或悄悄丢弃的发现）。
 */
public record ReviewOutcome(List<ReviewFindingDraft> findings,
                            int droppedFindings,
                            int malformedFindings,
                            int candidateFiles,
                            int selectedFiles,
                            int truncatedFiles,
                            TokenUsage tokenUsage) {

    public ReviewOutcome {
        findings = List.copyOf(Objects.requireNonNull(findings));
        Objects.requireNonNull(tokenUsage, "tokenUsage");
        if (droppedFindings < 0 || malformedFindings < 0 || candidateFiles < 0
                || selectedFiles < 0 || truncatedFiles < 0) {
            throw new IllegalArgumentException("统计计数不能为负");
        }
    }
}
