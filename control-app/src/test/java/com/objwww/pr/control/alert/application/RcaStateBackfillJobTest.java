package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.repository.RcaStateScanRepository;
import com.objwww.pr.control.alert.domain.repository.RcaStateScanRepository.StateRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-M4-03：状态回填/校验作业——分批扫描、可重入（中断重跑）、行数/digest 对账、
 * 违规行 fail-closed 收集不断批。
 */
class RcaStateBackfillJobTest {

    /** 有序键集内存实现：与 PG 实现同构——列表恒按 id 升序，afterId 断点 + limit 语义 */
    private static final class InMemoryScans implements RcaStateScanRepository {
        final List<StateRow> tasks = new ArrayList<>();
        final List<StateRow> runs = new ArrayList<>();

        void addTask(StateRow row) {
            tasks.add(row);
            tasks.sort((a, b) -> a.id().compareTo(b.id()));
        }

        void addRun(StateRow row) {
            runs.add(row);
            runs.sort((a, b) -> a.id().compareTo(b.id()));
        }

        @Override
        public List<StateRow> scanTaskStates(UUID afterId, int limit) {
            return page(tasks, afterId, limit);
        }

        @Override
        public List<StateRow> scanRunStates(UUID afterId, int limit) {
            return page(runs, afterId, limit);
        }

        private static List<StateRow> page(List<StateRow> rows, UUID after, int limit) {
            var stream = rows.stream();
            if (after != null) {
                stream = stream.dropWhile(r -> r.id().compareTo(after) <= 0);
            }
            return stream.limit(limit).toList();
        }
    }

    private static StateRow row(String state) {
        return new StateRow(UUID.randomUUID(), state);
    }

    @Test
    void batchedScanAggregatesCountsAndReconcilesAcrossRuns() {
        InMemoryScans scans = new InMemoryScans();
        for (String s : new String[]{"READY", "READY", "BLOCKED", "RUNNING", "STALE"}) {
            scans.addTask(row(s));
        }
        for (String s : new String[]{"QUEUED", "REPORTING", "PARTIAL"}) {
            scans.addRun(row(s));
        }

        RcaStateBackfillJob job = new RcaStateBackfillJob(scans, 2);
        RcaStateBackfillJob.BackfillReport first = job.run();

        assertThat(first.taskRows()).isEqualTo(5);
        assertThat(first.runRows()).isEqualTo(3);
        assertThat(first.batchesProcessed()).as("task 3 批 + run 2 批").isEqualTo(5);
        assertThat(first.taskStateCounts()).containsEntry("READY", 2L)
                .containsEntry("BLOCKED", 1L).containsEntry("RUNNING", 1L)
                .containsEntry("STALE", 1L);
        assertThat(first.runStateCounts()).containsEntry("QUEUED", 1L)
                .containsEntry("REPORTING", 1L).containsEntry("PARTIAL", 1L);
        assertThat(first.violations()).isEmpty();

        // 行数/digest 对账：重跑（模拟中断后恢复）结果恒等
        RcaStateBackfillJob.BackfillReport second = job.run();
        assertThat(second.digest()).isEqualTo(first.digest());
        assertThat(second.taskRows()).isEqualTo(first.taskRows());
        assertThat(second.runRows()).isEqualTo(first.runRows());
        assertThat(second.taskStateCounts()).isEqualTo(first.taskStateCounts());
        assertThat(second.runStateCounts()).isEqualTo(first.runStateCounts());
    }

    @Test
    void digestIsBatchSizeInvariant() {
        InMemoryScans scans = new InMemoryScans();
        for (String s : new String[]{"LEASED", "RETRY_WAIT", "DONE", "CANCELLED", "DEAD",
                "SKIPPED", "FAILED_TERMINAL"}) {
            scans.addTask(row(s));
        }
        scans.addRun(row("EXPIRED"));

        String digestSmallBatches = new RcaStateBackfillJob(scans, 1).run().digest();
        String digestBigBatches = new RcaStateBackfillJob(scans, 100).run().digest();

        assertThat(digestSmallBatches).as("分批粒度不影响对账 digest").isEqualTo(digestBigBatches);
    }

    @Test
    void nonAdvancingKeysetFailsFastInsteadOfLoopingForever() {
        // 扫描器违反 id 升序契约（返回已消费行）→ 键集守卫立即抛错，不允许死循环
        RcaStateScanRepository broken = new RcaStateScanRepository() {
            @Override
            public List<StateRow> scanTaskStates(UUID afterId, int limit) {
                // 永远返回同一行：键集永不推进
                return List.of(new StateRow(UUID.fromString(
                        "00000000-0000-0000-0000-000000000001"), "READY"));
            }

            @Override
            public List<StateRow> scanRunStates(UUID afterId, int limit) {
                return List.of();
            }
        };

        try {
            new RcaStateBackfillJob(broken, 1).run();
            throw new AssertionError("期望 IllegalStateException（键集未推进）未发生");
        } catch (IllegalStateException expected) {
            // fail-fast 守卫生效（本轮曾因 fake 未排序实证过死循环：45 万行/22 万批日志）
        }
    }

    @Test
    void violationRowIsCollectedFailClosedWithoutBreakingBatch() {
        InMemoryScans scans = new InMemoryScans();
        StateRow bad = row("WAITING_APPROVAL");
        scans.addTask(bad);
        scans.addTask(row("READY"));
        scans.addRun(row("SUPERSEDED"));

        RcaStateBackfillJob.BackfillReport report = new RcaStateBackfillJob(scans, 10).run();

        assertThat(report.violations()).hasSize(1);
        assertThat(report.violations().get(0))
                .isEqualTo("rca_task|" + bad.id() + "|WAITING_APPROVAL");
        // 违规行也计入行数与分布（诚实对账），合法行继续被处理
        assertThat(report.taskRows()).isEqualTo(2);
        assertThat(report.runRows()).isEqualTo(1);
        assertThat(report.taskStateCounts()).containsEntry("WAITING_APPROVAL", 1L)
                .containsEntry("READY", 1L);
        assertThat(report.runStateCounts()).containsEntry("SUPERSEDED", 1L);
    }

    @Test
    void batchSizeMustBePositive() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new RcaStateBackfillJob(new InMemoryScans(), 0))
                .withMessageContaining("batchSize");
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new RcaStateBackfillJob(new InMemoryScans(), -1));
    }
}
