package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.domain.model.InboxState;
import com.objwww.pr.control.domain.model.WebhookInbox;
import com.objwww.pr.control.domain.repository.WebhookInboxRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * WebhookInboxRepository 的 Postgres 实现（M1 技术方案 v1.2 §4.2）。
 * 领取 = 单条 UPDATE ... FOR UPDATE SKIP LOCKED（CT-12 并发唯一 / CT-17 公平排序）；
 * 回写 = 租约匹配条件 UPDATE（lease_owner + lease_epoch，I14/CT-15：0 行即晚到不生效）。
 * 一切时间比较与时间戳走 DB now()（I17），应用侧只传 Duration 时长。
 * payload_raw/payload_json 大字段不进常规查询与 RETURNING，payloadRaw() 按需取。
 *
 * <p>刻意不加 Spring 注解：接线见 infrastructure/config/PersistenceConfig（docker profile）。
 */
public class PostgresWebhookInboxRepository implements WebhookInboxRepository {

    // 常规查询列：刻意排除 payload_raw/payload_json 两个大字段（10~50KB/行，§7-5）
    private static final String SELECT_COLUMNS = """
            delivery_id, github_event, github_action,
            github_installation_id, github_repository_id, payload_digest,
            state, lease_owner, lease_until, lease_epoch,
            attempt_count, max_attempts, next_retry_at, last_error,
            received_at, updated_at, processed_at
            """;

    // 落库即 RECEIVED；received_at/updated_at 取 DB now()（I17）；
    // 主键冲突 DO NOTHING → 0 行 = 重投/重放，原行不动（I9/I13）
    private static final String INSERT_SQL = """
            INSERT INTO webhook_inbox (
                delivery_id, github_event, github_action,
                github_installation_id, github_repository_id,
                payload_raw, payload_json, payload_digest,
                state, received_at, updated_at
            ) VALUES (
                :deliveryId, :githubEvent, :githubAction,
                :installationId, :repositoryId,
                :payloadRaw, CAST(:payloadJson AS jsonb), :payloadDigest,
                'RECEIVED', now(), now()
            )
            ON CONFLICT (delivery_id) DO NOTHING
            """;

    // 领取（方案 §4.2 原文形状）：单语句原子完成 SKIP LOCKED 选行 + 租约写入。
    // RETURNING 用显式列清单替代 *：避免把 payload_raw 大字段随领取拖回内存。
    private static final String CLAIM_SQL = """
            UPDATE webhook_inbox
               SET state       = 'PROCESSING',
                   lease_owner = :worker,
                   lease_epoch = lease_epoch + 1,
                   lease_until = now() + make_interval(secs => :ttlSeconds),
                   updated_at  = now()
             WHERE delivery_id IN (
                   SELECT delivery_id FROM webhook_inbox
                    WHERE state = 'RECEIVED'
                       OR (state = 'RETRY_WAIT' AND next_retry_at <= now())
                       OR (state = 'PROCESSING' AND lease_until < now())
                    ORDER BY next_retry_at NULLS FIRST, received_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
            RETURNING
            """ + SELECT_COLUMNS;

    // 回写一律租约匹配（I14）：lease_epoch 失配 = 本 Processor 已失去租约
    // （崩溃回收后被接管），晚到结果不得生效，0 行返回（CT-15）。
    private static final String COMPLETE_PROCESSED_SQL = """
            UPDATE webhook_inbox
               SET state = 'PROCESSED', processed_at = now(), updated_at = now()
             WHERE delivery_id = :deliveryId
               AND lease_owner = :worker
               AND lease_epoch = :epoch
            """;

    // attempt_count+1 落点在失败回写（§4.2 失败路径）：RETRY_WAIT→PROCESSING
    // 重领不再重复计数；next_retry_at 用 DB now()+退避（I17）
    private static final String COMPLETE_RETRY_WAIT_SQL = """
            UPDATE webhook_inbox
               SET state         = 'RETRY_WAIT',
                   attempt_count = attempt_count + 1,
                   next_retry_at = now() + make_interval(secs => :backoffSeconds),
                   last_error    = CAST(:lastError AS jsonb),
                   updated_at    = now()
             WHERE delivery_id = :deliveryId
               AND lease_owner = :worker
               AND lease_epoch = :epoch
            """;

    private static final String COMPLETE_DEAD_LETTER_SQL = """
            UPDATE webhook_inbox
               SET state         = 'DEAD_LETTER',
                   attempt_count = attempt_count + 1,
                   last_error    = CAST(:lastError AS jsonb),
                   updated_at    = now()
             WHERE delivery_id = :deliveryId
               AND lease_owner = :worker
               AND lease_epoch = :epoch
            """;

