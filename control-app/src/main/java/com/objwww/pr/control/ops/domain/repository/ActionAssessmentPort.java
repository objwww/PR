package com.objwww.pr.control.ops.domain.repository;

import com.objwww.pr.control.ops.domain.model.ActionAssessment;

import java.util.List;
import java.util.UUID;

/**
 * 动作分析派生台账端口（OP-03，V105）：只增不改——同 (run, logical_action_key,
 * assessor_version, evidence_snapshot_digest) 冲突返回既有行（FO15 重入不重复）；
 * 修正/重算 = 新版本或新快照的新行，旧行永不 UPDATE/DELETE。
 */
public interface ActionAssessmentPort {

    /** 幂等插入：唯一键冲突 → 返回既有行 */
    ActionAssessment insertIfAbsent(ActionAssessment row);

    /** 某 run 的分析行（logical_action_key 序） */
    List<ActionAssessment> findByRun(UUID runId);
}
