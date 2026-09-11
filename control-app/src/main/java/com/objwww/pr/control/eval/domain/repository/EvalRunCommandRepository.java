package com.objwww.pr.control.eval.domain.repository;

import com.objwww.pr.control.eval.domain.model.EvalRunCommand;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * eval_run_command 端口（EV-04，V81；V27 OperatorCommandRepository 同构）。
 *
 * <p>SQL 契约：
 * <ul>
 *   <li>insert 撞 uq(command_type, idempotency_key) 抛 DuplicateKeyException(23505)
 *       ——并发同键双提交恰一行落库（败者读胜者行比对 payload_hash：同 = 重放，
 *       异 = 409，EU09）；</li>
 *   <li>claimNextLaunch 单语句 CAS（子查询 FOR UPDATE SKIP LOCKED 锁最老 PENDING
 *       LAUNCH 行）——多 worker 并发恰一人领取；</li>
 *   <li>finish/requeue 以当前 state 限定 CAS 推进；正文列（type/key/payload/hash/
 *       actor）落库后零开口（control_app 无 update 授权，eval_app 仅状态三列 +
 *       worker_id 列级写面）。</li>
 * </ul>
 */
public interface EvalRunCommandRepository {

    /** 先持久化（PENDING）；同 (type, key) 并发撞 uq 抛 DuplicateKeyException */
    void insert(EvalRunCommand command);

    /** 幂等重放/409 比对的查找面 */
    Optional<EvalRunCommand> findByKey(EvalRunCommand.Type type, String idempotencyKey);

    /** 该 run 是否已有被受理的 CANCEL 命令（PENDING/CLAIMED/DONE；worker 检查点信号） */
    boolean cancelAccepted(UUID evalRunId);

    /** 该 run 最近一条被受理 CANCEL 的提交时刻（读面 cancel_requested_at 备选；无 → empty） */
    Optional<Instant> cancelRequestedAt(UUID evalRunId);

    /** 领取最老 PENDING LAUNCH（CAS PENDING→CLAIMED）；无可领 → empty */
    Optional<EvalRunCommand> claimNextLaunch(String workerId, Instant claimedAt);

    /** 终态推进（DONE/FAILED）；CLAIMED 行才可推进，0 行 = 并发已推进返回 false */
    boolean finish(UUID id, EvalRunCommand.State terminal, Instant finishedAt);

    /** worker 启动孤儿面：CLAIMED 且 claimed_at 早于 before 的命令（失联判定窗口） */
    List<EvalRunCommand> findOrphanedClaims(Instant before);

    /** 孤儿重排队（CLAIMED→PENDING，清 worker/领取时刻）；仅 run 行从未落库时调用 */
    boolean requeue(UUID id);
}
