package com.objwww.pr.control.domain.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 账本存取端口（§4.1/§4.6/附录 C）：封闭写方法集（AFT-26）。
 *
 * <p>无通用 update/delete；条件更新幂等（0 行 = 已终态，幂等忽略）。
 */
public interface ModelCallLedgerRepository {

    /**
     * 插入 STARTED 行（触网前，D5 闸门）。
     *
     * @throws RuntimeException 写失败 → 零触网
     */
    void insertStarted(ModelCallLedgerEntry entry);

    /**
     * 条件更新为 SUCCEEDED（WHERE id=? AND state='STARTED'）。
     *
     * @return true = 更新成功；false = 0 行（已被并发终态化），幂等忽略
     */
    boolean completeTerminalSuccess(
            UUID id,
            TokenUsage usage,
            boolean usageMissing,
            String reportedModel,
            String providerRequestId,
            Duration latency,
            Long costMicros,
            String pricingVersion,
            String currency,
            Long inputPriceMicrosPerK,
            Long outputPriceMicrosPerK
    );

    /**
     * 条件更新为 FAILED（WHERE id=? AND state='STARTED'）。
     *
     * @return true = 更新成功；false = 0 行，幂等忽略
     */
    boolean completeTerminalFailure(
            UUID id,
            String outcome,
            Integer httpStatus,
            Duration retryAfter,
            Duration latency,
            String errorCode,
            String errorFingerprint,
            String sanitizedMessage
    );

    /**
     * Recovery：标记超龄 STARTED 为 UNKNOWN（§4.6）。
     *
     * @param threshold started_at < threshold 的 STARTED 行
     * @return 标记行数
     */
    int markUnknownOlderThan(Instant threshold);
}
