package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;

import java.util.Optional;
import java.util.UUID;

/**
 * 主任务检查点端口（R7-X4，V47 rca_primary_checkpoint）。task_id 主键 = 一主任务
 * 一检查点；单写者纪律由任务领取（lease）保证，upsert 末写胜出即安全。
 * 实现方每方法自含短事务。
 */
public interface PrimaryCheckpointRepository {

    /** 起跑初始化或全量推进落账（task_id 幂等锚） */
    void upsert(PrimaryCheckpoint checkpoint);

    Optional<PrimaryCheckpoint> findByTask(UUID taskId);

    /**
     * 相位 CAS（唤醒/就绪迁移的并发安全面）：仅当现相位 = from 才迁 to，返回是否生效。
     * 不触碰其余计数列（单步推进经 upsert 全量落账）。
     */
    boolean transitionPhase(UUID taskId, PrimaryCheckpoint.Phase from,
            PrimaryCheckpoint.Phase to);
}
