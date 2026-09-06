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
 *
 * <p>M3-30 部署门实测修正两件：①每轮派生独立 scenario_id（uq_chaos_scenario 全局唯一，
 * 两轮同名第二轮必撞唯一约束）——{@link #effectiveScenarioId} = chaos-eval-{sid}-r{round}，
 * 激活/解除/解析三面共用同一派生；②激活后按故障族注入 chaos 前缀评测流量
 * （{@link ArenaTrafficClient}，recipe 与 AM2 E2E 驱动一致）——无流量则 F1~F3 Gauge
 * 恒零，期望告警永不 firing。流量创单失败向上抛（runner 落 activate_failed）。
 */
public final class ArenaChaosScenarioDriver implements ScenarioDriver {

    private final ChaosAdminClient client;
    private final AlertProbe alertProbe;
    private final ArenaTrafficClient traffic;
    private final String datasetVersion;
    private final String runTag;

    public ArenaChaosScenarioDriver(ChaosAdminClient client, AlertProbe alertProbe,
                                    ArenaTrafficClient traffic, String datasetVersion,
                                    String runTag) {
        this.client = Objects.requireNonNull(client);
        this.alertProbe = Objects.requireNonNull(alertProbe);
        this.traffic = Objects.requireNonNull(traffic);
        this.datasetVersion = Objects.requireNonNull(datasetVersion);
        this.runTag = runTag == null ? "" : runTag;
    }

    /**
     * 每轮独立靶场场景 id（全局唯一约束面）；解析侧（RcaRunResolver）同式派生。
     * uq_chaos_scenario 是永存台账——重跑同批必须换 run-tag（启动方注入，逐批唯一），
     * 否则二次激活必撞唯一约束。id 形态约束 [a-z0-9][a-z0-9-]{2,63}，run-tag 非法
     * 字符直接剔除。
     */
    public static String effectiveScenarioId(GoldenCase golden, int roundNo, String runTag) {
        String tag = runTag == null ? ""
                : runTag.toLowerCase().replaceAll("[^a-z0-9-]", "");
        return "chaos-eval-" + (tag.isEmpty() ? "" : tag + "-")
                + golden.scenarioId().toLowerCase() + "-r" + roundNo;
    }

    /** arena.yml 冻结的靶场告警基础标签（C-6 指纹输入，AM2 实测冻结集） */
    private static final Map<String, String> ARENA_BASE_LABELS = Map.of(
            "severity", "page",
            "service", "order-arena",
            "job", "order-arena",
            "instance", "order-arena:8080");

    /** C-6 激活标签 = 基础标签 + alertname + 故障族（与 AM2 E2E 六标签冻结集一致） */
    private static Map<String, String> activationLabels(GoldenCase golden) {
        Map<String, String> labels = new java.util.LinkedHashMap<>(ARENA_BASE_LABELS);
        labels.put("alertname", golden.expectedSymptomCodes().isEmpty()
                ? "" : golden.expectedSymptomCodes().getFirst());
        labels.put("fault_type", golden.chaosFamily());
        labels.putAll(golden.expectedAlertLabels());
        return labels;
    }

    @Override
    public ActivationReceipt activate(GoldenCase golden, int roundNo) {
        if (golden.chaosFamily() == null || golden.chaosFamily().isBlank()) {
            throw new IllegalArgumentException(
                    "靶场场景缺 chaos_family: " + golden.scenarioId());
        }
        String sid = effectiveScenarioId(golden, roundNo, runTag);
        int ttl = golden.timing().preheatSeconds() + golden.timing().holdSeconds()
                + golden.timing().maxResolvedWaitSeconds();
        String payloadDigest = ChaosAdminClient.actionDigest("gt-payload",
                golden.scenarioId(), golden.target(), ttl,
                golden.expectedRootCause()).value();
        String ruleDigest = alertProbe.ruleDigest(golden.expectedSymptomCodes().isEmpty()
                ? "" : golden.expectedSymptomCodes().getFirst()).value();
        Map<String, Object> body = ChaosAdminClient.activationBody(
                sid, sid, ttl,
                ChaosAdminClient.actionDigest("config", golden.scenarioId(),
                        golden.expectedRootCause()).value(),
                datasetVersion, payloadDigest, activationLabels(golden), ruleDigest);
        ChaosAdminClient.Activation activation = client.activate(golden.chaosFamily(), body);
        settleSwitchboard();
        injectTraffic(golden, sid);
        return new ActivationReceipt(sid,
                ChaosAdminClient.actionDigest("activate", golden.scenarioId(),
                        activation.sessionId(), activation.generation()).value(),
                activation.generation(), activation.alertFingerprint());
    }

    /**
     * 激活后先等 arena 故障开关读面（ChaosSwitchboard）的缓存轮换再注入流量：
     * 它按默认 2s TTL 缓存 ACTIVE 会话快照，激活后立即注入会撞旧快照（fail-closed
     * = 无故障）→ 同 intent 幂等重放、重复单不复现、告警永不 firing
     * （2026-09-05 E2E-M3-01 实测；3s 手动诊断复现修复）。3s ≥ 2s TTL 留余量。
     */
    private void settleSwitchboard() {
        try {
            Thread.sleep(3_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 按故障族注入 chaos 评测流量（AM2 E2E 驱动同款 recipe；F1 同 intent 三单） */
    private void injectTraffic(GoldenCase golden, String sid) {
        switch (golden.chaosFamily()) {
            case "F1" -> {
                traffic.createOrder(sid + "-intent-1", sid + "-a", "sku-std");
                traffic.createOrder(sid + "-intent-1", sid + "-b", "sku-std");
                traffic.createOrder(sid + "-intent-1", sid + "-c", "sku-std");
            }
            case "F2" -> traffic.createOrder(sid + "-intent-1", sid + "-1", "sku-std");
            case "F3" -> {
                traffic.createOrder(sid + "-intent-1", sid + "-1", "sku-std");
                traffic.createOrder(sid + "-intent-2", sid + "-2", "sku-x-latesuccess");
            }
            default -> throw new IllegalArgumentException(
                    "未知靶场故障族: " + golden.chaosFamily());
        }
    }

    @Override
    public RecoveryReceipt deactivate(GoldenCase golden, ActivationReceipt receipt) {
        List<String> unmet = new ArrayList<>();
        boolean closed = client.deactivate(golden.chaosFamily(), Map.of(
                "scenarioId", receipt.scenarioId(),
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
