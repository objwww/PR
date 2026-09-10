package com.objwww.pr.control.eval.domain.repository;

import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.EvalRun;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * eval_run / eval_case_result 接口（M3-14；同 EvalRun 不可覆盖语义的落点）。
 *
 * <p>SQL 契约：insertRunning 主键冲突直接上抛（同 run id 禁重生）；
 * finalizeOnce 以 {@code state='RUNNING'} CAS 一次性回填终态聚合（0 行 = 已终结拒绝）；
 * insertCaseResult 撞 UNIQUE(eval_run_id, scenario_id, round_no) 返回 false
 * （同 case 同轮禁覆盖，两轮 = 两条独立记录）。
 */
public interface EvalRunRepository {

    /** 开跑行插入（仅 RUNNING 形态）；同 id 二次插入抛 DuplicateKeyException */
    void insertRunning(EvalRun running);

    /** 终态 CAS：仅 RUNNING 行可迁移；0 行 = 已终结/不存在，返回 false */
    boolean finalizeOnce(EvalRun terminal);

    /**
     * EV-04 发起身份回填（V81 列级授权面：display_name/mode/launch_plan 三列）：
     * worker 领取 LAUNCH 命令后一次性落计划快照；0 行 = run 不存在返回 false。
     */
    boolean applyLaunchIdentity(UUID runId, String displayName, String mode,
                                String launchPlanJson);

    /** EV-04 恢复分面推进（V81 recovery_state 列）；0 行 = run 不存在返回 false */
    boolean updateRecoveryState(UUID runId, String recoveryState);

    /** 逐案例评分插入；同 (run, scenario, round) 已存在 = false（不覆盖） */
    boolean insertCaseResult(EvalCaseResult result);

    Optional<EvalRun> findById(UUID runId);

    List<EvalCaseResult> findCasesByRunId(UUID runId);
}
