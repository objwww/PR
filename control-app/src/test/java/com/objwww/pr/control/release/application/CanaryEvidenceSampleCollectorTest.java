package com.objwww.pr.control.release.application;

import com.objwww.pr.control.release.domain.repository.CanaryEvidenceSampleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Canary 采集适配器 L0（B4）：LIVE 生产溯源门（provenance.source=inbox + run 可追，
 * INV-AM6-9 禁脚本补数）、DRILL/REPLAY 显式分级、run_id 幂等。
 */
class CanaryEvidenceSampleCollectorTest {

    private static final Instant NOW = Instant.parse("2026-09-12T10:00:00Z");

    private List<CanaryEvidenceSampleRepository.SampleRow> rows;
    private CanaryEvidenceSampleCollector collector;

    @BeforeEach
    void setUp() {
        rows = new ArrayList<>();
        collector = new CanaryEvidenceSampleCollector(new MemSampleRepo(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CanaryEvidenceSampleCollector.LiveObservation live(Map<String, Object> provenance) {
        return new CanaryEvidenceSampleCollector.LiveObservation(
                UUID.randomUUID(), UUID.randomUUID(), "alertname=X|service=svc",
                provenance, Map.of("failed", false, "outcome", "COMPLETED"));
    }

    @Test
    @DisplayName("LIVE 生产门：source=inbox + run 可追 → LIVE_CANARY 落行；缺溯源/缺 run 拒")
    void liveGate() {
        assertThat(collector.collectLive(live(Map.of("source", "inbox", "tenant", "t1"))))
                .isTrue();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).evidenceClass()).isEqualTo("LIVE_CANARY");

        // 缺溯源 = 禁脚本补数
        assertThatThrownBy(() -> collector.collectLive(
                live(Map.of("source", "script")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> collector.collectLive(live(Map.of())))
                .isInstanceOf(IllegalArgumentException.class);

        // 缺 run 可追性
        var noRun = new CanaryEvidenceSampleCollector.LiveObservation(null, UUID.randomUUID(),
                "k", Map.of("source", "inbox"), Map.of());
        assertThatThrownBy(() -> collector.collectLive(noRun))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(rows).as("拒绝路径零落库").hasSize(1);
    }

    @Test
    @DisplayName("DRILL/REPLAY 显式分级落行；LIVE 走非生产门即拒；run_id 幂等")
    void nonLiveAndIdempotency() {
        UUID runId = UUID.randomUUID();
        assertThat(collector.collectNonLive("DRILL", runId, UUID.randomUUID(), "k",
                Map.of("source", "drill"), Map.of("failed", false))).isTrue();
        assertThatThrownBy(() -> collector.collectNonLive("LIVE_CANARY", runId,
                UUID.randomUUID(), "k", Map.of("source", "inbox"), Map.of()))
                .as("LIVE 必须走 collectLive 生产门").isInstanceOf(IllegalArgumentException.class);

        // 幂等：同 run 重复采集（无论分级）= false 不重复落
        assertThat(collector.collectNonLive("DRILL", runId, UUID.randomUUID(), "k",
                Map.of(), Map.of())).isFalse();
        assertThat(rows).hasSize(1);
    }

    // ------------------------------------------------------------ 桩

    final class MemSampleRepo implements CanaryEvidenceSampleRepository {

        @Override
        public boolean insert(CanaryEvidenceSampleRepository.SampleRow row) {
            boolean exists = rows.stream().anyMatch(r -> r.runId().equals(row.runId()));
            if (exists) {
                return false;
            }
            rows.add(row);
            return true;
        }

        @Override
        public List<CanaryEvidenceSampleRepository.SampleRow> findByCollectedBetween(
                Instant from, Instant to) {
            return rows.stream()
                    .filter(r -> !r.createdAt().isBefore(from) && r.createdAt().isBefore(to))
                    .toList();
        }
    }
}
