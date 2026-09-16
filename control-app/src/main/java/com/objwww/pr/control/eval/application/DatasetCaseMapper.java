package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.model.EvalCaseV1;
import com.objwww.pr.control.eval.domain.repository.ReplayCaseReader;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 回放案例映射（P2）：case_version.payload（EvalCaseV1 原文）→ REPLAY 形态
 * GoldenCase。执行契约：
 * <ul>
 *   <li>driver = {@link ReplayScenarioDriver}；chaosFamily = null（resolver 走
 *       incident_key 匹配路，不走靶场 scenario_map）；</li>
 *   <li>回放锚强制：payload.rawArtifact.source_run_id（来源可溯）与
 *       expectedSymptomCodes 首位 alertname（重投与 resolver 匹配键）缺一即抛——
 *       期望面残缺的案例禁止进入评测（GoldenScenarioRegistry 同律）；</li>
 *   <li>时间参数：调查时长主导（resolver 认终态 run）， firingWait=120s 覆盖
 *       重投→episode→run 链，hold=900s 覆盖 Native 调查全程；preheat=0。</li>
 * </ul>
 */
public final class DatasetCaseMapper {

    /** 回放时间参数（调查时长主导；与注入场景的秒级预热语义无关） */
    public static final GoldenCase.Timing REPLAY_TIMING =
            new GoldenCase.Timing(0, 900, 120, 300, 60);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DatasetCaseMapper() {
    }

    public static GoldenCase toGoldenCase(ReplayCaseReader.ReplayCaseRow row) {
        Objects.requireNonNull(row, "row");
        EvalCaseV1 content;
        try {
            content = MAPPER.readValue(row.payloadJson(), EvalCaseV1.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "案例 " + row.caseKey() + " payload 解析失败: " + e.getMessage(), e);
        }
        if (!content.caseKey().equals(row.caseKey())) {
            throw new IllegalArgumentException("案例键不一致: 行=" + row.caseKey()
                    + " payload=" + content.caseKey());
        }
        Object sourceRun = content.rawArtifact().get("source_run_id");
        if (!(sourceRun instanceof String sourceRunId) || sourceRunId.isBlank()) {
            throw new IllegalArgumentException("案例 " + row.caseKey()
                    + " 缺回放锚 rawArtifact.source_run_id（来源不可溯，禁止进评测）");
        }
        if (content.expectedSymptomCodes().isEmpty()
                || content.expectedSymptomCodes().getFirst().isBlank()) {
            throw new IllegalArgumentException("案例 " + row.caseKey()
                    + " 缺重放锚 expected_symptom_codes[0]（alertname，重投/匹配键）");
        }
        List<String> checkpoints = evidenceCheckpoints(content.rawArtifact());
        return new GoldenCase(row.caseKey(), "回放·" + row.datasetName() + ":" + row.datasetVersion(),
                ReplayScenarioDriver.DRIVER_NAME, null, content.scenarioFamilyId(),
                content.expectedRootCause(), content.expectedSymptomCodes(),
                Map.of(), null, REPLAY_TIMING, GoldenCase.KIND_REPLAY, checkpoints);
    }

    /**
     * GT 证据检查点（P3 路径维；materialize 时人工显式提供，存 rawArtifact 保留键
     * gt_evidence_checkpoints——EvalCaseV1 契约不动，扩展走 artifact 面）。非字符串
     * 条目/缺键 = 空表（路径维 NOT_APPLICABLE，不猜）。
     */
    static List<String> evidenceCheckpoints(Map<String, Object> rawArtifact) {
        Object raw = rawArtifact.get("gt_evidence_checkpoints");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item instanceof String s && !s.isBlank())
                .map(Object::toString)
                .toList();
    }
}
