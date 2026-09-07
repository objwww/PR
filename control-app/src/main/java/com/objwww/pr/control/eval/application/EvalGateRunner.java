package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.model.DimensionCounts;
import com.objwww.pr.control.eval.domain.model.EvalCaseInput;
import com.objwww.pr.control.eval.domain.model.EvaluationRecordV1;
import com.objwww.pr.control.eval.domain.model.GateThresholds;
import com.objwww.pr.control.eval.domain.model.SixDimResult;
import com.objwww.pr.control.eval.domain.repository.EvaluationRecordRepository;
import com.objwww.pr.control.eval.domain.service.PairedTrialStats;
import com.objwww.pr.control.eval.domain.service.PairedTrialStats.PairedOutcome;
import com.objwww.pr.control.eval.domain.service.QualityGate;
import com.objwww.pr.control.eval.domain.service.SafetyGate;
import com.objwww.pr.control.eval.domain.service.SixDimEvaluator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 评测门禁编排（M5-08；阶段 B 收口）：复用跑批产物（{@link EvalBatchRunner} 完成后的
 * 逐案观测面 {@link EvalCaseInput} + 配对结局 {@link PairedOutcome}）→
 * {@link SixDimEvaluator} 六维逐案计算 → {@link SafetyGate} 逐案安检 + 跨案聚合 →
 * {@link PairedTrialStats} 配对差值 → {@link QualityGate} 五分支裁定 →
 * {@link EvaluationRecordV1} 落档（insert-only，落档即冻结）。
 *
 * <p>运行门聚合口径（跨案）：latency = max（最坏面 fail-closed）、tokens = sum（批预算）、
 * 工具错误率 = 全批错误调用/全批调用；六维 traceRefs 全批拼接（可回溯不丢案）。
 * 无配对试验（pairs 空）→ stats 快照 null，如实 INCONCLUSIVE，不伪造统计面（INV-AM5-3）。
 *
 * <p>真实性标签随记录落档（§12.2）；CONTROL_FIXTURE 的 ELIGIBLE 结论不得冒充
 * Native 质量结论（消费方责任，标签在本记录不可抵赖）。
 */
public class EvalGateRunner {

    private final SixDimEvaluator sixDimEvaluator;
    private final SafetyGate safetyGate;
    private final QualityGate qualityGate;
    private final EvaluationRecordRepository records;

    public EvalGateRunner(SixDimEvaluator sixDimEvaluator,
                          SafetyGate safetyGate,
                          QualityGate qualityGate,
                          EvaluationRecordRepository records) {
        this.sixDimEvaluator = Objects.requireNonNull(sixDimEvaluator, "sixDimEvaluator 不得为 null");
        this.safetyGate = Objects.requireNonNull(safetyGate, "safetyGate 不得为 null");
        this.qualityGate = Objects.requireNonNull(qualityGate, "qualityGate 不得为 null");
        this.records = Objects.requireNonNull(records, "records 不得为 null");
    }

    /** 一次门禁判定的全部输入（跑批产物 + 版本化阈值 + digest 集 + 真实性标签） */
    public record GateBatchRequest(UUID evalRunId,
                                   EvaluationRecordV1.AuthenticityLabel authenticity,
                                   List<EvalCaseInput> cases,
                                   List<PairedOutcome> pairs,
                                   long statsSeed,
                                   GateThresholds thresholds,
                                   String datasetDigest,
                                   String configDigest,
                                   String engineDigest) {

        public GateBatchRequest {
            Objects.requireNonNull(evalRunId, "evalRunId 不得为 null");
            Objects.requireNonNull(authenticity, "authenticity 不得为 null");
            Objects.requireNonNull(cases, "cases 不得为 null");
            cases = List.copyOf(cases);
            Objects.requireNonNull(pairs, "pairs 不得为 null");
            pairs = List.copyOf(pairs);
            Objects.requireNonNull(thresholds, "thresholds 不得为 null");
            Objects.requireNonNull(datasetDigest, "datasetDigest 不得为 null");
            Objects.requireNonNull(configDigest, "configDigest 不得为 null");
            Objects.requireNonNull(engineDigest, "engineDigest 不得为 null");
        }
    }

    /** 过门并落档（insert-only）；返回落档记录本体供调用方使用 */
    public EvaluationRecordV1 gate(GateBatchRequest request) {
        List<SixDimResult> perCase = new ArrayList<>();
        List<SafetyGate.Violation> violations = new ArrayList<>();
        for (EvalCaseInput caseInput : request.cases()) {
            SixDimResult dim = sixDimEvaluator.evaluate(caseInput);
            perCase.add(dim);
            violations.addAll(safetyGate.check(dim, caseInput).violations());
        }

        SafetyGate.SafetyVerdict aggregatedSafety = new SafetyGate.SafetyVerdict(
                violations, violations.isEmpty()
                        ? SafetyGate.Verdict.PASS : SafetyGate.Verdict.REJECT);
        SixDimResult aggregate = aggregate(perCase);
        PairedTrialStats.StatsResult stats = request.pairs().isEmpty() ? null
                : PairedTrialStats.pairedDifference(request.pairs(), request.statsSeed());
        QualityGate.GateDecision decision = qualityGate.evaluate(
                aggregatedSafety, stats, aggregate, request.thresholds());

        EvaluationRecordV1 record = new EvaluationRecordV1(UUID.randomUUID(),
                request.evalRunId(), request.authenticity(), aggregate,
                aggregatedSafety.verdict().name(), snapshotViolations(violations),
                snapshotStats(stats), decision.outcome(), decision.reasons(),
                request.thresholds().version(), request.datasetDigest(),
                request.configDigest(), request.engineDigest(), Instant.now());
        records.insert(record);
        return record;
    }

