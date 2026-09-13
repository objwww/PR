package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.agent.AgentPhase;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.agent.RoleRuntimeKind;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.TaskExecutionBinding;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RV04/BA-142/T17/T18：确定性单工具角色的诚实结构化回执——findings 如实声明
 * "查了什么"，supportRefs=真实证据行，反证恒空（不制造假反证凑 witness），
 * missing_information 如实声明能力边界（结构契约：回执非全空）。
 */
class SingleToolRoleRunnerChildResultTest {

    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");

    @Test
    @DisplayName("T17/T18：确定性角色产出诚实结构化结果——零假反证，能力缺口如实")
    void deterministicChildProducesHonestStructuredResult() {
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();

        SingleToolRoleRunner runner = new SingleToolRoleRunner(Map.of("logs",
                (ctx, start, end) -> {
                    calls.incrementAndGet();
                    return new SingleToolEvidenceAgent.AgentResult(
                            SingleToolEvidenceAgent.AgentOutcome.EVIDENCE_PRODUCED,
                            List.of(evidenceId), null);
                }));

        RcaTask task = new RcaTask(taskId, runId, "DELEGATE-g-logs",
                RcaTaskState.RUNNING, 5, NOW, NOW, Instant.MAX, null, null, 0, 1, 2,
                NOW, NOW, 1);
        TaskExecutionBinding binding = new TaskExecutionBinding(taskId, runId, 1,
                "DELEGATE-g-logs", "logs", "1", Digest.sha256Of("logs").hex(),
                null, null, List.of(), Map.of("type", "object"), null, true,
                TaskExecutionBinding.FailurePolicy.DEAD_ON_FAILURE, NOW);
        AgentProfile profile = new AgentProfile("logs", "1", "p", "pv",
                Set.of("logs.query"), Map.of(BudgetKind.STEP, 8L),
                Map.of("type", "object"), Map.of(),
                AgentPhase.INVESTIGATE, RoleRuntimeKind.DETERMINISTIC_SINGLE_TOOL,
                Set.of(), 8, "single-pass");
        var ctx = new SingleToolEvidenceAgent.CallContext(runId, taskId,
                UUID.randomUUID(), 1, 1L, null, "1757574000/1757577600");

        RoleRunner.RoleDriveResult result = runner.drive(
                new RoleRunner.RoleDriveRequest(task, binding, profile, ctx,
                        "1757574000", "1757577600"));

        assertThat(result.outcome()).isEqualTo(RoleRunner.RoleDriveOutcome.EVIDENCE_PRODUCED);
        var child = result.childResult();
        assertThat(child).as("BA-142：结构化子结果必须在场（非机械映射）").isNotNull();
        assertThat(child.supportRefs()).containsExactly(evidenceId.toString());
        assertThat(child.counterRefs()).as("确定性角色无反证能力：反证恒空（诚实）")
                .isEmpty();
        assertThat(child.missingInformation()).as("能力缺口如实声明（不凑 witness）")
                .anySatisfy(m -> assertThat(m).contains("无法回答").contains("原始证据"));
        assertThat(child.findings()).isNotEmpty();
        assertThat(calls.get()).isEqualTo(1);
    }
}
