package com.objwww.pr.control.drill.domain.repository;

import com.objwww.pr.control.drill.domain.model.DrillJob;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DR-02 演练作业仓储（V86 drill_job；同一接口服务两个 DB 身份，授权面在库侧收口）：
 * <ul>
 *   <li>control_app（/api/drills 面）：insert / find 系 / list / count 系 /
 *       requestStop——select,insert + 停止两列 update 授权内，状态机推进零开口；</li>
 *   <li>eval_app（worker）：claimNext/advance/finalize/requeue/findOrphanedClaims——
 *       列级 update 授权内，正文列（场景/参数/幂等键/发起人）零开口。</li>
 * </ul>
 * claim/推进全部单语句 CAS（SKIP LOCKED + revision 对账）：多 worker 并发恰一人领到，
 * 租约过期不等于可以把有副作用的动作重做一遍（§7.4）。
 */
public interface DrillJobRepository {

    void insert(DrillJob job);

    Optional<DrillJob> findById(UUID id);

    Optional<DrillJob> findByIdempotencyKey(String idempotencyKey);

    Optional<DrillJob> findByStopKey(String stopIdempotencyKey);

    /** 同靶场活动占位（含 RECOVERY_FAILED；excludeId 用于 worker 自检排除自身） */
    Optional<DrillJob> findActiveOccupant(String targetEnv, UUID excludeId);

    /** 列表投影（键集游标：created_at DESC, id DESC；cursor = "createdAtMicros|id"） */
    List<DrillJob> list(String state, String cursor, int limit);

    long countActive();

    long countRecoveryFailed();

    /** 停止受理 CAS：仅活动中且未受理过才置位（重复停止幂等，DU14；只写 stop 两列，
     *  不碰 updated_at——control_app 列级授权边界） */
    boolean requestStop(UUID id, String stopIdempotencyKey, Instant stopRequestedAt);

    /** 领取 = 单语句 CAS 且领取即迁移 QUEUED→PRECHECK（BA-114：与 EVAL
     *  claimNextLaunch 同律，行在领取语句提交时即离开 QUEUED 可见集）；
     *  SKIP LOCKED 防多 worker 撞同一行 */
    Optional<DrillJob> claimNext(String workerId, Instant claimedAt);

    /** 相位推进 CAS（state+revision 双对账；非终态目标） */
    boolean advance(UUID id, long expectedRevision, DrillJob.State from, DrillJob.State to,
                    Instant updatedAt);

    /** 终态化 CAS（FAILED/CANCELLED/RECOVERY_FAILED/CLOSED；outcome 仅 CLOSED 携带） */
    boolean finalize(UUID id, long expectedRevision, DrillJob.State from, DrillJob.State to,
                     String terminalReason, String outcome, Instant closedAt, Instant updatedAt);

    /** 崩溃恢复：worker 失联时对账扫描（租约超龄且非终态；QUEUED 谓词仅兜底
     *  旧行——BA-114 后领取即 PRECHECK，claim 不再产生 QUEUED+租约行） */
    List<DrillJob> findOrphanedClaims(Instant claimedBefore);

    /** 孤儿重排队（QUEUED/PRECHECK——尚未触及注入零副作用；身份稳定不换 id） */
    boolean requeue(UUID id, long expectedRevision, Instant updatedAt);
}
