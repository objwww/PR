package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.agent.DelegationDecision;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 委派裁决台账端口（R7-X4/X11，V47 rca_delegation_decision）。行=既成事实：
 * 只增不改（child_task_id 回填除外）。uq(run_id, gap_id) 冲突 = 缺口已裁决
 * （调用方按幂等短路处理，实现方原样抛 DuplicateKeyException）。
 */
public interface DelegationDecisionRepository {

    /** 裁决事务内写入（同事务性由调用方事务边界保证） */
    void insert(DelegationDecision decision);

    /** 去重判定：同 run 同信息缺口是否已裁决 */
    Optional<DelegationDecision> findByRunAndGap(UUID runId, String gapId);

    /** 主任务全部裁决（seq 序稳定；唤醒复判/恢复读） */
    List<DelegationDecision> findByRunAndPrimaryTask(UUID runId, UUID primaryTaskId);
}
