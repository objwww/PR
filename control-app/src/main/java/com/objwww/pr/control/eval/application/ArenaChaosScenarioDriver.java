package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.GoldenCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

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
 *
 * <p>DR-04（方案 §7.5/§7.4）三件安全收口：①on 回执到手立即成形 {@link ActivationReceipt}
 * 身份，流量注入是其后的独立阶段；②settle 等待/流量阶段被中断或失败即抛
 * {@link ActivationException}——activate 立即退出、零后续流量，已激活会话的恢复责任
 * 随异常携带的 receipt 身份移交调用方；③恢复核验的会话面探针查有效实例 id
 * （receipt.scenarioId()，每轮派生），告警面探针保留注册表模板键并先核验
 * receipt↔模板映射，多轮/跨场景不串场。
 */
public final class ArenaChaosScenarioDriver implements ScenarioDriver {

    /** 开关读面快照轮换等待（生产固定值； ChaosSwitchboard 2s TTL + 余量，见 settleSwitchboard） */
    static final long SETTLE_SWITCHBOARD_MILLIS = 3_000;

    private final ChaosAdminClient client;
    private final AlertProbe alertProbe;
    private final ArenaTrafficClient traffic;
    private final String datasetVersion;
    private final String runTag;
    private final long settleMillis;

    public ArenaChaosScenarioDriver(ChaosAdminClient client, AlertProbe alertProbe,
                                    ArenaTrafficClient traffic, String datasetVersion,
                                    String runTag) {
        this(client, alertProbe, traffic, datasetVersion, runTag, SETTLE_SWITCHBOARD_MILLIS);
    }

    /** 测试面：settle 等待时长可注入（生产装配走五参构造，固定 {@link #SETTLE_SWITCHBOARD_MILLIS}） */
    ArenaChaosScenarioDriver(ChaosAdminClient client, AlertProbe alertProbe,
                             ArenaTrafficClient traffic, String datasetVersion,
                             String runTag, long settleMillis) {
        this.client = Objects.requireNonNull(client);
        this.alertProbe = Objects.requireNonNull(alertProbe);
        this.traffic = Objects.requireNonNull(traffic);
        this.datasetVersion = Objects.requireNonNull(datasetVersion);
        this.runTag = runTag == null ? "" : runTag;
        this.settleMillis = settleMillis;
    }

    /** 开跑前预检（防假绿门）：CHAOS_ADMIN_TOKEN 未注入时首案注入必全灭——
     *  批件在开跑前 FAILED，不允许 60 案 TIMEOUT_OR_ABSENT 后零分"SUCCEEDED" */
    @Override
    public void preflight() {
        if (!client.tokenPresent()) {
            throw new IllegalStateException("preflight 拒绝开跑：CHAOS_ADMIN_TOKEN 未注入"
                    + "（INV-AM3-3 fail-closed）——靶场注入链不可用，批件不允许假绿");
        }
    }

    /**
     * BA-190 run-tag 空值兜底：返回带本批有效 run-tag 的拷贝（传输面/探针/数据集
     * 版本共享，仅 tag 替换）——runner 批开始时把空 tag 兜底为按 evalRunId 派生的
     * 逐批唯一值，原单例（env 静态 tag）不被改写，解析侧 RcaRunResolver 同式派生。
     */
    @Override
    public ArenaChaosScenarioDriver withRunTag(String effectiveRunTag) {
        return new ArenaChaosScenarioDriver(client, alertProbe, traffic, datasetVersion,
                effectiveRunTag, settleMillis);
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
        // DR-04（方案 §7.5）债务②：on 回执到手立即成形 receipt 身份——流量注入是其后的
        // 独立阶段；中断/流量部分失败时凭此 receipt 仍可恢复已激活会话（异常携带面）。
        ActivationReceipt receipt = new ActivationReceipt(sid,
                ChaosAdminClient.actionDigest("activate", golden.scenarioId(),
                        activation.sessionId(), activation.generation()).value(),
                activation.generation(), activation.alertFingerprint());
        settleSwitchboard(receipt);
        try {
            injectTraffic(golden, sid);
        } catch (RuntimeException e) {
            throw new ActivationException("chaos 评测流量注入失败（会话已激活，凭回执恢复）: "
                    + e.getMessage(), receipt, e);
        }
        return receipt;
    }

