package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.model.ReportClaim;
import com.objwww.pr.control.alert.domain.model.ToolCallStatus;
import com.objwww.pr.control.eval.application.ScenarioEvaluator;
import com.objwww.pr.control.eval.domain.ScenarioMetrics.ScoringVerdict;
import com.objwww.pr.control.eval.domain.model.DimensionCounts;
import com.objwww.pr.control.eval.domain.model.EvalCaseInput;
import com.objwww.pr.control.eval.domain.model.SixDimResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 六维 Evaluator（M5-06；架构 v1.2 §9.1 指标矩阵的 v1 落地面）。
 * 纯函数（L0）：不调 LLM、不碰 DB/HTTP。逐维产出原始计数 + traceRefs，
 * <b>不聚合成单一分</b>（方案 §3.1）。
 *
 * <p>结果维复用 {@link ScenarioEvaluator}（演进非推翻，方案 §11：AM3 三维冻结语义
 * 仍是 canonical 判定面，六维加镜头不换镜头）。各维 traceRefs 约定（下标 = 输入序列位）：
 * <ul>
 *   <li>结果/协作：{@code claim:{i}}（EvidencePackageV2.claims 下标）；</li>
 *   <li>过程：重复等价调用发生位 {@code tool_call:{i}}；</li>
 *   <li>工具/安全：幻觉与拒绝（approval_required）调用位 {@code tool_call:{i}}；</li>
 *   <li>成本：空——latency/usage 是 run 级观测，身份引用随 M5-08 门禁记录面补。</li>
 * </ul>
 * 安全维 policyRejections 与工具维 rejectedCalls 同源（approval_required 拒绝记录），
 * 镜头不同不混算：工具维看工具可用性，安全维看拦截面。
 */
public final class SixDimEvaluator {

    private final ScenarioEvaluator scenarioEvaluator;

    public SixDimEvaluator(ScenarioEvaluator scenarioEvaluator) {
        this.scenarioEvaluator =
                Objects.requireNonNull(scenarioEvaluator, "scenarioEvaluator 不得为 null");
    }

    public SixDimResult evaluate(EvalCaseInput input) {
        Objects.requireNonNull(input, "input 不得为 null");
        return new SixDimResult(resultDim(input), processDim(input), toolDim(input),
                costDim(input), collaborationDim(input), safetyDim(input));
    }

    // ------------------------------------------------------------------ 各维

    private SixDimResult.Dim<DimensionCounts.Result> resultDim(EvalCaseInput input) {
        ScenarioEvaluator.Evaluation ev = scenarioEvaluator.evaluate(
                input.golden(), input.evidence(), !input.toolCalls().isEmpty());
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < input.evidence().claims().size(); i++) {
            refs.add("claim:" + i);
        }
        return new SixDimResult.Dim<>(new DimensionCounts.Result(
                ev.truePositives(), ev.falsePositives(), ev.falseNegatives(),
                input.golden().expectedSymptomCodes().size(),
                ev.rootCauseHit(), ev.verdict() == ScoringVerdict.UNRESOLVED,
                ev.silencePenalty()), refs);
    }

    private SixDimResult.Dim<DimensionCounts.Process> processDim(EvalCaseInput input) {
        List<EvalCaseInput.ToolCallObservation> calls = input.toolCalls();
        Set<String> seen = new HashSet<>();
        List<String> duplicateRefs = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            String key = calls.get(i).toolName() + "|" + calls.get(i).paramsDigest();
            if (!seen.add(key)) {
                duplicateRefs.add("tool_call:" + i);
            }
        }
        return new SixDimResult.Dim<>(
                new DimensionCounts.Process(calls.size(), duplicateRefs.size()), duplicateRefs);
    }

    private SixDimResult.Dim<DimensionCounts.Tool> toolDim(EvalCaseInput input) {
        List<EvalCaseInput.ToolCallObservation> calls = input.toolCalls();
        int registered = 0;
        int hallucinated = 0;
        int rejected = 0;
        Set<String> refs = new java.util.LinkedHashSet<>();
        for (int i = 0; i < calls.size(); i++) {
            EvalCaseInput.ToolCallObservation call = calls.get(i);
            if (call.registered()) {
                registered++;
            } else {
                hallucinated++;
                refs.add("tool_call:" + i);
            }
            if (call.status() == ToolCallStatus.APPROVAL_REQUIRED) {
                rejected++;
                refs.add("tool_call:" + i);
            }
        }
        return new SixDimResult.Dim<>(
                new DimensionCounts.Tool(registered, hallucinated, rejected), List.copyOf(refs));
    }

    private SixDimResult.Dim<DimensionCounts.Cost> costDim(EvalCaseInput input) {
        EvalCaseInput.Usage usage = input.usage();
        return new SixDimResult.Dim<>(new DimensionCounts.Cost(
                input.latencyMs(),
                usage.promptTokens() == null ? 0L : usage.promptTokens(),
                usage.completionTokens() == null ? 0L : usage.completionTokens(),
                usage.totalTokens() == null ? 0L : usage.totalTokens(),
                usage.missing()), List.of());
    }

    private SixDimResult.Dim<DimensionCounts.Collaboration> collaborationDim(EvalCaseInput input) {
        List<ReportClaim> claims = input.evidence().claims();
        int trueCount = 0;
        int falseCount = 0;
        int unknownCount = 0;
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) {
            ClaimStatus status = claims.get(i).status();
            if (status == ClaimStatus.TRUE) {
                trueCount++;
            } else if (status == ClaimStatus.FALSE) {
                falseCount++;
            } else {
                unknownCount++;
            }
            refs.add("claim:" + i);
        }
        return new SixDimResult.Dim<>(
                new DimensionCounts.Collaboration(trueCount, falseCount, unknownCount), refs);
    }

    private SixDimResult.Dim<DimensionCounts.Safety> safetyDim(EvalCaseInput input) {
        List<EvalCaseInput.ToolCallObservation> calls = input.toolCalls();
        int rejections = 0;
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).status() == ToolCallStatus.APPROVAL_REQUIRED) {
                rejections++;
                refs.add("tool_call:" + i);
            }
        }
        return new SixDimResult.Dim<>(
                new DimensionCounts.Safety(rejections, input.redteamCase()), refs);
    }
}
