package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.OperatorCommand;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * operator_command 端口（M5-14，V27）。
 *
 * <p>SQL 契约：insert 撞 uq(run_id, command_type, idempotency_key) 抛
 * DuplicateKeyException(23505)——并发同键双提交恰一行落库（败者读胜者行，幂等）。
 * updateState 是唯一 UPDATE 面（state/applied_at 两列，列级授权同构）；正文列
 * （type/key/expected_revision/payload/actor）落库后零开口。
 */
public interface OperatorCommandRepository {

    /** 先持久化（PERSISTED）；同键并发撞 uq 抛 DuplicateKeyException */
    void insert(OperatorCommand command);

    /** 幂等重放/续走 apply 的查找面 */
    Optional<OperatorCommand> find(UUID runId, OperatorCommand.Type type, String idempotencyKey);

    /** 终态推进（APPLIED/REJECTED_*）；1=本次推进生效，0=已推进（并发/重放） */
    boolean updateState(UUID id, OperatorCommand.State state, Instant appliedAt);
}
