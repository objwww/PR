package com.objwww.pr.control.ops.application;

import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 开单草稿（M5-11；Case 来源 = M4-37 Reconciler 信号/M5-12 之后的 API，本任务先立形状）。
 * snapshotDigest/observedGeneration 固定创建时快照（P4 annot：迟到证据进新快照）。
 */
public record CaseDraft(String tenant,
                        String fingerprint,
                        String subject,
                        String priority,
                        String reasonCode,
                        UUID runId,
                        String taskId,
                        String incidentType,
                        Digest snapshotDigest,
                        int observedGeneration,
                        List<String> evidenceRefs,
                        Instant ackDue,
                        Instant resolveDue,
                        String idempotencyKey) {

    public CaseDraft {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(evidenceRefs, "evidenceRefs");
        evidenceRefs = List.copyOf(evidenceRefs);
    }
}
