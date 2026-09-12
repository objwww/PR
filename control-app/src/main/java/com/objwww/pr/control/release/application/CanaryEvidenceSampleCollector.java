package com.objwww.pr.control.release.application;

import com.objwww.pr.control.release.domain.repository.CanaryEvidenceSampleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Canary 采集适配器（B4，M6-01"真实晋升证据由长窗采集器生成"落地面）：NATIVE run
 * 收尾链挂点的唯一样本写入方——生产 provenance 可追溯（source=inbox + 可追 run），
 * 禁脚本补数（INV-AM6-9）。
 *
 * <p>分级纪律：LIVE_CANARY 仅生产路径（provenance.source=inbox 且 runId 在场）；
 * provenance 不满足生产可追溯 → 拒绝 LIVE（IllegalArgumentException，不降级不造数）。
 * DRILL/REPLAY 由各自既有路径带对应分级显式调用。evaluator 仅数 LIVE（INV-AM6-5）。
 *
 * <p>样本写入失败不阻断调查收尾（调用方 try/catch log-warn——采集是观测面，
 * 不是调查链的事务组成部分）。
 */
public class CanaryEvidenceSampleCollector {

    private static final Logger log = LoggerFactory.getLogger(CanaryEvidenceSampleCollector.class);

    /** 生产事实溯源标记（V30 provenance_json 契约：LIVE 必须可追到生产事实） */
    public static final String PROVENANCE_SOURCE_INBOX = "inbox";

    private final CanaryEvidenceSampleRepository samples;
    private final Clock clock;

    public CanaryEvidenceSampleCollector(CanaryEvidenceSampleRepository samples, Clock clock) {
        this.samples = Objects.requireNonNull(samples, "samples");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** LIVE 采集入参（run 收尾链投影；observed = 原始指标不预聚合） */
    public record LiveObservation(UUID runId, UUID incidentId, String stickinessKey,
                                  Map<String, Object> provenance, Map<String, Object> observed) {
    }

    /**
     * 生产路径 LIVE_CANARY 采集（NATIVE run 收尾链唯一入口）。provenance 必带
     * source=inbox（生产事实）；缺溯源即拒（INV-AM6-9 禁脚本补数）。
     * @return false = 该 run 已有样本（幂等重放）
     */
    public boolean collectLive(LiveObservation observation) {
        Objects.requireNonNull(observation, "observation");
        requireLiveProvenance(observation.provenance());
        if (observation.runId() == null || observation.incidentId() == null) {
            throw new IllegalArgumentException(
                    "LIVE 样本必须可追（runId/incidentId 在场）——INV-AM6-9");
        }
        boolean inserted = samples.insert(new CanaryEvidenceSampleRepository.SampleRow(
                observation.runId(), observation.incidentId(), observation.stickinessKey(),
                "LIVE_CANARY", copy(observation.provenance()), copy(observation.observed()),
                clock.instant()));
        if (inserted) {
            log.info("canary LIVE 样本落档：run={} incident={}",
                    observation.runId(), observation.incidentId());
        }
        return inserted;
    }

    /** DRILL/REPLAY 既有路径采集（显式分级；INV-AM6-5：仅记录，Evaluator 不计入晋升） */
    public boolean collectNonLive(String evidenceClass, UUID runId, UUID incidentId,
            String stickinessKey, Map<String, Object> provenance,
            Map<String, Object> observed) {
        if (!"DRILL".equals(evidenceClass) && !"REPLAY".equals(evidenceClass)) {
            throw new IllegalArgumentException(
                    "非 LIVE 分级只接受 DRILL/REPLAY（LIVE 走 collectLive 生产门）: " + evidenceClass);
        }
        return samples.insert(new CanaryEvidenceSampleRepository.SampleRow(
                runId, incidentId, stickinessKey, evidenceClass,
                copy(provenance), copy(observed), clock.instant()));
    }

    /** LIVE 溯源门：provenance.source 必为 inbox（生产事实），防脚本补数 */
    private static void requireLiveProvenance(Map<String, Object> provenance) {
        Object source = provenance == null ? null : provenance.get("source");
        if (!PROVENANCE_SOURCE_INBOX.equals(source)) {
            throw new IllegalArgumentException(
                    "LIVE_CANARY 需生产事实溯源（provenance.source=inbox，INV-AM6-9 禁脚本补数），"
                            + "实际: " + source);
        }
    }

    private static Map<String, Object> copy(Map<String, Object> payload) {
        return payload == null ? Map.of() : new LinkedHashMap<>(payload);
    }
}
