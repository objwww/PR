package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.review.ReviewOutcome;
import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.Objects;

/**
 * Step 执行结果（T2 输入；sealed 二选一）。
 */
public sealed interface StepOutcome {

    /** 成功：output digest + 评审产出（REVIEW step；其他 step 类型 reviewOutcome 传 null） */
    record Succeeded(Digest outputArtifactDigest, ReviewOutcome reviewOutcome) implements StepOutcome {
        public Succeeded {
            Objects.requireNonNull(outputArtifactDigest, "outputArtifactDigest");
        }
    }

    /** 失败：retryable=false 或预算耗尽 → Step FAILED；retryable=true 且预算未尽 → RETRY_WAIT 退避
     *  M3 新增：notBefore 支持 durable defer（Retry-After → available_at）*/
    record Failed(String errorClass, String errorCode, String errorDetail,
                  boolean retryable, Instant notBefore) implements StepOutcome {
        public Failed {
            Objects.requireNonNull(errorClass, "errorClass");
            // notBefore 可为 null（立即重试）
        }

        /** 附录C冻结的4参重载（兼容既有调用点，notBefore默认null） */
        public Failed(String errorClass, String errorCode, String errorDetail, boolean retryable) {
            this(errorClass, errorCode, errorDetail, retryable, null);
        }
    }
}
