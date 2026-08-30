package com.objwww.pr.control.application;

import com.objwww.pr.control.interfaces.webhook.PullRequestEvent;
import com.objwww.pr.control.support.InMemoryStores;
import com.objwww.pr.control.support.OrchestratorFixture;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * IntakeService：异步派发（直连执行器驱动）+ B-3 webhook 重投幂等兜底。
 */
class IntakeServiceTest {

    private OrchestratorFixture fx;
    private SnapshotService snapshotService;
    private IntakeService intakeService;

    @BeforeEach
    void setUp() {
        fx = new OrchestratorFixture();
        snapshotService = mock(SnapshotService.class);
        when(snapshotService.prepare(anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> new SnapshotService.SnapshotOutcome(
                        Digest.sha256Of("snap-" + inv.getArgument(3)),
                        Digest.sha256Of("diff-" + inv.getArgument(3)), 3, 100));
        intakeService = new IntakeService(snapshotService, fx.orchestrator,
                fx.cas, fx.artifacts, Runnable::run, // 直连执行器：同步驱动异步任务
                "m0-policy-v1", "m0-prompt-v1", "m0-toolset-v1");
    }

    private static PullRequestEvent event(String deliveryId, String headSha) {
        return new PullRequestEvent(deliveryId, "opened", 987L, 12345L, "org/repo", 7,
                "open", false, false, headSha, "main", "basesha456");
    }

    @Test
    void acceptedEventRunsT0AndT1() {
        intakeService.accept(event("d-1", "head1"), "{}".getBytes(StandardCharsets.UTF_8));

        // webhook 原文登记（WEBHOOK_PAYLOAD）
        assertThat(fx.artifacts.all())
                .anyMatch(a -> a.artifactType().name().equals("WEBHOOK_PAYLOAD"));
        // T1 建 run
        assertThat(fx.subjects.findByRepositoryAndPrNumber(12345L, 7)).isPresent();
    }

    @Test
    void redeliveredSameDeliveryIsIdempotent() {
        // ST-05/B-3：同一 delivery 重投 → run_key 唯一约束兜底，只一个 Run，不抛错
        PullRequestEvent e = event("d-1", "head1");
        intakeService.accept(e, "{}".getBytes(StandardCharsets.UTF_8));
        intakeService.accept(e, "{}".getBytes(StandardCharsets.UTF_8));

        var subject = fx.subjects.findByRepositoryAndPrNumber(12345L, 7).orElseThrow();
        assertThat(fx.runs.findActiveByPrSubjectId(subject.getId())).hasSize(1);
        // epoch 未因重投重复 bump
        assertThat(subject.getPublicationEpoch()).isEqualTo(1);
    }
}
