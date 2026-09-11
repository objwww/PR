package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.domain.ai.ModelCallLedgerEntry;
import com.objwww.pr.control.domain.ai.ModelCallLedgerRepository;
import com.objwww.pr.control.domain.ai.TokenUsage;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 平台模型账本旁路（BA-109 裁定：RCA 侧独立账本山）。
 *
 * <p>平台模型账本（V5 建表）三列 {@code review_run_id/run_step_id/
 * attempt_id} NOT NULL + FK 深绑 PR 域 review_run/run_step/step_attempt——RCA 调用
 * 以 rca_run/task/attempt id 填入必然 23503（195 真窗 A0 实证）。放松约束=削弱 PR 域
 * 完整性且混域双账，裁定为负；RCA 调用唯一账本山 = {@code rca_model_call}（V48，
 * invocation_id/provider_request_id/usage/cost 列齐全），其「账本不可写=零触网」
 * D5 等价门在 {@code RcaModelGateway.call} 的 ledger.open 先行落账。
 *
 * <p>本实现仅用于 RCA 面装配（AlertAm4Config#am4RcaModelGateway）：平台
 * {@code ModelGateway} 的路由/重试/熔断/预算面全量保留，仅平台账本写入旁路。
 * PR 域（评审链路）必须继续装配 {@link PostgresModelCallLedgerRepository}——
 * 本类对 PR 域使用即治理面失效，禁止。
 */
public class NoOpModelCallLedgerRepository implements ModelCallLedgerRepository {

    @Override
    public void insertStarted(ModelCallLedgerEntry entry) {
        // 旁路：RCA 面 STARTED 行不落平台账本（RCA 账本 rca_model_call PENDING 已先行）
    }

    @Override
    public boolean completeTerminalSuccess(UUID id, TokenUsage usage, boolean usageMissing,
            String reportedModel, String providerRequestId, Duration latency,
            Long costMicros, String pricingVersion, String currency,
            Long inputPriceMicrosPerK, Long outputPriceMicrosPerK) {
        return true;
    }

    @Override
    public boolean completeTerminalFailure(UUID id, String outcome, Integer httpStatus,
            Duration retryAfter, Duration latency, String errorCode,
            String errorFingerprint, String sanitizedMessage) {
        return true;
    }

    @Override
    public int markUnknownOlderThan(Instant threshold) {
        return 0;
    }
}
