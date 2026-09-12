package com.objwww.pr.control.release.application;

import com.objwww.pr.control.release.domain.model.CanaryEvidenceClass;
import com.objwww.pr.control.release.domain.model.CanaryWindowPolicy;
import com.objwww.pr.control.release.domain.repository.CanaryEvidenceSampleRepository;
import com.objwww.pr.control.release.domain.repository.CanaryWindowVerdictRepository;
import com.objwww.pr.control.release.domain.service.CanaryWindowEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Canary 窗口任务 L0（B4）：策略缺席跳过、身份源缺席诚实空转（零判定落库）、
 * LIVE 样本聚类评估+append 幂等、DRILL 不计入（INV-AM6-5）、对照组缺席
 * INCONCLUSIVE（M6-07 后诚实面）、独立容错（内部异常不外抛）。
 */
class CanaryWindowTaskTest {

    private static final Instant NOW = Instant.parse("2026-09-12T10:30:00Z");
    private static final long WINDOW_MINUTES = 60;

    private List<CanaryEvidenceSampleRepository.SampleRow> sampleRows;
    private List<CanaryWindowVerdictRepository.VerdictRow> verdictRows;
    private boolean identityPresent;
    private boolean policyPresent;
    private boolean throwInside;
    private CanaryWindowTask task;

    @BeforeEach
    void setUp() {
        sampleRows = new ArrayList<>();
        verdictRows = new ArrayList<>();
        identityPresent = true;
        policyPresent = true;
        throwInside = false;

        CanaryEvidenceSampleRepository sampleRepo = new CanaryEvidenceSampleRepository() {
            @Override
            public boolean insert(CanaryEvidenceSampleRepository.SampleRow row) {
                sampleRows.add(row);
                return true;
            }

            @Override
            public List<CanaryEvidenceSampleRepository.SampleRow> findByCollectedBetween(
                    Instant from, Instant to) {
                if (throwInside) {
                    throw new IllegalStateException("db down");
                }
                return sampleRows.stream()
                        .filter(r -> !r.createdAt().isBefore(from) && r.createdAt().isBefore(to))
                        .toList();
            }
        };
        CanaryWindowVerdictRepository verdictRepo = new CanaryWindowVerdictRepository() {
            @Override
            public boolean append(CanaryWindowVerdictRepository.VerdictRow row) {
                boolean dup = verdictRows.stream().anyMatch(v ->
                        v.rolloutId().equals(row.rolloutId())
                                && v.candidateDigest().equals(row.candidateDigest())
                                && v.rolloutPolicyDigest().equals(row.rolloutPolicyDigest())
                                && v.capabilityDigest().equals(row.capabilityDigest())
                                && v.fromPercent() == row.fromPercent()
                                && v.toPercent() == row.toPercent()
                                && v.windowSeq() == row.windowSeq()
                                && v.evidenceClass().equals(row.evidenceClass()));
                if (dup) {
                    return false;
                }
                verdictRows.add(row);
                return true;
            }

            @Override
            public List<CanaryWindowVerdictRepository.VerdictRow> findByRollout(
                    UUID rolloutId, String candidateDigest) {
                return verdictRows;
            }
        };
        CanaryWindowTask.WindowIdentitySource identitySource = (start, end, seq) ->
                identityPresent
                        ? Optional.of(new CanaryWindowEvaluator.WindowIdentity(
                                UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
                                "c".repeat(64), "p".repeat(64),
                                "d".repeat(64), 0, 100, seq,
                                CanaryEvidenceClass.LIVE_CANARY, start, end))
                        : Optional.empty();
        CanaryWindowTask.ControlCohortSource control = (from, to) -> List.of();
        CanaryWindowTask.PolicySource policySource = () -> policyPresent
                ? Optional.of(new CanaryWindowPolicy(3, 3, Duration.ofMinutes(WINDOW_MINUTES),
                        0.2, 0.1))
                : Optional.empty();

        task = new CanaryWindowTask(sampleRepo, verdictRepo, new CanaryWindowEvaluator(),
                identitySource, control, policySource,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void seedLive(String stickinessKey, boolean failed, Instant at) {
        sampleRows.add(new CanaryEvidenceSampleRepository.SampleRow(UUID.randomUUID(),
                UUID.randomUUID(), stickinessKey, "LIVE_CANARY",
                Map.of("tenant", "t1", "severity", "P1"),
                Map.of("failed", failed), at));
    }

    @Test
    @DisplayName("策略/身份缺席：跳过不落判定（fail-closed + 诚实空转）")
    void skipsWithoutPolicyOrIdentity() {
        policyPresent = false;
        assertThat(task.evaluateCurrentWindow()).isFalse();
        assertThat(verdictRows).isEmpty();

        policyPresent = true;
        identityPresent = false;
        assertThat(task.evaluateCurrentWindow()).isFalse();
        assertThat(verdictRows).isEmpty();
    }

    @Test
    @DisplayName("评窗主链：LIVE 聚类评估 → PASS/INCONCLUSIVE 判定落库；DRILL 不计入")
    void evaluateAndAppend() {
        // 3 独立事件（minSamples=3），全部成功 → 相对门：对照空 → INCONCLUSIVE(诚实)
        seedLive("k1", false, NOW.minusSeconds(600));
        seedLive("k2", false, NOW.minusSeconds(500));
        seedLive("k3", false, NOW.minusSeconds(400));
        seedLive("k1", false, NOW.minusSeconds(300)); // 同 k1 非独立，不入聚类计数

        assertThat(task.evaluateCurrentWindow()).isTrue();
        assertThat(verdictRows).hasSize(1);
        assertThat(verdictRows.get(0).verdict()).isEqualTo("INCONCLUSIVE");
        assertThat(verdictRows.get(0).eligibleIncidents()).isEqualTo(3);
        assertThat(verdictRows.get(0).rawCounts().get("excluded_reason"))
                .isEqualTo("NO_CONTROL_COHORT");

        // DRILL 样本不进聚类（INV-AM6-5）
        sampleRows.add(new CanaryEvidenceSampleRepository.SampleRow(UUID.randomUUID(),
                UUID.randomUUID(), "drill-key", "DRILL", Map.of(), Map.of(),
                NOW.minusSeconds(100)));
        assertThat(task.evaluateCurrentWindow()).isFalse(); // 同窗幂等（uq 重放）
        assertThat(verdictRows).as("同窗重评不重记").hasSize(1);
    }

    @Test
    @DisplayName("独立容错：内部异常不外抛，返回 false（worker 拍不被打断）")
    void faultTolerant() {
        throwInside = true;
        assertThat(task.evaluateCurrentWindow()).isFalse();
        assertThat(verdictRows).isEmpty();
    }
}
