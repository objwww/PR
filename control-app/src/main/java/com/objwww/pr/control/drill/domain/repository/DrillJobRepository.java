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
 *       requestStop / requestRetry——select,insert + 停止/重试意图列 update 授权内，
 *       状态机推进零开口（重试意图由 worker 消费后才推进相位）；</li>
 *   <li>eval_app（worker）：claimNext/advance/finalize/requeue/findOrphanedClaims/
 *       findRetryRequests/consumeRetry——
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

    /** DR-04 人工重试意图 CAS（control_app 面；对称 requestStop 的意图列模式——
     *  HTTP 受理只置 retry_requested_at，RECOVERY_FAILED→RECOVERING 推进归 worker）：
     *  仅 RECOVERY_FAILED 且未受理过才置位（重复重试幂等） */
    boolean requestRetry(UUID id, Instant retryRequestedAt);

    /** DR-04 重试意图扫描面（eval_app worker）：有待消费重试意图的 RECOVERY_FAILED 作业 */
    List<DrillJob> findRetryRequests();

    /** DR-04 重试消费 CAS（eval_app worker）：单语句推进 RECOVERY_FAILED→RECOVERING
     *  + 清意图列 + 刷新租约（worker_id/claimed_at）——与并发重复消费/截止对账恰一方
     *  生效（状态机人工重试边 §7.4） */
    boolean consumeRetry(UUID id, long expectedRevision, String workerId, Instant now);

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

    /** DR-05/DR-06 worker 扫描面：指定相位集的活动中作业（flagd 作业级截止对账与
     *  OBSERVING 关联回填的读取面；eval_app 列级授权内，select 无新开口） */
    List<DrillJob> findActiveInStates(List<DrillJob.State> states);

    /** DR-06 关联回填 CAS（§7.5）：仅当 related_incident_id 仍为空才落（重复回填
     *  幂等；state+revision 对账之外的第二重保险）；runId 可空（incident 的
     *  currentRcaRunId 存在才回填） */
    boolean linkRelated(UUID id, long expectedRevision, UUID incidentId, UUID runId,
                        Instant updatedAt);
}
