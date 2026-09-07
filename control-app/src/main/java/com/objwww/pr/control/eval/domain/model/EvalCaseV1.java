package com.objwww.pr.control.eval.domain.model;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * EvalCaseV1（M5-01）：三适配器（OrderArena/Rca100/RcaEval）统一产出的内部案例契约。
 * 期望面与 AM3 评分器同构（TypedRootCause 类型化等值 + 症状码）；
 * rawArtifact 保留原始证据不覆盖来源字段。新增 benchmark 只加 Adapter，不动本契约。
 */
public record EvalCaseV1(String caseKey,
                         String scenarioFamilyId,
                         TypedRootCause expectedRootCause,
                         List<String> expectedSymptomCodes,
                         Map<String, Object> rawArtifact) {

    public EvalCaseV1 {
        Objects.requireNonNull(caseKey, "caseKey 不得为 null");
        Objects.requireNonNull(scenarioFamilyId, "scenarioFamilyId 不得为 null");
        Objects.requireNonNull(expectedRootCause, "expectedRootCause 不得为 null");
        Objects.requireNonNull(expectedSymptomCodes, "expectedSymptomCodes 不得为 null");
        Objects.requireNonNull(rawArtifact, "rawArtifact 不得为 null");
        DatasetVersion.requireText(caseKey, "caseKey");
        DatasetVersion.requireText(scenarioFamilyId, "scenarioFamilyId");
        expectedSymptomCodes = List.copyOf(expectedSymptomCodes);
        rawArtifact = Map.copyOf(rawArtifact);
    }
}