    private static final String COMPLETE_IGNORED_SQL = """
            UPDATE webhook_inbox
               SET state = 'IGNORED', updated_at = now()
             WHERE delivery_id = :deliveryId
               AND lease_owner = :worker
               AND lease_epoch = :epoch
            """;

    private static final String PAYLOAD_RAW_SQL = """
            SELECT payload_raw FROM webhook_inbox WHERE delivery_id = :deliveryId
            """;

    private final JdbcClient jdbc;

    public PostgresWebhookInboxRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public boolean insertNew(String deliveryId, String githubEvent, String githubAction,
                             Long installationId, Long repositoryId,
                             byte[] payloadRaw, String payloadJson, String payloadDigest) {
        int inserted = jdbc.sql(INSERT_SQL)
                .param("deliveryId", Objects.requireNonNull(deliveryId))
                .param("githubEvent", Objects.requireNonNull(githubEvent))
                .param("githubAction", githubAction)
                .param("installationId", installationId)
                .param("repositoryId", repositoryId)
                .param("payloadRaw", Objects.requireNonNull(payloadRaw))
                .param("payloadJson", payloadJson)
                .param("payloadDigest", Objects.requireNonNull(payloadDigest))
                .update();
        return inserted == 1;
    }

    @Override
    public Optional<WebhookInbox> findByDeliveryId(String deliveryId) {
        return jdbc.sql("SELECT " + SELECT_COLUMNS + " FROM webhook_inbox WHERE delivery_id = :deliveryId")
                .param("deliveryId", Objects.requireNonNull(deliveryId))
                .query(this::map)
                .optional();
    }

    @Override
    public List<WebhookInbox> claim(int limit, String workerId, Duration leaseTtl) {
        if (limit <= 0) {
            throw new IllegalArgumentException("领取批量必须为正: " + limit);
        }
        return jdbc.sql(CLAIM_SQL)
                .param("worker", Objects.requireNonNull(workerId))
                .param("ttlSeconds", Objects.requireNonNull(leaseTtl).toSeconds())
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    @Override
    public int completeProcessed(String deliveryId, String workerId, long leaseEpoch) {
        return jdbc.sql(COMPLETE_PROCESSED_SQL)
                .param("deliveryId", Objects.requireNonNull(deliveryId))
                .param("worker", Objects.requireNonNull(workerId))
                .param("epoch", leaseEpoch)
                .update();
    }

    @Override
    public int completeRetryWait(String deliveryId, String workerId, long leaseEpoch,
                                 Duration backoff, String lastError) {
        return jdbc.sql(COMPLETE_RETRY_WAIT_SQL)
                .param("deliveryId", Objects.requireNonNull(deliveryId))
                .param("worker", Objects.requireNonNull(workerId))
                .param("epoch", leaseEpoch)
                .param("backoffSeconds", Objects.requireNonNull(backoff).toSeconds())
                .param("lastError", lastError)
                .update();
    }

    @Override
    public int completeDeadLetter(String deliveryId, String workerId, long leaseEpoch,
                                  String lastError) {
        return jdbc.sql(COMPLETE_DEAD_LETTER_SQL)
                .param("deliveryId", Objects.requireNonNull(deliveryId))
                .param("worker", Objects.requireNonNull(workerId))
                .param("epoch", leaseEpoch)
                .param("lastError", lastError)
                .update();
    }

    @Override
    public int completeIgnored(String deliveryId, String workerId, long leaseEpoch) {
        return jdbc.sql(COMPLETE_IGNORED_SQL)
                .param("deliveryId", Objects.requireNonNull(deliveryId))
                .param("worker", Objects.requireNonNull(workerId))
                .param("epoch", leaseEpoch)
                .update();
    }

    @Override
    public byte[] payloadRaw(String deliveryId) {
        return jdbc.sql(PAYLOAD_RAW_SQL)
                .param("deliveryId", Objects.requireNonNull(deliveryId))
                .query((rs, rowNum) -> rs.getBytes(1))
                .optional()
                .orElse(null);
    }

    private WebhookInbox map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp leaseUntil = rs.getTimestamp("lease_until");
        Timestamp nextRetryAt = rs.getTimestamp("next_retry_at");
        Timestamp processedAt = rs.getTimestamp("processed_at");
        return new WebhookInbox(
                rs.getString("delivery_id"),
                rs.getString("github_event"),
                rs.getString("github_action"),
                (Long) rs.getObject("github_installation_id"),
                (Long) rs.getObject("github_repository_id"),
                rs.getString("payload_digest"),
                InboxState.valueOf(rs.getString("state")),
                rs.getString("lease_owner"),
                leaseUntil == null ? null : leaseUntil.toInstant(),
                rs.getLong("lease_epoch"),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                nextRetryAt == null ? null : nextRetryAt.toInstant(),
                rs.getString("last_error"),
                rs.getTimestamp("received_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                processedAt == null ? null : processedAt.toInstant());
    }
}
