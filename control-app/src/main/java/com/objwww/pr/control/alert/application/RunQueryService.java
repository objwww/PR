package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.dag.TaskEdge;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * runs 队列/详情只读投影（M5-13；落码方案 §M5-13③，字段级以 mocks/runs.js 为准）。
 *
 * <p>C-18 决策面（账本留痕）：
 * <ul>
 *   <li>① severity/owner：rca_run/rca_incident 均无该列 → 如实 null（不冒充）；
 *       owner 认领面属 AM7 operator_case 域，runs 无认领语义；</li>
 *   <li>② engine/config：新读端口 {@link RcaRunRepository#findRoutingById} 读 V25
 *       路由四列（读视图独立于铸造校验面 RcaRunRouting）；</li>
 *   <li>③ budget/projectionLag：预算账本无读面、投影延迟无观测面 → 如实 null
 *       （不回填示意值）；</li>
 *   <li>④ bucket 只由 run+task 状态推导：mine（认领）/cursor 分页不落，summary.mine
 *       恒 0、nextCursor 恒 null（开放项 O-5）；SUCCEEDED/PARTIAL 落位 review
 *       （报告待人工复核，暂无复核状态机——诚实近似）；</li>
 *   <li>⑤ 鉴权沿 O-4 过渡形态：operator bearer + X-Operator-Id（控制器面）。</li>
 * </ul>
 */
public class RunQueryService {

    private final RcaRunRepository runs;
    private final RcaTaskRepository tasks;
    private final TaskEdgeRepository edges;
    private final Supplier<Instant> now;

    public RunQueryService(RcaRunRepository runs, RcaTaskRepository tasks,
                           TaskEdgeRepository edges, Supplier<Instant> now) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.edges = Objects.requireNonNull(edges, "edges");
        this.now = Objects.requireNonNull(now, "now");
    }

    /** 队列页：summary 计数 + 全量 rows（O-5：游标分页未落，nextCursor 恒 null） */
    public Map<String, Object> list() {
        Instant at = now.get();
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean anyOverSla = false;
        Instant oldestReady = null;
        for (RcaRun run : runs.findAll()) {
            List<RcaTask> runTasks = tasks.findByRunId(run.id());
            rows.add(row(run, runTasks, at));
            for (RcaTask t : runTasks) {
                if (overSla(at, t)) {
                    anyOverSla = true;
                    break;
                }
            }
            for (RcaTask t : runTasks) {
                if (t.state() == RcaTaskState.READY
                        && (oldestReady == null || t.readySince().isBefore(oldestReady))) {
                    oldestReady = t.readySince();
                }
            }
        }
        Map<String, Object> buckets = new LinkedHashMap<>();
        buckets.put("mine", 0);
        buckets.put("running", count(rows, "running"));
        buckets.put("stuck", count(rows, "stuck"));
        buckets.put("failed", count(rows, "failed"));
        buckets.put("review", count(rows, "review"));

        Map<String, Object> sla = new LinkedHashMap<>();
        sla.put("overSla", anyOverSla ? 1 : 0);
        sla.put("oldestReadyWait", oldestReady == null ? null : human(Duration.between(oldestReady, at)));
        sla.put("projectionLag", null);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("buckets", buckets);
        summary.put("sla", sla);
        summary.put("updatedAt", at.toString());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("rows", rows);
        out.put("nextCursor", null);
        return out;
    }

    /** 详情页：run 头 + 任务/边 DAG 投影（payload 只含白名单摘要/引用/digest）；未知 run → empty */
    public Optional<Map<String, Object>> detail(UUID runId) {
        return runs.findById(runId).map(run -> {
            List<RcaTask> runTasks = tasks.findByRunId(runId);
            Map<String, Object> head = new LinkedHashMap<>();
            head.put("id", run.id().toString());
            head.put("incident", run.incidentId().toString());
            head.put("status", run.state().name());
            head.put("severity", null);
            runs.findRoutingById(runId).ifPresentOrElse(routing -> {
                head.put("engine", routing.engine().name());
                head.put("config", routing.configDigest());
            }, () -> {
                head.put("engine", null);
                head.put("config", null);
            });
            head.put("progress", progress(runTasks));
            head.put("budget", null);

            List<Map<String, Object>> taskRows = new ArrayList<>();
            Map<String, String> keyById = new LinkedHashMap<>();
            for (RcaTask t : runTasks) {
                keyById.put(t.id().toString(), t.taskKey());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", t.taskKey());
                row.put("taskId", t.id().toString());
                row.put("status", t.state().name());
                row.put("priority", t.priority());
                row.put("deadline", t.deadlineAt().equals(Instant.MAX) ? null : t.deadlineAt().toString());
                row.put("lease", t.leaseOwner() == null ? null : Map.of(
                        "worker", t.leaseOwner(), "epoch", t.leaseEpoch()));
                row.put("attempts", t.attemptCount());
                taskRows.add(row);
            }
            List<Map<String, Object>> edgeRows = new ArrayList<>();
            for (TaskEdge e : edges.findByRunId(runId)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", keyById.getOrDefault(e.fromTaskId(), e.fromTaskId()));
                row.put("target", keyById.getOrDefault(e.toTaskId(), e.toTaskId()));
                row.put("type", e.dependencyType().name());
                edgeRows.add(row);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("run", head);
            out.put("tasks", taskRows);
            out.put("edges", edgeRows);
            return out;
        });
    }

    // ------------------------------------------------------------------ 行投影

    private Map<String, Object> row(RcaRun run, List<RcaTask> runTasks, Instant at) {
        RcaTask stuck = stuckTask(runTasks, at);
        String bucket = bucketOf(run.state(), stuck != null);
        RcaTaskState stageState = stuck != null ? stuck.state() : null;
        String stage = stageState != null ? stageState.name() : run.state().name();

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", run.id().toString());
        row.put("incident", run.incidentId().toString());
        row.put("severity", null);
        row.put("bucket", bucket);
        row.put("stage", stage);
        row.put("stageZh", stageZh(stageState, run.state()));
        row.put("stageTone", stageTone(stageState, run.state()));
        row.put("progress", progressLine(runTasks));
        row.put("blocker", blockerOf(run, stuck));
        row.put("owner", null);
        row.put("duration", durationOf(run, at));
        row.put("action", "view");
        return row;
    }

    /** 卡住判据（C-18④ 状态推导口径）：BLOCKED / RETRY_WAIT / LEASED 超 deadline */
    private RcaTask stuckTask(List<RcaTask> runTasks, Instant at) {
        for (RcaTask t : runTasks) {
            if (t.state() == RcaTaskState.BLOCKED || t.state() == RcaTaskState.RETRY_WAIT
                    || (t.state() == RcaTaskState.LEASED && overSla(at, t))) {
                return t;
            }
        }
        return null;
    }

    private boolean overSla(Instant at, RcaTask t) {
        return !t.deadlineAt().equals(Instant.MAX) && t.deadlineAt().isBefore(at)
                && !isTerminal(t.state());
    }

    private String bucketOf(RcaRunState state, boolean stuck) {
        if (state.isActive()) {
            return stuck ? "stuck" : "running";
        }
        return switch (state) {
            case FAILED, EXPIRED -> "failed";
            case SUCCEEDED, PARTIAL -> "review";
            case CANCELLED, SUPERSEDED -> "done";
            default -> "running";
        };
    }

    private static boolean isTerminal(RcaTaskState state) {
        return state == RcaTaskState.DONE || state == RcaTaskState.CANCELLED
                || state == RcaTaskState.DEAD || state == RcaTaskState.SKIPPED
                || state == RcaTaskState.FAILED_TERMINAL || state == RcaTaskState.STALE;
    }

    private static Map<String, Object> progress(List<RcaTask> runTasks) {
        int done = 0;
        int running = 0;
        int blocked = 0;
        for (RcaTask t : runTasks) {
            if (t.state() == RcaTaskState.DONE || t.state() == RcaTaskState.SKIPPED) {
                done++;
            } else if (t.state() == RcaTaskState.RUNNING || t.state() == RcaTaskState.LEASED) {
                running++;
            } else if (!isTerminal(t.state())) {
                blocked++;
            }
        }
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("done", done);
        progress.put("running", running);
        progress.put("blocked", blocked);
        progress.put("total", runTasks.size());
        return progress;
    }

    private static String progressLine(List<RcaTask> runTasks) {
        if (runTasks.isEmpty()) {
            return "—";
        }
        long done = runTasks.stream()
                .filter(t -> t.state() == RcaTaskState.DONE || t.state() == RcaTaskState.SKIPPED)
                .count();
        return done + "/" + runTasks.size();
    }

    private static String blockerOf(RcaRun run, RcaTask stuck) {
        if (run.lastError() != null && !run.lastError().isBlank()) {
            return run.lastError();
        }
        return stuck == null ? null : stuck.taskKey() + ":" + stuck.state().name();
    }

    private static String durationOf(RcaRun run, Instant at) {
        if (run.startedAt() == null) {
            return "—";
        }
        Instant end = run.finishedAt() != null ? run.finishedAt() : at;
        return "运行 " + human(Duration.between(run.startedAt(), end));
    }

    private static String human(Duration d) {
        long seconds = Math.max(0, d.getSeconds());
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        return minutes / 60 + "h" + minutes % 60 + "m";
    }

    // ------------------------------------------------------ 展示词典（M7-09 前端可覆盖）

    private static String stageZh(RcaTaskState stuckState, RcaRunState runState) {
        if (stuckState != null) {
            return switch (stuckState) {
                case BLOCKED -> "卡住";
                case RETRY_WAIT -> "等待重试";
                default -> "卡住";
            };
        }
        return switch (runState) {
            case QUEUED -> "等待";
            case RUNNING -> "执行中";
            case REPORTING -> "报告组装中";
            case SUCCEEDED -> "待审查";
            case FAILED, EXPIRED -> "失败";
            case PARTIAL -> "部分完成";
            case CANCELLED -> "已取消";
            case SUPERSEDED -> "已被取代";
        };
    }

    private static String stageTone(RcaTaskState stuckState, RcaRunState runState) {
        if (stuckState != null) {
            return stuckState == RcaTaskState.RETRY_WAIT ? "orange" : "red";
        }
        return switch (runState) {
            case QUEUED, CANCELLED, SUPERSEDED -> "gray";
            case RUNNING, REPORTING -> "blue";
            case SUCCEEDED, PARTIAL -> "orange";
            case FAILED, EXPIRED -> "red";
        };
    }

    private static int count(List<Map<String, Object>> rows, String bucket) {
        return (int) rows.stream().filter(r -> bucket.equals(r.get("bucket"))).count();
    }
}
