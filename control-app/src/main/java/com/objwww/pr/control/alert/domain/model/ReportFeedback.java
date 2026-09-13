package com.objwww.pr.control.alert.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 终态报告反馈（OP-04，V103 report_feedback append-only）：值班人员对已发布
 * 报告的评价——ACCEPTED/PARTIAL/INCORRECT/INSUFFICIENT 四值封闭（方案 §5.1）。
 *
 * <p>更正 = 新行 supersedesId 指向前序（原意见永不覆盖）；同一前序至多一条更正
 * （部分唯一索引）。作者取认证主体；reportDigest 服务端按 packageJson 计算。
 * 反馈不改报告、不触发生产处置、不改 Run 成功状态——只是回归候选的原料。
 */
public record ReportFeedback(
        UUID id,
        UUID reportId,
        UUID runId,
        String reportDigest,
        String author,
        Verdict verdict,
        String reason,
        List<String> evidenceRefs,
        UUID supersedesId,
        String idempotencyKey,
        Instant createdAt) {

    /** 评价四值：采纳/部分正确/错误/证据不足 */
    public enum Verdict {ACCEPTED, PARTIAL, INCORRECT, INSUFFICIENT}

    public ReportFeedback {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(reportDigest, "reportDigest");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(evidenceRefs, "evidenceRefs");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(createdAt, "createdAt");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("反馈理由不得为空（纠正依据可追溯）");
        }
        if (author.isBlank() || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("author/idempotencyKey 不得为空");
        }
        evidenceRefs = List.copyOf(evidenceRefs);
    }
}
