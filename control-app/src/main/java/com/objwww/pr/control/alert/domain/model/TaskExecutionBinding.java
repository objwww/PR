package com.objwww.pr.control.alert.domain.model;

import com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 任务→角色的冻结绑定（R7-X1，v2.1 §十一.2）：编译事务一次写入，只增不改；
 * 恢复只读本记录+固定资产——不猜 latest、不串用同名各版本（配套
 * AgentRegistry.requireExact 的 digest 钉面）；已部署节点缺对应运行器时执行面
 * 返回 CAPABILITY_UNAVAILABLE，不能顶替。
 *
 * <p>字段与 V46 rca_task_execution_binding 一一对应。inputRefs 为编译期校验过的
 * 本 run artifact 引用（RX06 跨 Run 引用在编译期拒绝）；expectedOutputSchema 为
 * 绑定时刻 Profile 输出 schema 冻结件（深冻结来源，事后改不漂移）。
 */
public record TaskExecutionBinding(
        UUID taskId,
        UUID runId,
        int roundId,
        String taskKey,
        String roleId,
        String roleVersion,
        String roleDigest,
        String releaseDigest,
        Long configEpoch,
        List<String> inputRefs,
        Map<String, Object> expectedOutputSchema,
        UUID parentRequestId,
        boolean required,
        FailurePolicy failurePolicy,
        Instant createdAt) {

    /** §十一.2 失败策略（首期两值；V46 ck 同集） */
    public enum FailurePolicy {DEAD_ON_FAILURE, SKIP_WITH_REASON}

    public TaskExecutionBinding {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(taskKey, "taskKey");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(roleVersion, "roleVersion");
        if (roleDigest == null || roleDigest.length() != 64 || !roleDigest.chars().allMatch(
                c -> Character.isDigit(c) || (c >= 'a' && c <= 'f'))) {
            throw new IllegalArgumentException("roleDigest 必须为 hex64: " + roleDigest);
        }
        if (roundId < 0) {
            throw new IllegalArgumentException("roundId 不得为负: " + roundId);
        }
        inputRefs = List.copyOf(Objects.requireNonNull(inputRefs, "inputRefs"));
        expectedOutputSchema = Map.copyOf(Objects.requireNonNull(expectedOutputSchema,
                "expectedOutputSchema"));
        Objects.requireNonNull(failurePolicy, "failurePolicy");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** 绑定冻结件摘要（input refs + schema 冻结面整体 canonical，hex 64；恢复对账锚） */
    public String frozenDigest() {
        Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("kind", "task-execution-binding");
        content.put("taskId", taskId.toString());
        content.put("runId", runId.toString());
        content.put("roundId", roundId);
        content.put("taskKey", taskKey);
        content.put("roleId", roleId);
        content.put("roleVersion", roleVersion);
        content.put("roleDigest", roleDigest);
        content.put("inputRefs", inputRefs.stream().sorted().toList());
        content.put("expectedOutputSchema", expectedOutputSchema);
        content.put("required", required);
        content.put("failurePolicy", failurePolicy.name());
        return InternalCanonicalJsonV1.sha256(content);
    }
}
