package com.objwww.pr.notify.infrastructure.persistence;

import com.objwww.pr.notify.domain.model.ClaimedNotification;
import com.objwww.pr.notify.domain.port.NotifyOutboxStore;
import com.objwww.pr.notify.domain.port.StaleClaimException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * notify_outbox / report_publication PG 仓储（notify_app 身份；V9 列级授权面内写）。
 *
 * <p>claim：SKIP LOCKED 领取 + 短事务租约立即提交（PENDING/RETRY_WAIT 到期行 + 租约
 * 过期的 CLAIMED 折叠回收）；mark/sync 各一笔短事务，epoch 栅栏未中 0 行 =
 * {@link StaleClaimException}。channel 投递面之外零触碰（报告正文/调查记录不可见）。
 */
public class PostgresNotifyOutboxStore implements NotifyOutboxStore {

    private static final String CLAIM_SQL = """
            update notify_outbox set
                state = 'CLAIMED',
                lease_owner = :owner,
                lease_until = :leaseUntil,
                lease_epoch = lease_epoch + 1,
                updated_at = now()
            where id in (
                select id from notify_outbox
                where (state in ('PENDING','RETRY_WAIT') and available_at <= now())
                   or (state = 'CLAIMED' and lease_until < now())
                order by available_at, created_at
                limit :batch
                for update skip locked
            )
            returning id, publication_id, report_id, channel, template_version,
                      operation_id, payload_json::text as payload_json,
                      attempt_count, max_attempts, lease_epoch, created_at
            """;

    private static final String MARK_SENT_SQL = """
            update notify_outbox set state = 'SENT', sent_at = :sentAt, last_error = null,
                   updated_at = now()
            where id = :id and state = 'CLAIMED' and lease_epoch = :epoch
            """;

    private static final String MARK_RETRY_SQL = """
            update notify_outbox set state = 'RETRY_WAIT',
                   attempt_count = attempt_count + :bump,
                   available_at = :availableAt,
                   last_error = cast(:lastError as jsonb),
                   updated_at = now()
            where id = :id and state = 'CLAIMED' and lease_epoch = :epoch
            """;

    private static final String MARK_DEAD_SQL = """
            update notify_outbox set state = 'DEAD',
                   last_error = cast(:lastError as jsonb),
                   updated_at = now()
            where id = :id and state = 'CLAIMED' and lease_epoch = :epoch
            """;

    private static final String MARK_SUPPRESSED_SQL = """
            update notify_outbox set state = 'SUPPRESSED',
                   last_error = cast(:lastError as jsonb),
                   updated_at = now()
            where id = :id and state = 'CLAIMED' and lease_epoch = :epoch
            """;

    /** publication 聚合同步：只在冻结状态机出边内迁移（READY/RETRY_WAIT 行） */
    private static final String SYNC_PUBLICATION_SQL = """
            update report_publication p set
                state = case
                    when exists (select 1 from notify_outbox o
                                 where o.publication_id = p.id and o.state = 'SENT')
                        then 'SENT'
                    when not exists (select 1 from notify_outbox o
                                     where o.publication_id = p.id
                                       and o.state not in ('SENT','DEAD','SUPPRESSED'))
                        then 'DEAD'
                    else p.state
                end,
                updated_at = now()
            where p.id = :id and p.state in ('READY','RETRY_WAIT')
            """;

    private final JdbcClient jdbc;

    public PostgresNotifyOutboxStore(JdbcClient jdbc) {
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
                .query((rs, i) -> new ClaimedNotification(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("publication_id")),
                        UUID.fromString(rs.getString("report_id")),
                        rs.getString("channel"),
                        rs.getString("template_version"),
                        UUID.fromString(rs.getString("operation_id")),
                        rs.getString("payload_json"),
                        rs.getInt("attempt_count"),
                        rs.getInt("max_attempts"),
                        rs.getLong("lease_epoch"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Override
    public void markSent(UUID id, long leaseEpoch, Instant sentAt) {
        requireUpdated(id, MARK_SENT_SQL, Map.of(
                "sentAt", Timestamp.from(sentAt),
                "id", id,
                "epoch", leaseEpoch));
    }

    @Override
    public void markRetryWait(UUID id, long leaseEpoch, Instant availableAt,
                              boolean consumeAttempt, String lastErrorJson) {
        requireUpdated(id, MARK_RETRY_SQL, Map.of(
                "bump", consumeAttempt ? 1 : 0,
                "availableAt", Timestamp.from(availableAt),
                "lastError", lastErrorJson,
                "id", id,
                "epoch", leaseEpoch));
    }

    @Override
    public void markDead(UUID id, long leaseEpoch, String lastErrorJson) {
        requireUpdated(id, MARK_DEAD_SQL, Map.of(
                "lastError", lastErrorJson,
                "id", id,
                "epoch", leaseEpoch));
    }

    @Override
    public void markSuppressed(UUID id, long leaseEpoch, String noteJson) {
        requireUpdated(id, MARK_SUPPRESSED_SQL, Map.of(
                "lastError", noteJson,
                "id", id,
                "epoch", leaseEpoch));
    }

    @Override
    public void syncPublication(UUID publicationId) {
        jdbc.sql(SYNC_PUBLICATION_SQL)
                .param("id", publicationId)
                .update();
    }

    private void requireUpdated(UUID id, String sql, Map<String, Object> params) {
        int updated = jdbc.sql(sql).params(params).update();
        if (updated == 0) {
            throw new StaleClaimException(id);
        }
    }
}
