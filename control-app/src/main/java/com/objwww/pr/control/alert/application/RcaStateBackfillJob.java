package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.repository.RcaStateScanRepository;
import com.objwww.pr.control.alert.domain.repository.RcaStateScanRepository.StateRow;
import com.objwww.pr.control.alert.domain.statemachine.RcaStateContract;
import com.objwww.pr.control.infrastructure.observability.StructuredLog;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 状态数据回填/校验作业（M4-03）：键集分批扫全量 rca_task/rca_run，
 * 逐行过 {@code RcaStateContract} 契约校验（fail-closed 收集违规行，不断不抛），
 * 产出 行数/状态分布/sha256 digest 对账单。
 *
 * <p>V12 为 additive 扩容（旧值冻结不改名）——本口径下没有需要改写的存量行，
 * 作业即"全表契约校验 + 对账取证"；未来若出现需改写状态值的迁移，
 * 本框架的分批/断点/对账骨架即回填载体。只读不写：任意时刻中断直接重跑，
 * 两次运行的行数与 digest 必须一致（行数/digest 对账验收）。
 */
public final class RcaStateBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(RcaStateBackfillJob.class);

    private final RcaStateScanRepository scans;
    private final int batchSize;

    public RcaStateBackfillJob(RcaStateScanRepository scans, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须 > 0");
        }
        this.scans = scans;
        this.batchSize = batchSize;
    }

    /** 对账单：行数 + 状态分布 + 违规行 + digest（状态分布排序后 sha256，稳定可比） */
    public record BackfillReport(long taskRows, long runRows,
                                 Map<String, Long> taskStateCounts,
                                 Map<String, Long> runStateCounts,
                                 List<String> violations,
                                 String digest,
                                 int batchesProcessed) {
    }

    public BackfillReport run() {
        Progress tasks = scanTable("rca_task", scans::scanTaskStates);
        Progress runs = scanTable("rca_run", scans::scanRunStates);

        StringBuilder canonical = new StringBuilder();
        appendLedger(canonical, "rca_task", tasks.counts);
        appendLedger(canonical, "rca_run", runs.counts);
        String digest = Digest.sha256Of(canonical.toString()).value();

        StructuredLog.event(log, "rca_state_backfill_reconciled", Map.of(
                "task_rows", tasks.rows, "run_rows", runs.rows,
                "violations", tasks.violations.size() + runs.violations.size(),
                "batches", tasks.batches + runs.batches, "digest", digest));

        List<String> violations = new ArrayList<>(tasks.violations);
        violations.addAll(runs.violations);
        return new BackfillReport(tasks.rows, runs.rows,
                sorted(tasks.counts), sorted(runs.counts),
                List.copyOf(violations), digest, tasks.batches + runs.batches);
    }

    // ------------------------------------------------------------------ 内部

    private static final class Progress {
        long rows;
        int batches;
        final Map<String, Long> counts = new TreeMap<>();
        final List<String> violations = new ArrayList<>();
    }

    private Progress scanTable(String table, TableScanner scanner) {
        Progress progress = new Progress();
        UUID after = null;
        while (true) {
            List<StateRow> batch = scanner.scan(after, batchSize);
            // 键集推进守卫：扫描器契约 = id 升序；批次不前进立即失败（防死循环吞内存）
            if (after != null && !batch.isEmpty()
                    && batch.get(0).id().compareTo(after) <= 0) {
                throw new IllegalStateException(
                        table + " 扫描器键集未按 id 升序推进 after=" + after
                                + " first=" + batch.get(0).id());
            }
            for (StateRow row : batch) {
                progress.rows++;
                progress.counts.merge(row.state(), 1L, Long::sum);
                try {
                    if ("rca_task".equals(table)) {
                        RcaStateContract.parseTaskState(row.state());
                    } else {
                        RcaStateContract.parseRunState(row.state());
                    }
                } catch (IllegalArgumentException e) {
                    progress.violations.add(table + "|" + row.id() + "|" + row.state());
                }
            }
            progress.batches++;
            StructuredLog.event(log, "rca_state_backfill_progress", Map.of(
                    "table", table, "batches", progress.batches,
                    "rows", progress.rows, "violations", progress.violations.size()));
            if (batch.size() < batchSize) {
                return progress;
            }
            after = batch.get(batch.size() - 1).id();
        }
    }

    @FunctionalInterface
    private interface TableScanner {
        List<StateRow> scan(UUID afterId, int limit);
    }

    /** 对账账本行：table|state|count，按 state 排序——同一数据两次运行逐字节一致 */
    private static void appendLedger(StringBuilder sb, String table, Map<String, Long> counts) {
        counts.forEach((state, count) -> sb.append(table).append('|')
                .append(state).append('|').append(count).append('\n'));
    }

    private static Map<String, Long> sorted(Map<String, Long> counts) {
        return Map.copyOf(new TreeMap<>(counts));
    }
}