    /**
     * 激活后先等 arena 故障开关读面（ChaosSwitchboard）的缓存轮换再注入流量：
     * 它按默认 2s TTL 缓存 ACTIVE 会话快照，激活后立即注入会撞旧快照（fail-closed
     * = 无故障）→ 同 intent 幂等重放、重复单不复现、告警永不 firing
     * （2026-09-05 E2E-M3-01 实测；3s 手动诊断复现修复）。3s ≥ 2s TTL 留余量。
     *
     * <p>DR-04（方案 §7.5/§7.4）债务①：中断必须让 activate 立即退出——恢复中断标记后
     * 抛 {@link ActivationException}（携带已激活会话的 receipt 身份），不得继续注入流量。
     */
    private void settleSwitchboard(ActivationReceipt receipt) {
        try {
            Thread.sleep(settleMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ActivationException("激活后开关读面轮换等待被中断（会话已激活）", receipt, e);
        }
    }

    /** 按故障族注入 chaos 评测流量（配方=各 ma-drill/ma-t9 脚本 195 实测家法，各 case
     *  行注出处；F1 同 intent 三单；F9（BA-178）3 单创+付——capture 成功事实落库后
     *  故障点吞掉 markPaid 收口；F10~F17（BA-179）接线八族；未接线族如实抛错
     *  （不静默降级成"只开故障不注流量"的空转）） */
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
            case "F9" -> {
                for (int i = 1; i <= 3; i++) {
                    String orderId = traffic.createOrder(
                            sid + "-intent-" + i, sid + "-" + i, "sku-std");
                    if (orderId != null) {
                        traffic.payOrder(orderId, sid + "-intent-" + i);
                    }
                }
            }
            // ma-drill-s17.sh：25 单仅创单不支付——AUTH 沉默 INITIATED、订单停 CREATED
            // 积压（>20 持续 5m 触 ArenaPendingPaymentBacklog ticket）
            case "F10" -> {
                for (int i = 1; i <= 25; i++) {
                    traffic.createOrder(sid + "-intent-" + i, sid + "-" + i, "sku-std");
                }
            }
            // ma-drill-s18.sh：创单→支付→同单同 correlationId 再支付——故障点跳过
            // 已支付闸放行重复 CAPTURE
            case "F11" -> {
                String orderId = traffic.createOrder(sid + "-intent-1", sid + "-1", "sku-std");
                if (orderId != null) {
                    traffic.payOrder(orderId, sid + "-intent-1");
                    traffic.payOrder(orderId, sid + "-intent-1");
                }
            }
            // ma-drill-s19.sh：1 单创+付——DISCOUNT 扣减行被吞，三方对账数量差
            case "F12" -> createAndPayOnce(sid);
            // ma-t9-s20.sh：1 单创+付——补插超额 INVENTORY DEDUCT 行，负库存超卖
            case "F13" -> createAndPayOnce(sid);
            // ma-t9-s21.sh：25 单仅创单——履约行被吞（orders 与 fulfillments_started
            // counter 10m 差值 >10 触 ArenaFulfillmentGap ticket）
            case "F14" -> {
                for (int i = 1; i <= 25; i++) {
                    traffic.createOrder(sid + "-intent-" + i, sid + "-" + i, "sku-std");
                }
            }
            // ma-t9-s22.sh：1 单创+付——消费端对已履约单重复插 attempt 行
            case "F15" -> createAndPayOnce(sid);
            // ma-t9-s24.sh：25 次创单突发——入口静默 accepted 零落单（createOrder 返回
            // null 即受理无单，不支付）；脚本 off 后 5 单补创属恢复验证面，非注入配方
            case "F16" -> {
                for (int i = 1; i <= 25; i++) {
                    traffic.createOrder(sid + "-intent-" + i, sid + "-" + i, "sku-std");
                }
            }
            // ma-t9-s25.sh：16 单仅创单——履约停 CONFIRMING 不推进，SLA 超时积压 >15
            case "F17" -> {
                for (int i = 1; i <= 16; i++) {
                    traffic.createOrder(sid + "-intent-" + i, sid + "-" + i, "sku-std");
                }
            }
            default -> throw new IllegalArgumentException(
                    "未知靶场故障族: " + golden.chaosFamily());
        }
    }

    /** 单创+付配方（F12/F13/F15 同构：故障点在记账/库存/消费侧，流量面同为 1 单创+付） */
    private void createAndPayOnce(String sid) {
        String orderId = traffic.createOrder(sid + "-intent-1", sid + "-1", "sku-std");
        if (orderId != null) {
            traffic.payOrder(orderId, sid + "-intent-1");
        }
    }

    @Override
    public RecoveryReceipt deactivate(GoldenCase golden, ActivationReceipt receipt) {
        List<String> unmet = new ArrayList<>();
        // DR-04（方案 §7.5）债务③映射核验：回执必须携带本模板派生的有效实例 id
        // （chaos-eval-[tag-]{template}-r{round}，与 effectiveScenarioId 同式）——
        // 跨场景/跨轮回执不进本场次的恢复通路（不抛半途，落 unmet 回执）。
        if (!receiptMatchesTemplate(receipt.scenarioId(), golden)) {
            unmet.add("receipt_scenario_mismatch:" + receipt.scenarioId());
            return new RecoveryReceipt(golden.scenarioId(), receipt.actionDigest(),
                    receipt.generation(), false, false, List.copyOf(unmet));
        }
        boolean closed = client.deactivate(golden.chaosFamily(), Map.of(
                "scenarioId", receipt.scenarioId(),
                "expectedGeneration", receipt.generation()));
        if (!closed) {
            unmet.add("chaos_cas_rejected");
        } else {
            // 债务③：会话在 chaos 管理面按每轮派生的有效实例 id 登记（模板 id 无此
            // 会话）——收口探针查 receipt 的有效实例 id，不用模板 golden.scenarioId()。
            boolean recovered = alertProbe.awaitSessionClosed(receipt.scenarioId(),
                    golden.timing().cleanupTimeoutSeconds());
            if (!recovered) {
                unmet.add("session_not_closed_in_cleanup_window");
            }
        }
        // 债务③探针契约追踪：awaitAllResolved 的 scenarioId 是注册表模板键
        // （PrometheusAlertProbe 经 registry.byScenarioId 解析期望 alertname 集，
        // 有效实例 id 不在注册表；告警查询面无 per-round 场次维度，C-6 激活标签
        // 轮次无关）——探针无法把模板键映射到有效实例，告警面核验保留模板键，
        // 映射正确性由上面的 receiptMatchesTemplate 核验。
        boolean alertsResolved = alertProbe.awaitAllResolved(golden.scenarioId(),
                golden.timing().maxResolvedWaitSeconds());
        if (!alertsResolved) {
            unmet.add("alerts_still_firing");
        }
        return new RecoveryReceipt(golden.scenarioId(), receipt.actionDigest(),
                receipt.generation(), unmet.isEmpty(), alertsResolved, List.copyOf(unmet));
    }

    /** 回执的有效实例 id 是否本模板派生（chaos-eval-[tag-]{template}-r{round} 精确式） */
    private static boolean receiptMatchesTemplate(String receiptScenarioId, GoldenCase golden) {
        String template = Pattern.quote(golden.scenarioId().toLowerCase());
        return receiptScenarioId != null && receiptScenarioId
                .matches("chaos-eval-(?:[a-z0-9-]+-)?" + template + "-r[0-9]+");
    }

    /**
     * 激活半途退出（中断/流量失败）时携带的回执身份（DR-04，方案 §7.5）：
     * 会话已在 chaos 管理面激活，调用方凭 {@link #receipt()} 走恢复通路
     * （off CAS 需 scenarioId + expectedGeneration）。
     */
    public static final class ActivationException extends RuntimeException {

        private final ActivationReceipt receipt;

        public ActivationException(String message, ActivationReceipt receipt, Throwable cause) {
            super(message, cause);
            this.receipt = Objects.requireNonNull(receipt);
        }

        /** 已激活会话的回执身份（中断/流量失败现场不丢，凭此恢复） */
        public ActivationReceipt receipt() {
            return receipt;
        }
    }
}