    // ------------------------------------------------------------------ 聚合

    /** 六维跨案聚合：计数求和；latency=max、usageMissing=any；布尔面=any；traceRefs 拼接 */
    private static SixDimResult aggregate(List<SixDimResult> dims) {
        if (dims.isEmpty()) {
            throw new IllegalArgumentException("过门批次不得为空（无案例无结论）");
        }
        int tp = 0, fp = 0, fn = 0, support = 0, dup = 0, totalCalls = 0, errors = 0;
        int registered = 0, hallucinated = 0, rejected = 0, claimsTrue = 0, claimsFalse = 0;
        int claimsUnknown = 0, policyRejections = 0;
        boolean hit = false, unresolved = false, silence = false, usageMissing = false;
        boolean redteam = false;
        long latencyMax = 0L, prompt = 0L, completion = 0L, totalTokens = 0L;
        List<String> resultRefs = new ArrayList<>(), processRefs = new ArrayList<>(),
                toolRefs = new ArrayList<>(), costRefs = new ArrayList<>(),
                collabRefs = new ArrayList<>(), safetyRefs = new ArrayList<>();

        for (SixDimResult d : dims) {
            DimensionCounts.Result r = d.result().rawCounts();
            tp += r.truePositives();
            fp += r.falsePositives();
            fn += r.falseNegatives();
            support += r.support();
            hit |= r.rootCauseHit();
            unresolved |= r.unresolved();
            silence |= r.silencePenalty();
            resultRefs.addAll(d.result().traceRefs());

            DimensionCounts.Process p = d.process().rawCounts();
            totalCalls += p.totalToolCalls();
            dup += p.duplicateToolCalls();
            errors += p.errorToolCalls();
            processRefs.addAll(d.process().traceRefs());

            DimensionCounts.Tool t = d.tool().rawCounts();
            registered += t.registeredHits();
            hallucinated += t.hallucinatedCalls();
            rejected += t.rejectedCalls();
            toolRefs.addAll(d.tool().traceRefs());

            DimensionCounts.Cost c = d.cost().rawCounts();
            latencyMax = Math.max(latencyMax, c.latencyMs());
            prompt += c.promptTokens();
            completion += c.completionTokens();
            totalTokens += c.totalTokens();
            usageMissing |= c.usageMissing();
            costRefs.addAll(d.cost().traceRefs());

            DimensionCounts.Collaboration cb = d.collaboration().rawCounts();
            claimsTrue += cb.claimsTrue();
            claimsFalse += cb.claimsFalse();
            claimsUnknown += cb.claimsUnknown();
            collabRefs.addAll(d.collaboration().traceRefs());

            DimensionCounts.Safety s = d.safety().rawCounts();
            policyRejections += s.policyRejections();
            redteam |= s.redteamCase();
            safetyRefs.addAll(d.safety().traceRefs());
        }

        return new SixDimResult(
                new SixDimResult.Dim<>(new DimensionCounts.Result(tp, fp, fn, support,
                        hit, unresolved, silence), resultRefs),
                new SixDimResult.Dim<>(new DimensionCounts.Process(totalCalls, dup, errors),
                        processRefs),
                new SixDimResult.Dim<>(new DimensionCounts.Tool(registered, hallucinated,
                        rejected), toolRefs),
                new SixDimResult.Dim<>(new DimensionCounts.Cost(latencyMax, prompt, completion,
                        totalTokens, usageMissing), costRefs),
                new SixDimResult.Dim<>(new DimensionCounts.Collaboration(claimsTrue,
                        claimsFalse, claimsUnknown), collabRefs),
                new SixDimResult.Dim<>(new DimensionCounts.Safety(policyRejections, redteam),
                        safetyRefs));
    }

    private static List<EvaluationRecordV1.ViolationSnapshot> snapshotViolations(
            List<SafetyGate.Violation> violations) {
        return violations.stream()
                .map(v -> new EvaluationRecordV1.ViolationSnapshot(v.face(), v.ref(), v.reason()))
                .toList();
    }

    private static EvaluationRecordV1.StatsSnapshot snapshotStats(
            PairedTrialStats.StatsResult stats) {
        if (stats == null) {
            return null;
        }
        return new EvaluationRecordV1.StatsSnapshot(stats.clusterCount(), stats.statsSeed(),
                stats.algorithmVersion(), stats.resamples(), stats.ciMethod(),
                stats.pointEstimate(), stats.ciLower(), stats.ciUpper(),
                stats.verdict().name());
    }
}
