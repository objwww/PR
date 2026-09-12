package com.objwww.pr.control.release.application;

import com.objwww.pr.control.release.domain.model.CanaryWindowPolicy;
import com.objwww.pr.control.release.domain.repository.CanaryEvidenceSampleRepository;
import com.objwww.pr.control.release.domain.repository.CanaryWindowVerdictRepository;
import com.objwww.pr.control.release.domain.service.CanaryWindowEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Canary 窗口任务（B4，M6-01 周期评窗接线；V30:88-89）：worker 拍循环内独立容错
 * 调用（禁 @Scheduled/Quartz——A2 裁定同律）——读窗内样本 → 调
 * {@link CanaryWindowEvaluator} → append 窗判定（uq_cwv_window 同窗幂等，重评不重记）。
 * 策略值全来自 bundle canary 段（O-63 禁硬编码）；身份来自 {@link WindowIdentitySource}
 * （bundle 激活指针 + 策略段 digest——生产接线归收口清单，源缺席=任务空转不落判定，
 * 诚实空转非假评）。scored_json（M6-03 连续 K 窗评分回填）需 verdict 表 update 授权，
 * 与"零新迁移"纪律冲突 → 不在本卡（登记：M6-03 随 V99 授权候选另评）；
 * hasConsecutivePasses 已由仓储 findByRollout 断言源承载，M6-03 即插即用。
 */
public class CanaryWindowTask {

    private static final Logger log = LoggerFactory.getLogger(CanaryWindowTask.class);

    /** 窗口身份源（生产实现=bundle 激活指针派生；缺席=Optional.empty 任务空转） */
    @FunctionalInterface
    public interface WindowIdentitySource {

        Optional<CanaryWindowEvaluator.WindowIdentity> current(Instant windowStart,
                Instant windowEnd, int windowSeq);
    }

    /** 对照组样本源（Holmes cohort；M6-07 退场后生产缺省 empty → INCONCLUSIVE 诚实面） */
    @FunctionalInterface
    public interface ControlCohortSource {

        List<CanaryWindowEvaluator.Sample> controlSamples(Instant windowStart, Instant windowEnd);
    }

    /** 策略源（bundle canary 段；缺段 fail-closed = empty，任务跳过不评判） */
    @FunctionalInterface
    public interface PolicySource {

        Optional<CanaryWindowPolicy> current();
    }

    private final CanaryEvidenceSampleRepository samples;
    private final CanaryWindowVerdictRepository verdicts;
    private final CanaryWindowEvaluator evaluator;
    private final WindowIdentitySource identitySource;
    private final ControlCohortSource controlCohort;
    private final PolicySource policySource;
    private final Clock clock;

    public CanaryWindowTask(CanaryEvidenceSampleRepository samples,
            CanaryWindowVerdictRepository verdicts, CanaryWindowEvaluator evaluator,
            WindowIdentitySource identitySource, ControlCohortSource controlCohort,
            PolicySource policySource, Clock clock) {
        this.samples = Objects.requireNonNull(samples, "samples");
        this.verdicts = Objects.requireNonNull(verdicts, "verdicts");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.identitySource = Objects.requireNonNull(identitySource, "identitySource");
        this.controlCohort = Objects.requireNonNull(controlCohort, "controlCohort");
        this.policySource = Objects.requireNonNull(policySource, "policySource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 评一窗（worker 拍内调用；返回 false=跳过/幂等重放，不抛出——独立容错） */
    public boolean evaluateCurrentWindow() {
        try {
            return evaluateCurrentWindowInternal();
        } catch (RuntimeException e) {
            log.warn("canary 评窗失败（独立容错，不阻断 worker 拍）: {}", e.getMessage());
            return false;
        }
    }

    private boolean evaluateCurrentWindowInternal() {
        Optional<CanaryWindowPolicy> policy = policySource.current();
        if (policy.isEmpty()) {
            log.debug("canary 策略段缺席（fail-closed），评窗跳过");
            return false;
        }
        Instant now = clock.instant();
        long windowMinutes = policy.get().windowLength().toMinutes();
        // 全局确定性窗序号（纪元对齐）：windowSeq = floor(epoch分钟 / 窗幅)
        long epochMinutes = now.getEpochSecond() / 60;
        int windowSeq = (int) (epochMinutes / windowMinutes);
        Instant windowStart = Instant.ofEpochSecond((epochMinutes / windowMinutes)
                * windowMinutes * 60L);
        Instant windowEnd = windowStart.plus(Duration.ofMinutes(windowMinutes));

        Optional<CanaryWindowEvaluator.WindowIdentity> identity =
                identitySource.current(windowStart, windowEnd, windowSeq);
        if (identity.isEmpty()) {
            log.debug("canary 窗口身份源缺席（bundle 未激活/未接线），评窗空转");
            return false;
        }

        List<CanaryWindowEvaluator.Sample> nativeSamples = new ArrayList<>();
        for (CanaryEvidenceSampleRepository.SampleRow row :
                samples.findByCollectedBetween(windowStart, windowEnd)) {
            if (!"LIVE_CANARY".equals(row.evidenceClass())) {
                continue; // INV-AM6-5：DRILL/REPLAY 只记录不晋升
            }
            nativeSamples.add(new CanaryWindowEvaluator.Sample(
                    row.stickinessKey(),
                    String.valueOf(row.provenance().getOrDefault("tenant", "unknown")),
                    String.valueOf(row.provenance().getOrDefault("severity", "unknown")),
                    Boolean.TRUE.equals(row.observed().get("failed")),
                    row.createdAt()));
        }
        List<CanaryWindowEvaluator.Sample> control =
                controlCohort.controlSamples(windowStart, windowEnd);

        CanaryWindowEvaluator.Draft draft = evaluator.evaluate(identity.get(),
                nativeSamples, control, null, policy.get());

        boolean appended = verdicts.append(new CanaryWindowVerdictRepository.VerdictRow(
                identity.get().rolloutId(), identity.get().candidateDigest(),
                identity.get().rolloutPolicyDigest(), identity.get().capabilityDigest(),
                identity.get().fromPercent(), identity.get().toPercent(), windowSeq,
                identity.get().windowStart(), identity.get().windowEnd(),
                identity.get().evidenceClass().name(), draft.eligibleIncidents(),
                draft.rawCounts(), draft.strata(), draft.control(), draft.absoluteSlo(),
                draft.criticalPass(), Map.of(), draft.verdict(), List.of(), now));
        log.info("canary 评窗：seq={} verdict={} eligible={} appended={}", windowSeq,
                draft.verdict(), draft.eligibleIncidents(), appended);
        return appended;
    }
}
