package com.objwww.pr.control.domain.repository;

import com.objwww.pr.control.domain.model.WebhookInbox;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Webhook Inbox 仓储 port（M1 技术方案 v1.2 §4.2）。
 * 一切时间比较与时间戳由实现侧取数据库 now()（I17），调用方只传退避/租约时长。
 */
public interface WebhookInboxRepository {

    /**
     * 验签后落库（§4.2 HTTP 线程）：state=RECEIVED，received_at/updated_at 取 DB now()。
     * payloadJson 必须是已验证合法的 JSON 文本；畸形 JSON 传 null——合法签名 + 畸形 JSON
     * 也要能落库审计（E2E-22），payload_raw 永远是 HMAC 复核与审计的唯一权威（CT-18）。
     *
     * @return false = delivery_id 主键冲突（重投/重放），原行不被覆盖（I9/I13）
     */
    boolean insertNew(String deliveryId, String githubEvent, String githubAction,
                      Long installationId, Long repositoryId,
                      byte[] payloadRaw, String payloadJson, String payloadDigest);

    Optional<WebhookInbox> findByDeliveryId(String deliveryId);

    /**
     * 租约领取（§4.2 原文单条 SQL）：领取 RECEIVED / 到点的 RETRY_WAIT /
     * 租约过期的 PROCESSING（崩溃回收）；ORDER BY next_retry_at NULLS FIRST, received_at
     * 公平排序（CT-17 无尾部饿死）；FOR UPDATE SKIP LOCKED（CT-12 并发下每行恰好一次）。
     * 原子写入：state=PROCESSING、lease_owner=workerId、lease_epoch+1、
     * lease_until=now()+leaseTtl、updated_at=now()。
     */
    List<WebhookInbox> claim(int limit, String workerId, Duration leaseTtl);

    /**
     * 处理完成回写 PROCESSED + processed_at=now()。
     * 返回 0 = lease 失配（本 Processor 已被崩溃回收接管），晚到结果不得生效（I14/CT-15）。
     */
    int completeProcessed(String deliveryId, String workerId, long leaseEpoch);

    /**
     * 失败未耗尽：RETRY_WAIT + attempt_count+1 + next_retry_at=now()+backoff + last_error。
     * 耗尽与否由调用方按（领取时 attemptCount+1 &gt;= maxAttempts）判定后，
     * 选本方法或 completeDeadLetter——attempt 递增落点在失败回写（§4.2），重领不重复计数。
     * 返回 0 = 租约失配，晚到不生效（I14）。
     */
    int completeRetryWait(String deliveryId, String workerId, long leaseEpoch,
                          Duration backoff, String lastError);

    /**
     * 重试耗尽或不可恢复（如畸形载荷，E2E-22）：DEAD_LETTER + attempt_count+1 + last_error。
     * 终态，重投不唤醒（I16）。返回 0 = 租约失配，晚到不生效（I14）。
     */
    int completeDeadLetter(String deliveryId, String workerId, long leaseEpoch, String lastError);

    /**
     * 忽略：IGNORED 终态留痕（陈旧快筛 ST-11 / 非处理事件 ST-16，INC-16 关闭）。
     * 返回 0 = 租约失配，晚到不生效（I14）。
     */
    int completeIgnored(String deliveryId, String workerId, long leaseEpoch);

    /** 处理时按需取原始 payload（大字段不进常规查询）；行不存在返回 null */
    byte[] payloadRaw(String deliveryId);
}
