package com.objwww.pr.notify.domain.port;

import com.objwww.pr.notify.domain.model.ClaimedNotification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * notify_outbox / report_publication 存储端口（notify_app 身份，V9 授权面：
 * 只碰投递面——领取/退避/终态；不得改报告与调查记录，BA-10②/E2E-M3-07）。
 *
 * <p>事务边界（旧 publisher 骨架同构）：{@link #claim} SKIP LOCKED 领取 + 短事务
 * 租约立即提交；各 mark/sync 方法各自一笔短事务，带 leaseEpoch 的写命中 0 行抛
 * {@link StaleClaimException}。
 */
public interface NotifyOutboxStore {

    /**
     * 领取到期可投递行（公平排序 available_at, created_at；单 worker 串行，§6.8 预算）：
     * PENDING/RETRY_WAIT 且 available_at 到期，或租约过期的 CLAIMED（崩溃回收折叠进
     * 领取查询，无需独立 scanner）。
     */
    List<ClaimedNotification> claim(String leaseOwner, Duration leaseDuration, int batchSize);

    /** →SENT + sent_at（ck_notify_outbox_lifecycle：SENT iff sent_at 非空） */
    void markSent(UUID id, long leaseEpoch, Instant sentAt) throws StaleClaimException;

    /**
     * →RETRY_WAIT + 持久化退避（available_at 推进，不占 worker 槽）。
     * consumeAttempt=false 用于 429：渠道限流不是投递失败，不耗 attempt 预算（落码自决）；
     * 5xx/连接失败 consumeAttempt=true。
     */
    void markRetryWait(UUID id, long leaseEpoch, Instant availableAt, boolean consumeAttempt,
                       String lastErrorJson) throws StaleClaimException;

    /** →DEAD（终态）：4xx 确定性失败 / 重试预算耗尽 / 结果未知（UNKNOWN 不自动重发） */
    void markDead(UUID id, long leaseEpoch, String lastErrorJson) throws StaleClaimException;

    /** →SUPPRESSED（终态）：VALIDATE_ONLY 档校验通过、按策略不投递（诚实落账不冒充 SENT） */
    void markSuppressed(UUID id, long leaseEpoch, String noteJson) throws StaleClaimException;

    /**
     * publication 聚合同步（READY/RETRY_WAIT 行）：
     * 任一渠道行 SENT → SENT（E2E-M3-05 终入 SENT）；全部行终态且无一 SENT → DEAD。
     * 只在冻结状态机允许的出边内迁移（READY→SENT/DEAD、RETRY_WAIT→DEAD）。
     */
    void syncPublication(UUID publicationId);
}
