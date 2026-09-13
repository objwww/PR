package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.ReportFeedback;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 报告反馈端口（OP-04，V103）：append-only，幂等插入（(author, idempotency_key)
 * 冲突返回既有行——同载荷重放收敛、异载荷由服务层显式冲突）；同一前序的并发
 * 更正以 uq(supersedes_id) 一胜一拒（返回既有更正行由服务层裁决语义）。
 */
public interface ReportFeedbackPort {

    /** 幂等插入：同 (author, idempotencyKey) 已存在 → 返回既有行 */
    ReportFeedback insert(ReportFeedback candidate);

    List<ReportFeedback> findByReportId(UUID reportId);

    Optional<ReportFeedback> findById(UUID id);
}
