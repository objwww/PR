package com.objwww.pr.publisher.domain.port;

import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.domain.service.T3AContext;
import com.objwww.pr.publisher.domain.service.T3ADecision;
import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.PublicationResourceType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    /** 过期 IN_FLIGHT → RECONCILING（I7：先对账，禁盲目重发）；返回是否命中（并发守卫） */
    boolean toReconciling(UUID operationId, Instant now, Instant reconcileAfter);

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
}
