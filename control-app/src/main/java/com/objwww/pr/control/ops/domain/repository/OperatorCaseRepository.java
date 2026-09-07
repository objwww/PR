package com.objwww.pr.control.ops.domain.repository;

import com.objwww.pr.control.ops.domain.model.OperatorCase;

import java.util.Optional;
import java.util.UUID;

/**
 * OperatorCase 仓储端口（M5-11；零框架，实现方 infrastructure.persistence）。
 *
 * <p>并发语义：lockByTenantAndFingerprint = SELECT ... FOR UPDATE（幂等合并键的
 * 串行化锚，E-17 keep db.py 同构）；update = expected-revision CAS 全列写，
 * 返回 false 即并发冲突（恰一人认领成功的物理前提）。
 */
public interface OperatorCaseRepository {

    void insert(OperatorCase operatorCase);

    /** 幂等合并键行锁（FOR UPDATE）；无该键行返回 empty */
    Optional<OperatorCase> lockByTenantAndFingerprint(String tenant, String fingerprint);

    /** 无锁读投影（M5-12 查询面共用） */
    Optional<OperatorCase> findById(UUID id);

    /** expected-revision CAS 全列更新；0 行命中 = 并发冲突（不改历史行） */
    boolean update(OperatorCase operatorCase, long expectedRevision);
}
