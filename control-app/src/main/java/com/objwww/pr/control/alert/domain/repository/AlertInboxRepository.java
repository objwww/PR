package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.AlertInbox;
import com.objwww.pr.control.alert.domain.model.InboxDecision;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * alert_inbox 端口（V7 全列；六态 + 租约 + 退避，V3 webhook_inbox 同构语义）。
 *
 * <p>SQL 契约：claimNext =
 * {@code UPDATE ... SET state='PROCESSING', lease_owner=:owner, lease_until=:now+:lease,
 * lease_epoch=lease_epoch+1 WHERE id = (SELECT id FROM alert_inbox
 * WHERE state IN ('RECEIVED','RETRY_WAIT') AND (next_retry_at IS NULL OR next_retry_at <= :now)
 * ORDER BY next_retry_at NULLS FIRST, received_at LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING *}
 *
 * <p>所有带 epoch 的写方法都是 epoch 栅栏：lease_epoch 不匹配即 0 行（旧 worker 晚到提交被拒）。
 */
public interface AlertInboxRepository {

    /** 整组原子落库（验签/尺寸门通过后；初态 RECEIVED，空组可初态 IGNORED） */
    void insert(AlertInbox row);

    /** 领取一行（租约翻转 PROCESSING + epoch+1）；无可领行返回 empty */
    Optional<AlertInbox> claimNext(String owner, Instant now, Duration lease);

    /** PROCESSING→PROCESSED + decision 落审计列；epoch 栅栏 */
    boolean complete(UUID id, long leaseEpoch, InboxDecision decision, Instant now);

    /**
     * PROCESSING→RETRY_WAIT（attempt+1、next_retry_at=now+backoff、last_error、decision 审计列）；
     * 软背压 DEFERRED 也走此路径（§6.4：backlog 回落后由 claimNext 重领补投，行不进终态）。
     */
    boolean scheduleRetry(UUID id, long leaseEpoch, InboxDecision decision, String lastError,
                          Instant nextRetryAt, Instant now);

    /** PROCESSING→DEAD_LETTER（attempt 耗尽由调用方判定） */
    boolean markDeadLetter(UUID id, long leaseEpoch, String lastError, Instant now);

    /** →IGNORED（如处理期发现空组） */
    boolean markIgnored(UUID id, long leaseEpoch, Instant now);

    /** 崩溃回收：租约过期的 PROCESSING→RECEIVED（epoch 不动，重领时再 +1） */
    long reclaimExpired(Instant now);

    Optional<AlertInbox> findById(UUID id);
}
