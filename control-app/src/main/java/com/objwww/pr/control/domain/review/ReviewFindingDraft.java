package com.objwww.pr.control.domain.review;

import com.objwww.pr.shared.Digest;

import java.util.Objects;

/**
 * 经工程映射后的 finding 草稿：行号已由 FindingMapper 按 existing_code 片段重新定位（不信模型行号），
 * fingerprint 已算好。落库为 review_finding 行（T2 同事务，fingerprint 唯一约束幂等）。
 */
public record ReviewFindingDraft(String filePath, int lineStart, int lineEnd,
                                 String ruleId, String severity, String message,
                                 Digest fingerprint) {

    public ReviewFindingDraft {
        Objects.requireNonNull(filePath, "filePath");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (lineStart < 1 || lineEnd < lineStart) {
            throw new IllegalArgumentException("行号范围非法: " + lineStart + "-" + lineEnd);
        }
    }
}
