package com.objwww.pr.arena.application;

import com.objwww.pr.arena.infrastructure.persistence.PostgresProbeStore;
import com.objwww.pr.shared.Digest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 领域探针（M2-21，C-7 冻结语义）：
 * <ul>
 *   <li>每次扫描 = 事实违规查询 + episode 台账同步：违规出现→开 episode（检测计数 +1）、
 *       连续在→续（只碰 last_seen，<b>同问题连续扫描只计一次</b>）、消失→关、
 *       复发→<b>新 episode_no</b>（重新计一次）；</li>
 *   <li>gauge = 打开 episode 数；counter = 累计 episode 数；</li>
 *   <li>DB 失败：<b>保留末值、probe_up=0、绝不归零业务 gauge</b>——
 *       探测失明 ≠ 世界恢复（C-7 硬约束）；失明时长由
 *       oa_domain_probe_last_success_timestamp 的陈旧度暴露。</li>
 * </ul>
 */
public class DomainProbe {

    private static final Logger log = LoggerFactory.getLogger(DomainProbe.class);

    public static final String STUCK = "STUCK_ORDER";
    public static final String DUPLICATE = "DUPLICATE_ORDER";
    public static final String STATE_VIOLATION = "STATE_VIOLATION";

    /** @param ok false = 本轮失败（保留末值语义生效） */
    public record ScanResult(boolean ok, int stuck, int duplicates, int stateViolations) {
    }

    private final PostgresProbeStore store;
    private final int stuckThresholdSeconds;
    private final MeterRegistry registry;
    private final Map<String, Counter> detectedCounters = new HashMap<>();
    /** C-7 末值面：type → 最近一次成功扫描的打开 episode 数 */
    private final Map<String, Long> lastOpenCounts = new ConcurrentHashMap<>();
    private volatile boolean probeUp = true;
    private volatile double lastSuccessEpoch = 0;

    public DomainProbe(PostgresProbeStore store, int stuckThresholdSeconds,
                       MeterRegistry registry) {
        this.store = store;
        this.stuckThresholdSeconds = stuckThresholdSeconds;
        this.registry = registry;
        registerGauges();
    }

    private void registerGauges() {
        Gauge.builder("oa_stuck_orders_current", () -> openCount(STUCK))
                .description("当前打开的卡单 episode 数（F3 症状）").register(registry);
        Gauge.builder("oa_duplicate_orders_current", () -> openCount(DUPLICATE))
                .description("当前打开的重复单 episode 数（F1 症状）").register(registry);
        Gauge.builder("oa_state_violations_current", () -> openCount(STATE_VIOLATION))
                .description("当前打开的状态违规 episode 数（F2 症状）").register(registry);
        Gauge.builder("oa_domain_probe_up", () -> probeUp ? 1.0 : 0.0)
                .description("领域探针自证（1=上轮成功）").register(registry);
        Gauge.builder("oa_domain_probe_last_success_timestamp", () -> lastSuccessEpoch)
                .description("最近一次成功扫描的 epoch 秒（陈旧度 = 探测失明时长）")
                .register(registry);
    }

    private double openCount(String type) {
        return lastOpenCounts.getOrDefault(type, 0L);
    }

    private Counter detectedCounter(String type, String metricName) {
        return detectedCounters.computeIfAbsent(type,
                t -> registry.counter(metricName));
    }

    /** 一轮探测（异常不外抛：C-7 失败语义在内部收口） */
    public ScanResult scanOnce() {
        try {
            int stuck = sync(STUCK, store.stuckOrders(stuckThresholdSeconds),
                    "oa_stuck_orders_detected");
            int dups = sync(DUPLICATE, store.duplicateOrders(),
                    "oa_duplicate_orders_detected");
            int states = sync(STATE_VIOLATION, store.stateViolations(),
                    "oa_state_violations_detected");
            probeUp = true;
            lastSuccessEpoch = Instant.now().getEpochSecond();
            return new ScanResult(true, stuck, dups, states);
        } catch (DataAccessException e) {
            // C-7：保留末值 + probe_down；业务 gauge 一字不动
            probeUp = false;
            log.warn("领域探针失明（保留末值，不伪装恢复）: {}", e.getMostSpecificCause().getMessage());
            return new ScanResult(false,
                    (int) openCount(STUCK), (int) openCount(DUPLICATE),
                    (int) openCount(STATE_VIOLATION));
        }
    }

    /** 同步一类 episode；@return 同步后的打开数（= 当前违规事实数） */
    private int sync(String type, List<PostgresProbeStore.Violation> violations,
                     String counterName) {
        Map<String, String> wanted = new HashMap<>();
        for (var v : violations) {
            wanted.put(v.entityId(),
                    Digest.sha256Of(v.variant() + "|" + v.entityId()).value());
        }
        Map<String, PostgresProbeStore.OpenFinding> open = store.openFindings(type);
        long newEpisodes = 0;
        for (var entry : wanted.entrySet()) {
            if (open.containsKey(entry.getKey())) {
                store.touchEpisode(type, entry.getKey(), entry.getValue());
            } else {
                store.openEpisode(type, entry.getKey(), entry.getValue());
                newEpisodes++;
            }
        }
        List<String> toClose = new ArrayList<>(open.keySet());
        toClose.removeAll(wanted.keySet());
        store.closeEpisodes(type, toClose);
        if (newEpisodes > 0) {
            detectedCounter(type, counterName).increment(newEpisodes);
        }
        lastOpenCounts.put(type, (long) wanted.size());
        return wanted.size();
    }
}
