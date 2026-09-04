package com.objwww.pr.control.alert.domain.service;

import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UT-A06：SlaPolicy——deadline 计算 / ready_since 起算 / 排序语义（§6.2）。
 */
class SlaPolicyTest {

    private final SlaPolicy policy = new SlaPolicy(Duration.ofMinutes(10), Duration.ofMinutes(60));

    private static final Instant READY = Instant.parse("2026-09-03T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-03T10:30:00Z");   // ready + 30min

    @Test
    void severityToPriorityMapping() {
        assertThat(policy.priority("critical")).isEqualTo(SlaPolicy.PRIORITY_CRITICAL);
        assertThat(policy.priority("warning")).isEqualTo(SlaPolicy.PRIORITY_WARNING);
        assertThat(policy.priority("info")).isEqualTo(SlaPolicy.PRIORITY_INFO);
        // 未知级别按 info 收敛（安全侧），null 同
        assertThat(policy.priority("P0")).isEqualTo(SlaPolicy.PRIORITY_INFO);
        assertThat(policy.priority(null)).isEqualTo(SlaPolicy.PRIORITY_INFO);
    }

    @Test
    void deadlineFromReadySinceAndCriticalNeverExpires() {
        assertThat(policy.deadline(READY, SlaPolicy.PRIORITY_CRITICAL)).isEqualTo(Instant.MAX);
        assertThat(policy.deadline(READY, SlaPolicy.PRIORITY_WARNING))
                .isEqualTo(READY.plus(Duration.ofMinutes(10)));
        assertThat(policy.deadline(READY, SlaPolicy.PRIORITY_INFO))
                .isEqualTo(READY.plus(Duration.ofMinutes(60)));
    }

    @Test
    void slaWindowMustBePositive() {
        assertThatThrownBy(() -> new SlaPolicy(Duration.ZERO, Duration.ofMinutes(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RcaTask task(int priority, Instant deadline, Instant createdAt, UUID id) {
        return new RcaTask(id, UUID.randomUUID(), RcaTask.HOLMES_INVESTIGATE, RcaTaskState.READY,
                priority, Instant.EPOCH, Instant.EPOCH, deadline,
                null, null, 0, 0, 3, createdAt, createdAt);
    }

    @Test
    void claimOrderOverdueJumpsAheadOfHigherPriority() {
        // NOW=ready+30min：info（deadline=ready+60min 未到期）vs warning（deadline=ready+10min 已到期）
        RcaTask infoNotOverdue = task(SlaPolicy.PRIORITY_INFO,
                READY.plus(Duration.ofMinutes(60)), READY.minusSeconds(10), UUID.randomUUID());
        RcaTask warningOverdue = task(SlaPolicy.PRIORITY_WARNING,
                READY.plus(Duration.ofMinutes(10)), READY.minusSeconds(20), UUID.randomUUID());

        List<RcaTask> list = new ArrayList<>(List.of(infoNotOverdue, warningOverdue));
        list.sort(SlaPolicy.claimOrder(NOW));

        // 到期越级（§6.2"等待超 SLA 才允许越级"）：warning 到期 > info 未到期
        assertThat(list.get(0)).isSameAs(warningOverdue);
    }

    @Test
    void claimOrderWithinSameOverdueBucketFollowsPriorityThenDeadline() {
        RcaTask criticalNeverOverdue = task(SlaPolicy.PRIORITY_CRITICAL,
                Instant.MAX, READY, UUID.randomUUID());                         // critical 永不到期
        RcaTask warningOverdue = task(SlaPolicy.PRIORITY_WARNING,
                READY.plus(Duration.ofMinutes(10)), READY, UUID.randomUUID());  // 已到期
        RcaTask infoOverdue = task(SlaPolicy.PRIORITY_INFO,
                READY.plus(Duration.ofMinutes(60)), READY, UUID.randomUUID());  // 未到期（60min>30min）

        List<RcaTask> list = new ArrayList<>(List.of(infoOverdue, criticalNeverOverdue, warningOverdue));
        list.sort(SlaPolicy.claimOrder(NOW));

        // 到期桶：warning 在前；未到期桶：critical > info
        assertThat(list).containsExactly(warningOverdue, criticalNeverOverdue, infoOverdue);
        assertThat(SlaPolicy.overdue(NOW, criticalNeverOverdue.deadlineAt())).isFalse();
    }

    @Test
    void claimOrderTieBreaksByDeadlineThenCreatedAtThenId() {
        Instant d = READY.plus(Duration.ofMinutes(10));
        UUID small = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID large = UUID.fromString("00000000-0000-0000-0000-000000000002");

        RcaTask laterCreated = task(SlaPolicy.PRIORITY_WARNING, d, READY.plusSeconds(1), large);
        RcaTask earlierCreated = task(SlaPolicy.PRIORITY_WARNING, d, READY, large);
        RcaTask sameCreatedSmallerId = task(SlaPolicy.PRIORITY_WARNING, d, READY, small);

        List<RcaTask> list = new ArrayList<>(List.of(laterCreated, earlierCreated, sameCreatedSmallerId));
        list.sort(SlaPolicy.claimOrder(NOW));

        assertThat(list).containsExactly(sameCreatedSmallerId, earlierCreated, laterCreated);
    }
}
