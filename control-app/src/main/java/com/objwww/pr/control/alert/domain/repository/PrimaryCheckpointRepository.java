package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;

import java.util.Optional;
import java.util.UUID;

/**
 * 主任务检查点端口（R7-X4，V47 rca_primary_checkpoint）。task_id 主键 = 一主任务
 * 一检查点；单写者纪律由任务领取（lease）保证，upsert 末写胜出即安全。
 * 实现方每方法自含短事务。
 *
 * <p>CL-01（V99）提交围栏：运行路径提交弃用无条件 upsert，统一走
 * PrimaryCheckpointCommitService —— 锁读（{@link #findByTaskForUpdate}）+
 * revision 条件写（{@link #updateGuarded}，影响行数必须 1）+ 动作身份去重
 * （同动作重复提交 REPLAYED）；初始化走 {@link #insertIfAbsent}。
 */
public interface PrimaryCheckpointRepository {

    /** 起跑初始化或全量推进落账（task_id 幂等锚） */
    void upsert(PrimaryCheckpoint checkpoint);

    /**
     * CL-01 初始化专用：不存在才插入（竞态缺席者胜，返回 false）。初始化不校验
     * 租约（无租约的起跑面），不得复用本入口做运行提交。
     */
    default boolean insertIfAbsent(PrimaryCheckpoint initial) {
        if (findByTask(initial.taskId()).isPresent()) {
            return false;
        }
        upsert(initial);
        return true;
    }

    Optional<PrimaryCheckpoint> findByTask(UUID taskId);

    /**
     * CL-01 行锁读（提交围栏锁序 task→run→checkpoint 的第三锁，必须在调用方事务内）。
     * 默认回退非锁读（测试 fake 兼容）；生产实现必须 SELECT ... FOR UPDATE。
     */
    default Optional<PrimaryCheckpoint> findByTaskForUpdate(UUID taskId) {
        return findByTask(taskId);
    }

    /** CL-01 锁读 + 最近动作身份（REPLAYED 判定锚；检查点行不存在时为 null） */
    record CommitState(PrimaryCheckpoint checkpoint, String lastActionKey,
                       String lastActionDigest) {
    }

    /**
     * CL-01 行锁读含动作身份（同 {@link #findByTaskForUpdate} 的锁语义 + 动作列）。
     * 默认实现动作身份为 null（假件无去重面）；生产实现必须真读 last_action_*。
     */
    default CommitState findCommitStateForUpdate(UUID taskId) {
        return findByTaskForUpdate(taskId)
                .map(cp -> new CommitState(cp, null, null)).orElse(null);
    }

    /**
     * CL-01 revision 条件写（提交围栏线性化点）：全列落账 + revision+1 +
     * 动作身份列回填，{@code WHERE task_id=? AND revision=:expectedRevision}。
     * 影响行数必须 1（0 = 并发修订已推进/记录不存在，调用方拿 STALE_REVISION
     * 立即退出本次驱动）。默认不可用（条件写必须真实现）。
     */
    default long updateGuarded(PrimaryCheckpoint next, long expectedRevision,
            String actionKey, String actionDigest) {
        throw new UnsupportedOperationException(
                "updateGuarded 需原子条件写实现: " + getClass().getName());
    }

    /**
     * 相位 CAS（唤醒/就绪迁移的并发安全面）：仅当现相位 = from 才迁 to，返回是否生效。
     * 不触碰其余计数列（单步推进经 upsert 全量落账）。
     */
    boolean transitionPhase(UUID taskId, PrimaryCheckpoint.Phase from,
            PrimaryCheckpoint.Phase to);
}
