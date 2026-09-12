package com.objwww.pr.control.ops.application;

import com.objwww.pr.control.ops.domain.repository.AgentOpsReader;
import com.objwww.pr.control.ops.domain.repository.AgentOpsReader.AgentOpsAggregate;
import com.objwww.pr.control.ops.domain.repository.AgentOpsReader.ToolCallCount;
import com.objwww.pr.control.ops.domain.repository.AgentOpsReader.WorkerActivity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentOpsSummaryService 单测（UI-6；IncidentQueryServiceTest 同模式——假端口）：
 * generatedAt 钉 now、聚合字段直透、诚实 null（oldestReadyWaitSeconds/tokens24h）不回填。
 */
class AgentOpsSummaryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T10:30:00Z");

    private final FakeReader reader = new FakeReader();
    private final AgentOpsSummaryService service = new AgentOpsSummaryService(reader, () -> NOW);

    @Test
    void summaryPassesThroughAggregateAndStampsGeneratedAt() {
        reader.aggregate = new AgentOpsAggregate(3, 2, 1, 120L, 4, 5, 1, 42, 98765L,
                List.of(new ToolCallCount("gpt-5", 30), new ToolCallCount("deepseek-v3", 12)));

        AgentOpsSummaryService.AgentOpsSummaryResponse out = service.summary();

        assertThat(out.activeRuns()).isEqualTo(3);
        assertThat(out.awaitingReviewRuns()).isEqualTo(2);
        assertThat(out.readyTasks()).isEqualTo(1);
        assertThat(out.oldestReadyWaitSeconds()).isEqualTo(120L);
        assertThat(out.openCases()).isEqualTo(4);
        assertThat(out.notifyOutboxPending()).isEqualTo(5);
        assertThat(out.notifyOutboxFailed24h()).isEqualTo(1);
        assertThat(out.llmCalls24h()).isEqualTo(42);
        assertThat(out.tokens24h()).isEqualTo(98765L);
        assertThat(out.topTools24h()).containsExactly(
                new ToolCallCount("gpt-5", 30), new ToolCallCount("deepseek-v3", 12));
        assertThat(out.generatedAt()).isEqualTo(NOW);
        assertThat(reader.lastNow).isEqualTo(NOW);
    }

    @Test
    void honestNullsAreNotBackfilled() {
        reader.aggregate = new AgentOpsAggregate(0, 0, 0, null, 0, 0, 0, 0, null, List.of());

        AgentOpsSummaryService.AgentOpsSummaryResponse out = service.summary();

        assertThat(out.oldestReadyWaitSeconds()).isNull();
        assertThat(out.tokens24h()).isNull();
        assertThat(out.topTools24h()).isEmpty();
    }

    // -------------------------------------------------------------- 执行器活性（§三.12）

    @Test
    void workersPassesThroughAndStampsAsOf() {
        reader.activities = List.of(
                new WorkerActivity("rca", "worker-1", NOW.minusSeconds(30), 2),
                new WorkerActivity("eval", "eval-host2", NOW.minusSeconds(120), 0));

        AgentOpsSummaryService.WorkerActivityResponse out = service.workers(Duration.ofMinutes(60));

        assertThat(out.workers()).containsExactly(
                new WorkerActivity("rca", "worker-1", NOW.minusSeconds(30), 2),
                new WorkerActivity("eval", "eval-host2", NOW.minusSeconds(120), 0));
        assertThat(out.windowMinutes()).isEqualTo(60);
        assertThat(out.derivedFromLeaseActivity()).isTrue();
        assertThat(out.asOf()).isEqualTo(NOW);
        assertThat(reader.lastActivityNow).isEqualTo(NOW);
        assertThat(reader.lastWindow).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void emptyLeaseActivityIsHonestEmptyList() {
        reader.activities = List.of();

        AgentOpsSummaryService.WorkerActivityResponse out = service.workers(Duration.ofMinutes(30));

        assertThat(out.workers()).isEmpty();
        assertThat(out.windowMinutes()).isEqualTo(30);
    }

    private static final class FakeReader implements AgentOpsReader {
        AgentOpsAggregate aggregate;
        Instant lastNow;
        List<WorkerActivity> activities = List.of();
        Instant lastActivityNow;
        Duration lastWindow;

        @Override
        public AgentOpsAggregate summary(Instant now) {
            this.lastNow = now;
            return aggregate;
        }

        @Override
        public List<WorkerActivity> workerActivity(Instant now, Duration window) {
            this.lastActivityNow = now;
            this.lastWindow = window;
            return activities;
        }
    }
}
