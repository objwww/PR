package com.objwww.pr.notify.infrastructure.persistence;

import com.objwww.pr.notify.domain.model.ClaimedNotification;
import com.objwww.pr.notify.domain.port.NotifyOutboxStore;
import com.objwww.pr.notify.domain.port.StaleClaimException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * duty_delivery → {@link NotifyOutboxStore} 适配器（M7-14；马尾辫复用裁定：
 * B-41 加固过的 FencedNotifyExecutor/NotifyOutboxClaimer 单源复用，零新执行器）。
 *
 * <p>字段映射：publicationId/reportId/operationId ← notification_id（duty 无报告/
 * 出版物绑定——V9 不变量不放松，表分离）；channel ← duty_channel.name（领取时 JOIN）；
 * templateVersion ← 常量 duty-v1。{@link #syncPublication} = no-op：duty 的"聚合"
 * 职责由 195 侧 DutyFallbackWatcher 降级链接管（SUPPRESSED 台账 = 全链 DEAD +
 * DUTY_CHAIN_EXHAUSTED 事件），无 publication 状态机。
 *
 * <p>SQL 范式与 PostgresNotifyOutboxStore 逐句同构（SKIP LOCKED + 租约 + epoch 栅栏
 * + 各 mark 单语句 CAS）；notify_app 对 duty_delivery 只有 SELECT + 列级 UPDATE
 * （V43 授权面——补投行铸造归 control_app watcher）。
 */
public class PostgresDutyNotifyAdapter implements NotifyOutboxStore {

    private static final String CLAIM_SQL = """
            update duty_delivery d set
                state = 'CLAIMED',
                lease_owner = :owner,
                lease_until = :leaseUntil,
                lease_epoch = d.lease_epoch + 1,
                updated_at = now()
            from duty_channel c, duty_notification n
            where d.id in (
                select id from duty_delivery
                where (state in ('PENDING','RETRY_WAIT') and available_at <= now())
                   or (state = 'CLAIMED' and lease_until < now())
                order by available_at, created_at
                limit :batch
                for update skip locked
            ) and c.id = d.channel_id and n.id = d.notification_id
            returning d.id, d.notification_id, c.name as channel,
                      n.payload_json::text as payload_json,
                      d.attempt_count, d.max_attempts, d.lease_epoch, d.created_at
            """;

    private static final String MARK_SENT_SQL = """
            update duty_delivery set state = 'SENT', sent_at = :sentAt, last_error = null,
                   updated_at = now()
            where id = :id and state = 'CLAIMED' and lease_epoch = :epoch
            """;

    private static final String MARK_RETRY_SQL = """
            update duty_delivery set state = 'RETRY_WAIT',
                   attempt_count = attempt_count + :bump,
                   available_at = :availableAt,
                   last_error = cast(:lastError as jsonb),
                   updated_at = now()
            where id = :id and state = 'CLAIMED' and lease_epoch = :epoch
            """;

    private static final String MARK_DEAD_SQL = """
            update duty_delivery set state = 'DEAD',
                   last_error = cast(:lastError as jsonb),
                   updated_at = now()
            where id = :id and state = 'CLAIMED' and lease_epoch = :epoch
            """;

    private static final String MARK_SUPPRESSED_SQL = """
            update duty_delivery set state = 'SUPPRESSED',
                   last_error = cast(:lastError as jsonb),
                   updated_at = now()
            where id = :id and state = 'CLAIMED' and lease_epoch = :epoch
            """;

    private final JdbcClient jdbc;

    public PostgresDutyNotifyAdapter(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public List<ClaimedNotification> claim(String leaseOwner, Duration leaseDuration,
                                           int batchSize) {
        Instant leaseUntil = Instant.now().plus(leaseDuration);
        return jdbc.sql(CLAIM_SQL)
                .param("owner", leaseOwner)
                .param("leaseUntil", Timestamp.from(leaseUntil))
                .param("batch", batchSize)
                .query((rs, i) -> {
                    UUID notificationId = UUID.fromString(rs.getString("notification_id"));
                    return new ClaimedNotification(
                            UUID.fromString(rs.getString("id")),
                            notificationId,
                            notificationId,
                            rs.getString("channel"),
                            "duty-v1",
                            notificationId,
                            rs.getString("payload_json"),
                            rs.getInt("attempt_count"),
                            rs.getInt("max_attempts"),
                            rs.getLong("lease_epoch"),
                            rs.getTimestamp("created_at").toInstant());
                })
                .list();
    }

    @Override
    public void markSent(UUID id, long leaseEpoch, Instant sentAt) throws StaleClaimException {
        if (jdbc.sql(MARK_SENT_SQL).param("id", id).param("epoch", leaseEpoch)
                .param("sentAt", Timestamp.from(sentAt)).update() == 0) {
            throw new StaleClaimException(id);
        }
    }

    @Override
    public void markRetryWait(UUID id, long leaseEpoch, Instant availableAt,
                              boolean consumeAttempt, String lastErrorJson)
            throws StaleClaimException {
        if (jdbc.sql(MARK_RETRY_SQL).param("id", id).param("epoch", leaseEpoch)
                .param("bump", consumeAttempt ? 1 : 0)
                .param("availableAt", Timestamp.from(availableAt))
                .param("lastError", lastErrorJson).update() == 0) {
            throw new StaleClaimException(id);
        }
    }

    @Override
    public void markDead(UUID id, long leaseEpoch, String lastErrorJson)
            throws StaleClaimException {
        if (jdbc.sql(MARK_DEAD_SQL).param("id", id).param("epoch", leaseEpoch)
                .param("lastError", lastErrorJson).update() == 0) {
            throw new StaleClaimException(id);
        }
    }

    @Override
    public void markSuppressed(UUID id, long leaseEpoch, String noteJson)
            throws StaleClaimException {
        if (jdbc.sql(MARK_SUPPRESSED_SQL).param("id", id).param("epoch", leaseEpoch)
                .param("lastError", noteJson).update() == 0) {
            throw new StaleClaimException(id);
        }
    }

    @Override
    public void syncPublication(UUID publicationId) {
        // no-op：duty 无 publication 聚合（降级链 = 195 侧 watcher 职责）
    }
}
