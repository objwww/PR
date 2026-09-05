package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.GoldenCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 靶场 chaos 场景驱动（M3-17，S3~S5）：eval-mgmt 私网调 ChaosController（M2-17 契约）。
 * TTL 由本轮 timing 显式给定（hold + 预热 + 余量，落 DB 约束内）；off 走 CAS，
 * 恢复确认 = 会话 CLOSED（F1/F2/F3 恢复算法收口）+ 告警无残留 firing（探针由 runner
 * 传入的 {@link AlertProbe} 判定，S3~S5 恢复判据含 {@code *_current==0} 面）。
 */
public final class ArenaChaosScenarioDriver implements ScenarioDriver {

    private final ChaosAdminClient client;
    private final AlertProbe alertProbe;
    private final String datasetVersion;

    public ArenaChaosScenarioDriver(ChaosAdminClient client, AlertProbe alertProbe,
                                    String datasetVersion) {
        this.client = Objects.requireNonNull(client);
        this.alertProbe = Objects.requireNonNull(alertProbe);
        this.datasetVersion = Objects.requireNonNull(datasetVersion);
    }

    @Override
    public ActivationReceipt activate(GoldenCase golden) {
        if (golden.chaosFamily() == null || golden.chaosFamily().isBlank()) {
            throw new IllegalArgumentException(
                    "靶场场景缺 chaos_family: " + golden.scenarioId());
        }
        int ttl = golden.timing().preheatSeconds() + golden.timing().holdSeconds()
                + golden.timing().maxResolvedWaitSeconds();
        String payloadDigest = ChaosAdminClient.actionDigest("gt-payload",
                golden.scenarioId(), golden.target(), ttl,
                golden.expectedRootCause()).value();
        String ruleDigest = alertProbe.ruleDigest(golden.expectedSymptomCodes().isEmpty()
                ? "" : golden.expectedSymptomCodes().getFirst()).value();
        Map<String, Object> body = ChaosAdminClient.activationBody(
                golden.scenarioId().toLowerCase(), golden.target(), ttl,
                ChaosAdminClient.actionDigest("config", golden.scenarioId(),
                        golden.expectedRootCause()).value(),
                datasetVersion, payloadDigest, golden.expectedAlertLabels(), ruleDigest);
        ChaosAdminClient.Activation activation = client.activate(golden.chaosFamily(), body);
        return new ActivationReceipt(golden.scenarioId(),
                ChaosAdminClient.actionDigest("activate", golden.scenarioId(),
                        activation.sessionId(), activation.generation()).value(),
                activation.generation(), activation.alertFingerprint());
    }

    @Override
    public RecoveryReceipt deactivate(GoldenCase golden, ActivationReceipt receipt) {
        List<String> unmet = new ArrayList<>();
        boolean closed = client.deactivate(golden.chaosFamily(), Map.of(
                "scenarioId", golden.scenarioId().toLowerCase(),
                "expectedGeneration", receipt.generation()));
        if (!closed) {
            unmet.add("chaos_cas_rejected");
        } else {
            boolean recovered = alertProbe.awaitSessionClosed(golden.scenarioId(),
                    golden.timing().cleanupTimeoutSeconds());
            if (!recovered) {
                unmet.add("session_not_closed_in_cleanup_window");
            }
        }
        boolean alertsResolved = alertProbe.awaitAllResolved(golden.scenarioId(),
                golden.timing().maxResolvedWaitSeconds());
        if (!alertsResolved) {
            unmet.add("alerts_still_firing");
        }
        return new RecoveryReceipt(golden.scenarioId(), receipt.actionDigest(),
                receipt.generation(), unmet.isEmpty(), alertsResolved, List.copyOf(unmet));
    }
}
