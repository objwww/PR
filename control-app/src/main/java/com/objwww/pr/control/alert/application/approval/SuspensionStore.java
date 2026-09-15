package com.objwww.pr.control.alert.application.approval;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 审批挂起台账端口（PC-C3，V120 approval_suspension，§2.10）。
 */
public interface SuspensionStore {

    UUID insertSuspension(UUID runId, UUID requestId, Instant at);

    /** SUSPENDED→RESUMED CAS：落 resumed_at + human_wait 秒（wall clock 差）；false=非挂起态 */
    boolean resumeSuspension(UUID runId, Instant at);

    /** 豁免面（RunReconciler suspensionGate）：run 是否处于审批挂起 */
    boolean hasActiveSuspension(UUID runId);

    record SuspensionView(UUID suspensionId, UUID runId, UUID requestId, String state,
            Instant suspendedAt, Instant resumedAt) {
    }

    Optional<SuspensionView> latest(UUID runId);

    /** shadow 观测面：挂起次数与人等待合计 */
    record SuspensionStats(long total, long active, double humanWaitSeconds) {
    }

    SuspensionStats stats();
}
