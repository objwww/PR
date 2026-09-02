package com.objwww.pr.publisher.domain.port;

import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.domain.model.DriftCheckTarget;
import com.objwww.pr.publisher.domain.model.RepairRequestDraft;
import com.objwww.pr.publisher.domain.model.RepairOutcomeTarget;
import com.objwww.pr.publisher.domain.service.T3AContext;
import com.objwww.pr.publisher.domain.service.T3ADecision;
import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.PublicationResourceType;
import com.objwww.pr.shared.Digest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Publisher 的 outbox 存储端口（I10 的另一面：只 SELECT/UPDATE outbox_command，
 * 不存在任何 INSERT outbox 路径；事件经内部 appender INSERT execution_event）。
 *
 * <p>事务边界（§6.1 T3）：
 * <ul>
 *   <li>{@link #claim}：SKIP LOCKED 领取 + 短事务写租约，立即提交；</li>
 *   <li>{@link #prepare}：T3-A——决策上下文加载与决策应用在同一事务（E1）；</li>
 *   <li>T3-B 各 mark/confirm 方法各自一笔短事务（lease_epoch 栅栏防僵尸，B-2），
 *       终态方法同事务推进 last_resolved_sequence（评审修正 #5）；MANUAL 不推进。</li>
 * </ul>
 * 所有带 leaseEpoch 的写方法命中 0 行时抛 {@link StaleLeaseException}。
 */
public interface PublicationStore {

    // ---------- T-claim（OutboxClaimer） ----------

    /** SKIP LOCKED 领取到期 PENDING/RETRY_WAIT（RETRY_WAIT 同事务归位 PENDING），按 aggregate_key+sequence 保序 */
    List<ClaimedCommand> claim(String leaseOwner, Duration leaseDuration, int batchSize);

    // ---------- T3-A（FencedPublicationExecutor） ----------

    /**
     * T3-A 单事务：行锁读命令 + 依赖 + subject 游标 → 应用 domain 决策 → 提交。
     * 决策函数必须是纯逻辑（由 FencedPublicationExecutor/PublicationGate 提供）。
     */
    T3ADecision prepare(UUID operationId, long leaseEpoch, Function<T3AContext, T3ADecision> decider);

    // ---------- T3-B（每个方法一笔短事务） ----------

    /** →CONFIRMED + remote_id/url + publication_resource + 游标推进 + PUBLICATION_CONFIRMED 事件 */
    void confirm(UUID operationId, long leaseEpoch, String remoteId, String remoteUrl,
                 PublicationResourceType resourceType, String marker, ExecutionEvent event);

    void confirmRepairReplacement(UUID operationId, long leaseEpoch, UUID oldResourceId,
                                  String remoteId, String remoteUrl,
                                  PublicationResourceType resourceType, String marker,
                                  ExecutionEvent event);

    void confirmRepairNoop(UUID operationId, long leaseEpoch, UUID oldResourceId,
                           String remoteId, String remoteUrl, ExecutionEvent event);

    Optional<ClaimedCommand> findRepairOrigin(UUID oldResourceId);

    Optional<UUID> findRepairResourceByOperation(UUID operationId);

    void reconcileConfirmRepairReplacement(UUID operationId, UUID oldResourceId,
                                            String remoteId, String remoteUrl,
                                            PublicationResourceType resourceType, String marker,
                                            ExecutionEvent event);

    List<RepairOutcomeTarget> findRepairOutcomes(int limit);

    boolean projectRepairOutcome(UUID requestId, String targetState, String error,
                                 ExecutionEvent event);

    /** →RECONCILING（响应丢失，EX-03；禁盲目重发）+ PUBLICATION_OUTCOME_UNKNOWN 事件 */
    void markReconciling(UUID operationId, long leaseEpoch, Instant reconcileAfter, ExecutionEvent event);

    /** →RETRY_WAIT + 退避 + attempt_count+1（EX-01） */
    void markRetryWait(UUID operationId, long leaseEpoch, Instant nextAttemptAt, String errorCode);

    /** →SUPERSEDED + 级联 + 游标推进（422 STALE_HEAD 确定性否定，EX-02） */
    void markSuperseded(UUID operationId, long leaseEpoch, String errorCode);

    /** →FAILED_TERMINAL + 游标推进；event 非空时同事务落账（401/403 的 SAFETY_REJECTED 告警） */
    void markFailedTerminal(UUID operationId, long leaseEpoch, String errorCode, ExecutionEvent event);

    /** →MANUAL（熔断出口；不推进游标，阻塞同 PR 后续命令，评审修正 #5） */
    void markManual(UUID operationId, long leaseEpoch, String errorCode);

    // ---------- Reconciler 扫描（OutboxRecoveryScanner，单实例周期任务，状态守卫而非租约守卫） ----------

    /** 路径①：租约过期的 IN_FLIGHT */
    List<ClaimedCommand> findExpiredInFlight(Instant now, int limit);

    /** 过期 IN_FLIGHT → RECONCILING（I7：先对账，禁盲目重发；崩溃恢复与响应丢失同属
     *  "结果未知"EX-03，转换成功时同事务补 PUBLICATION_OUTCOME_UNKNOWN 留痕）；
     *  返回是否命中（并发守卫） */
    boolean toReconciling(UUID operationId, Instant now, Instant reconcileAfter, ExecutionEvent event);

    /** 路径②：到期的 RECONCILING */
    List<ClaimedCommand> findDueReconciling(Instant now, int limit);

    /** 探测找到：→CONFIRMED + remote_id + publication_resource + 游标推进 + 事件（不重复创建） */
    void reconcileConfirm(UUID operationId, String remoteId, String remoteUrl,
                          PublicationResourceType resourceType, String marker, ExecutionEvent event);

    /** 窗口内穷尽确认不存在：→RETRY_WAIT 退避重发（§4.3） */
    void reconcileRetryWait(UUID operationId, Instant nextAttemptAt);

    /** 查不到也不能确认：reconcile_not_found_count+1 并推迟下次探测；返回新计数（超预算 → MANUAL，EX-04） */
    int reconcileUnknown(UUID operationId, Instant nextReconcileAfter);

    /** 对账熔断/策略性人工：RECONCILING → MANUAL */
    void reconcileManual(UUID operationId, String errorCode);

    /** 路径③：supersede 兜底——PENDING/RETRY_WAIT 且 epoch 落后于 subject 当前 epoch（v2.1 修订三；IN_FLIGHT 不在此列，I7） */
    List<ClaimedCommand> findStaleEpoch(int limit);

    /** 同事务 →SUPERSEDED + 级联（OPTIONAL 不级联）+ 游标推进；事务内复核 epoch 仍落后才生效 */
    void supersedeStaleEpoch(UUID operationId);

    // ---------- Drift 巡检（DriftReconciler，M1-T08 方案 §4.6；只写观测列，CT-20 列级授权边界内） ----------

    /**
     * 公平巡检扫描：JOIN outbox_command，取 {@code state IN ('PRESENT','MISSING')}
     * 且命令 CONFIRMED 且 {@code next_check_at <= now()} 的资源，按 next_check_at 升序
     * LIMIT（最久未查先查，不饿死尾部，E2E-15）。MISSING 在列是为了低频复核（§4.6）。
     *
     * <p>新建资源的 next_check_at 初值 = 创建时刻 + 首查宽限
     * （{@code publisher.drift.first-check-grace-seconds}，默认 10s，TB-25），宽限窗内
     * 不被本扫描选中——刚确认创建成功的对象不立即重探。
     */
    List<DriftCheckTarget> findDueForDriftCheck(int limit);

    /** 探针命中：→PRESENT + last_checked_at=now() + next_check_at=now()+interval + error_count=0（MISSING 复核找回也走这里） */
    void markCheckedPresent(UUID resourceId, Duration interval);

    /** 内容漂移 episode：新 observed digest 才落事件；MISSING 复核找回先归 PRESENT（RM2-04），state 不留 MISSING。 */
    boolean markContentDrift(UUID resourceId, Digest observedDigest,
                             Duration interval, ExecutionEvent event);

    /** 内容恢复期望值：关闭 episode、刷新巡检时间并归 PRESENT（MISSING 复核找回同此，RM2-04）。 */
    void clearContentDrift(UUID resourceId, Duration interval);

    /**
     * 404 且 sanity 通过：→MISSING + drift_detected_at（首次才置）+ 低频复核排期。
     * event 仅在本调用把资源从非 MISSING 翻成 MISSING 时同事务落账（PUBLICATION_DRIFT_DETECTED
     * 恰好一次，ST-22 的状态守卫）；已 MISSING 的复核只重排期、忽略 event。
     *
     * @return true = 本轮新转入 MISSING（事件已落）；false = 已是 MISSING 或状态守卫未命中
     */
    boolean markMissing(UUID resourceId, Duration recheckInterval, ExecutionEvent event);

    /** 新 MISSING 首次翻转时，同事务落 drift 事件、repair_request 和 requested 事件。 */
    boolean markMissingWithRepair(UUID resourceId, Duration recheckInterval,
                                  ExecutionEvent driftEvent, RepairRequestDraft repair,
                                  ExecutionEvent repairEvent);

    /**
     * sanity 失败（E2E-18/F-3：权限异常绝不冒充"不存在"）：PRESENT→UNKNOWN +
     * 权限告警事件同事务落账。UNKNOWN 不在巡检扫描集内，等待人工/后续代际处理。
     */
    void markUnknown(UUID resourceId, ExecutionEvent event);

    /**
     * 探测失败（5xx/超时/429）：状态不动，check_error_count+1 + 退避排期。
     *
     * @return 新计数（>= 阈值时调用方落 ReconcilerDegraded，措辞修正 #3/EX-14）
     */
    int markCheckError(UUID resourceId, Duration backoff);
}
