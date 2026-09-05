package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.InvestigationResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * rca_investigation_result 接口（M3-04 全程落档；只 INSERT + SELECT + 终态列级 UPDATE）。
 *
 * <p>SQL 契约：insertStartedIfAbsent 在 attempt 铸造同事事务落 STARTED 行
 * （attempt_id 幂等锚，冲突重读）；finishTerminal 以
 * {@code execution_status='STARTED'} CAS 回写终态——进程在外调后落库前被杀也留悬挂
 * STARTED 可查；两写都带 observed_generation 栅栏（与 rca_run.generation 不一致 = 晚到
 * 旧代写入，0 行拒绝，FUT-50）。
 */
public interface InvestigationResultRepository {

    /** STARTED 先行；attempt_id 已存在时不覆盖（幂等锚），返回库中现行 */
    InvestigationResult insertStartedIfAbsent(InvestigationResult started);

    /** 终态 CAS：仅 STARTED 行可迁移 + generation 栅栏；0 行 = 拒绝（晚到/已收尾） */
    boolean finishTerminal(InvestigationResult terminal);

    /** 悬挂 STARTED（created_at < olderThan）→ 崩溃回收标 UNKNOWN 的扫描入口 */
    List<InvestigationResult> findHangingStarted(Instant olderThan);

    Optional<InvestigationResult> findByAttemptId(UUID attemptId);

    List<InvestigationResult> findByRunId(UUID runId);
}
