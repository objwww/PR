package com.objwww.pr.control.alert.domain.statemachine;

import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;

import java.util.EnumSet;
import java.util.Set;

/**
 * RCA 状态新旧双读契约（M4-01，评审 v1.1 修正④冻结口径）：
 * 持久层读出的状态串只能经本契约解析——AM1 旧值恒等映射、AM4 新值直读，
 * 契约外取值（含 AM5 才引入的 WAITING_APPROVAL 与一切未知串）一律拒绝（fail-closed），
 * 不再直接 {@code Enum.valueOf}。
 *
 * <p>契约集与 {@code RcaTaskState}/{@code RcaRunState} 枚举全集精确互斥完备
 * （LEGACY ∪ AM4 = 全集且 LEGACY ∩ AM4 = ∅，由 RcaStateContractTest 穷举锚定）；
 * DB 侧 CHECK 同步在 V12 迁移（M4-02）落地，此前 DB 不可能给出 AM4 新值。
 */
public final class RcaStateContract {

    /** AM1 旧六态（V7 ck_rca_task_state 字面量，语义冻结不改名） */
    public static final Set<RcaTaskState> LEGACY_TASK_STATES = EnumSet.of(
            RcaTaskState.READY, RcaTaskState.LEASED, RcaTaskState.RETRY_WAIT,
            RcaTaskState.DONE, RcaTaskState.CANCELLED, RcaTaskState.DEAD);

    /** AM4 新增五态（V12 起入库；BLOCKED/RUNNING/SKIPPED/FAILED_TERMINAL/STALE） */
    public static final Set<RcaTaskState> AM4_TASK_STATES = EnumSet.of(
            RcaTaskState.BLOCKED, RcaTaskState.RUNNING, RcaTaskState.SKIPPED,
            RcaTaskState.FAILED_TERMINAL, RcaTaskState.STALE);

    /** AM1 旧六态（V7 ck_rca_run_state 字面量，语义冻结不改名） */
    public static final Set<RcaRunState> LEGACY_RUN_STATES = EnumSet.of(
            RcaRunState.QUEUED, RcaRunState.RUNNING, RcaRunState.SUCCEEDED,
            RcaRunState.FAILED, RcaRunState.CANCELLED, RcaRunState.SUPERSEDED);

    /** AM4 新增三态（V12 起入库；REPORTING/PARTIAL/EXPIRED） */
    public static final Set<RcaRunState> AM4_RUN_STATES = EnumSet.of(
            RcaRunState.REPORTING, RcaRunState.PARTIAL, RcaRunState.EXPIRED);

    private RcaStateContract() {
    }

    /** rca_task.state 双读解析：契约外取值抛 IllegalArgumentException（fail-closed） */
    public static RcaTaskState parseTaskState(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("rca_task.state 为空，拒绝解析");
        }
        return switch (raw) {
            case "READY" -> RcaTaskState.READY;
            case "LEASED" -> RcaTaskState.LEASED;
            case "RETRY_WAIT" -> RcaTaskState.RETRY_WAIT;
            case "DONE" -> RcaTaskState.DONE;
            case "CANCELLED" -> RcaTaskState.CANCELLED;
            case "DEAD" -> RcaTaskState.DEAD;
            case "BLOCKED" -> RcaTaskState.BLOCKED;
            case "RUNNING" -> RcaTaskState.RUNNING;
            case "SKIPPED" -> RcaTaskState.SKIPPED;
            case "FAILED_TERMINAL" -> RcaTaskState.FAILED_TERMINAL;
            case "STALE" -> RcaTaskState.STALE;
            default -> throw new IllegalArgumentException(
                    "rca_task.state 契约外取值: " + raw);
        };
    }

    /** rca_run.state 双读解析：契约外取值抛 IllegalArgumentException（fail-closed） */
    public static RcaRunState parseRunState(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("rca_run.state 为空，拒绝解析");
        }
        return switch (raw) {
            case "QUEUED" -> RcaRunState.QUEUED;
            case "RUNNING" -> RcaRunState.RUNNING;
            case "SUCCEEDED" -> RcaRunState.SUCCEEDED;
            case "FAILED" -> RcaRunState.FAILED;
            case "CANCELLED" -> RcaRunState.CANCELLED;
            case "SUPERSEDED" -> RcaRunState.SUPERSEDED;
            case "REPORTING" -> RcaRunState.REPORTING;
            case "PARTIAL" -> RcaRunState.PARTIAL;
            case "EXPIRED" -> RcaRunState.EXPIRED;
            default -> throw new IllegalArgumentException(
                    "rca_run.state 契约外取值: " + raw);
        };
    }
}
