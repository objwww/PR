package com.objwww.pr.control.alert.domain.agent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次 Jev 选材决策的审计行（JE-02，V166 rca_jev_selection）：池清单/保护项/
 * 选中项/被裁项/逐候选概率/是否应用，关联 rca_model_call 账本行（modelCallId）。
 * SHADOW 与 SELECT 都落行——SHADOW 是"只记档不改输入"的可观测面，正是本表的
 * 主要供数场景。append-only：决策事实不修改不删除。
 */
public record RcaJevSelection(
        UUID id,
        UUID runId,
        UUID taskId,
        String mode,
        boolean applied,
        List<String> poolRefs,
        List<String> protectedRefs,
        List<String> selectedRefs,
        List<String> omittedRefs,
        Map<String, Double> probabilities,
        UUID modelCallId,
        String policyDigest,
        Long latencyMs,
        Instant createdAt,
        Long totalTokens) {

    public RcaJevSelection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(mode, "mode");
        poolRefs = poolRefs == null ? List.of() : List.copyOf(poolRefs);
        protectedRefs = protectedRefs == null ? List.of() : List.copyOf(protectedRefs);
        selectedRefs = selectedRefs == null ? List.of() : List.copyOf(selectedRefs);
        omittedRefs = omittedRefs == null ? List.of() : List.copyOf(omittedRefs);
        probabilities = probabilities == null ? Map.of() : Map.copyOf(probabilities);
    }

    /** 构造工厂（落库形；totalTokens 是读面 JOIN 附加列，落库时恒 null） */
    public static RcaJevSelection of(UUID id, UUID runId, UUID taskId, String mode,
            boolean applied, List<String> poolRefs, List<String> protectedRefs,
            List<String> selectedRefs, List<String> omittedRefs,
            Map<String, Double> probabilities, UUID modelCallId, String policyDigest,
            Long latencyMs, Instant createdAt) {
        return new RcaJevSelection(id, runId, taskId, mode, applied, poolRefs,
                protectedRefs, selectedRefs, omittedRefs, probabilities, modelCallId,
                policyDigest, latencyMs, createdAt, null);
    }
}
