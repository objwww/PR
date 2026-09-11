package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillEvent;
import com.objwww.pr.control.drill.domain.model.DrillJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-02 八阶段时间线推导（§7.2）：enteredAt 只取真实事件（无事件 = null 不推断）；
 * 失败点 FAILED、其后 SKIPPED（不渲染"未开始"伪装）；注入前取消其后 SKIPPED；
 * CLOSED 全 DONE（outcome 由详情顶层另存，不由时间线冒充）。
 */
class DrillTimelineTest {

    private static final Instant T0 = Instant.parse("2026-09-11T00:00:00Z");

    private static DrillJob job(DrillJob.State state) {
        DrillJob base = DrillJob.queued(UUID.randomUUID(), "S3", "F1 幂等失效",
                "0".repeat(64), "arena-195", "op", "{}", "1".repeat(64), "k", T0);
        if (state == DrillJob.State.QUEUED) {
            return base;
        }
        if (state == DrillJob.State.CLOSED) {
            return base.advanced(DrillJob.State.CLOSED, null, "PASS", T0, T0);
        }
        return base.advanced(state, state == DrillJob.State.FAILED
                ? "INJECTION_NOT_IMPLEMENTED" : null, null, null, T0);
    }

    private static List<DrillEvent> events(DrillJob job, String... toStates) {
        List<DrillEvent> out = new ArrayList<>();
        String from = "QUEUED";
        int i = 0;
        for (String to : toStates) {
            out.add(DrillEvent.phaseTransition(job.id(),
                    DrillJob.State.valueOf(from), DrillJob.State.valueOf(to), "w",
                    "{}", T0.plusSeconds(++i * 60L)));
            from = to;
        }
        return out;
    }

    private static DrillTimeline.Stage stage(List<DrillTimeline.Stage> stages, String key) {
        return stages.stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("QUEUED：受理 DONE（enteredAt=createdAt），其后 PENDING 零推断")
    void queued() {
        DrillJob j = job(DrillJob.State.QUEUED);
        List<DrillTimeline.Stage> stages = DrillTimeline.of(j, List.of());
        assertThat(stages).hasSize(8);
        assertThat(stage(stages, "ACCEPTED").status()).isEqualTo(DrillTimeline.Status.DONE);
        assertThat(stage(stages, "ACCEPTED").enteredAt()).isEqualTo(j.createdAt());
        assertThat(stage(stages, "PRECHECK").status())
                .isEqualTo(DrillTimeline.Status.PENDING);
        assertThat(stage(stages, "PRECHECK").enteredAt()).isNull();
    }

    @Test
    @DisplayName("INJECTING 失败（本批 NOT_IMPLEMENTED 面）：预检 DONE、注入 FAILED、其后 SKIPPED")
    void failedAtInjecting() {
        DrillJob j = job(DrillJob.State.FAILED);
        List<DrillEvent> ev = events(j, "PRECHECK", "INJECTING", "FAILED");
        List<DrillTimeline.Stage> stages = DrillTimeline.of(j, ev);
        assertThat(stage(stages, "PRECHECK").status()).isEqualTo(DrillTimeline.Status.DONE);
        assertThat(stage(stages, "PRECHECK").enteredAt())
                .isEqualTo(T0.plusSeconds(60));
        assertThat(stage(stages, "INJECTION").status())
                .isEqualTo(DrillTimeline.Status.FAILED);
        assertThat(stage(stages, "INJECTION").enteredAt())
                .isEqualTo(T0.plusSeconds(120));
        for (String key : new String[]{"TRAFFIC", "SYMPTOM_WAIT", "AGENT_INVESTIGATION",
                "STOP", "VERIFY_RECOVERY"}) {
            assertThat(stage(stages, key).status())
                    .as(key).isEqualTo(DrillTimeline.Status.SKIPPED);
        }
    }

    @Test
    @DisplayName("注入前取消：取消点 CANCELLED、其后 SKIPPED（零注入无需恢复核验，如实）")
    void cancelledPrecheck() {
        DrillJob base = job(DrillJob.State.CANCELLED);
        DrillJob withStop = new DrillJob(base.id(), base.scenarioId(),
                base.scenarioName(), base.templateDigest(), base.targetEnv(),
                base.operator(), base.state(), base.outcome(), base.terminalReason(),
                base.paramsJson(), base.payloadHash(), base.idempotencyKey(), "sk",
                T0.plusSeconds(30), base.workerId(), base.claimedAt(), base.revision(),
                base.relatedIncidentId(), base.relatedRunId(), base.createdAt(),
                base.updatedAt(), base.closedAt());
        List<DrillEvent> ev = events(withStop, "PRECHECK", "CANCELLED");
        List<DrillTimeline.Stage> stages = DrillTimeline.of(withStop, ev);
        assertThat(stage(stages, "PRECHECK").status())
                .isEqualTo(DrillTimeline.Status.CANCELLED);
        assertThat(stage(stages, "INJECTION").status())
                .isEqualTo(DrillTimeline.Status.SKIPPED);
        assertThat(stage(stages, "VERIFY_RECOVERY").status())
                .isEqualTo(DrillTimeline.Status.SKIPPED);
        assertThat(stage(stages, "STOP").enteredAt()).isEqualTo(T0.plusSeconds(30));
    }

    @Test
    @DisplayName("恢复异常占位：恢复阶段 FAILED、核验 SKIPPED（保留占位如实呈现）")
    void recoveryFailed() {
        DrillJob j = job(DrillJob.State.RECOVERY_FAILED);
        List<DrillEvent> ev = events(j, "PRECHECK", "INJECTING", "RECOVERING",
                "RECOVERY_FAILED");
        List<DrillTimeline.Stage> stages = DrillTimeline.of(j, ev);
        assertThat(stage(stages, "STOP").status()).isEqualTo(DrillTimeline.Status.FAILED);
        assertThat(stage(stages, "VERIFY_RECOVERY").status())
                .isEqualTo(DrillTimeline.Status.SKIPPED);
    }

    @Test
    @DisplayName("CLOSED：八阶段全 DONE（CLOSED≠成功——outcome 由详情顶层另存）")
    void closed() {
        DrillJob j = job(DrillJob.State.CLOSED);
        List<DrillEvent> ev = events(j, "PRECHECK", "INJECTING", "OBSERVING",
                "RECOVERING", "VERIFYING", "CLOSED");
        List<DrillTimeline.Stage> stages = DrillTimeline.of(j, ev);
        assertThat(stages).allSatisfy(s -> assertThat(s.status())
                .isEqualTo(DrillTimeline.Status.DONE));
    }

    @Test
    @DisplayName("进行中 OBSERVING：流量 DONE、症状等待 ACTIVE、Agent 调查 PENDING")
    void observing() {
        DrillJob j = job(DrillJob.State.OBSERVING);
        List<DrillEvent> ev = events(j, "PRECHECK", "INJECTING", "OBSERVING");
        List<DrillTimeline.Stage> stages = DrillTimeline.of(j, ev);
        assertThat(stage(stages, "TRAFFIC").status()).isEqualTo(DrillTimeline.Status.DONE);
        assertThat(stage(stages, "SYMPTOM_WAIT").status())
                .isEqualTo(DrillTimeline.Status.ACTIVE);
        assertThat(stage(stages, "AGENT_INVESTIGATION").status())
                .isEqualTo(DrillTimeline.Status.PENDING);
    }
}
