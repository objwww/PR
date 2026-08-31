package com.objwww.pr.control.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Webhook 接收记录聚合（M1 技术方案 v1.2 §4.1，与 V3 webhook_inbox 表一一对应）。
 * 防什么：入口去重（delivery_id 主键，I9）+ 租约 CAS 防双处理器（I14）。
 *
 * <p>两条刻意约束：
 * <ul>
 *   <li>payload_raw / payload_json 不进本模型（大字段，PR 事件 10~50KB，§7-5）：
 *       raw 由仓储 payloadRaw() 在处理时按需取（HMAC 复核与审计的唯一权威，CT-18），
 *       jsonb 仅供 SQL 侧查询/路由，永不参与验签；</li>
 *   <li>本模型是领取/回写 SQL 的不可变快照，不提供任何改态方法——状态推进全部走
 *       WebhookInboxRepository 的租约匹配 SQL，防止绕过 epoch 栅栏在 Java 侧改态。</li>
 * </ul>
 */
public class WebhookInbox {

    private final String deliveryId;
    private final String githubEvent;
    /** 可空（V3 github_action 允许 NULL） */
    private final String githubAction;
    /** 可空 */
    private final Long installationId;
    /** 可空 */
    private final Long repositoryId;
    /** sha256(payload_raw) 的 64 位小写 hex，重投比对用（I13） */
    private final String payloadDigest;

    private final InboxState state;
    /** 可空：未持租约时 NULL */
    private final String leaseOwner;
    /** 可空 */
    private final Instant leaseUntil;
    private final long leaseEpoch;

    private final int attemptCount;
    private final int maxAttempts;
    /** 可空：仅 RETRY_WAIT 有意义 */
    private final Instant nextRetryAt;
    /** 可空：jsonb 原文（JSON 文本形态），记录最近一次失败原因 */
    private final String lastError;

    private final Instant receivedAt;
    private final Instant updatedAt;
    /** 可空：仅 PROCESSED 回写时落（§4.2） */
    private final Instant processedAt;

    public WebhookInbox(String deliveryId, String githubEvent, String githubAction,
                        Long installationId, Long repositoryId, String payloadDigest,
                        InboxState state, String leaseOwner, Instant leaseUntil, long leaseEpoch,
                        int attemptCount, int maxAttempts, Instant nextRetryAt, String lastError,
                        Instant receivedAt, Instant updatedAt, Instant processedAt) {
        this.deliveryId = Objects.requireNonNull(deliveryId);
        this.githubEvent = Objects.requireNonNull(githubEvent);
        this.githubAction = githubAction;
        this.installationId = installationId;
        this.repositoryId = repositoryId;
        this.payloadDigest = Objects.requireNonNull(payloadDigest);
        this.state = Objects.requireNonNull(state);
        this.leaseOwner = leaseOwner;
        this.leaseUntil = leaseUntil;
        this.leaseEpoch = leaseEpoch;
        // 对齐 ck_inbox_attempts
        if (maxAttempts <= 0 || attemptCount < 0 || attemptCount > maxAttempts) {
            throw new IllegalArgumentException("attempt 计数越界: " + attemptCount + "/" + maxAttempts);
        }
        this.attemptCount = attemptCount;
        this.maxAttempts = maxAttempts;
        this.nextRetryAt = nextRetryAt;
        this.lastError = lastError;
        this.receivedAt = Objects.requireNonNull(receivedAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.processedAt = processedAt;
    }

    public String getDeliveryId() { return deliveryId; }
    public String getGithubEvent() { return githubEvent; }
    public String getGithubAction() { return githubAction; }
    public Long getInstallationId() { return installationId; }
    public Long getRepositoryId() { return repositoryId; }
    public String getPayloadDigest() { return payloadDigest; }
    public InboxState getState() { return state; }
    public String getLeaseOwner() { return leaseOwner; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public long getLeaseEpoch() { return leaseEpoch; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public String getLastError() { return lastError; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getProcessedAt() { return processedAt; }
}
