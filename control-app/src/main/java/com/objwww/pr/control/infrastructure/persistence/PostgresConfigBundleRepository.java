package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.shared.Digest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * config_bundle / config_bundle_active 的 Postgres 实现（V24；M5-09）。
 *
 * <p>事务边界：{@link #activate}（pointer CAS UPDATE）单语句单事务——"无半激活态"
 * 的落地面；CAS 以 {@code WHERE bundle_digest IS NOT DISTINCT FROM :expected}
 * 命中才写（null 期望 = 未激活态），0 行 = 并发竞争败者返回 false。bundle 行面
 * insert-only（V24 只授 select,insert），同 digest 二次 INSERT 由唯一约束拒绝 →
 * 发布幂等锚。历史行无任何 UPDATE 路径（回滚 = pointer 指回，INV-AM5-5）。
 */
public class PostgresConfigBundleRepository implements ConfigBundleRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String INSERT_BUNDLE_SQL = """
            INSERT INTO config_bundle (
                id, bundle_digest, revision, content, created_by, created_at
            ) VALUES (
                :id, :bundleDigest, :revision, CAST(:content AS jsonb), :createdBy, :createdAt
            )
            """;

    private static final String FIND_BUNDLE_SQL = """
            SELECT id, bundle_digest, revision, content, created_by, created_at
              FROM config_bundle
             WHERE bundle_digest = :bundleDigest
            """;

    private static final String NEXT_REVISION_SQL =
            "SELECT coalesce(max(revision), 0) + 1 FROM config_bundle";

    private static final String ACTIVE_DIGEST_SQL =
            "SELECT bundle_digest FROM config_bundle_active WHERE id = 1"
                    + " AND bundle_digest IS NOT NULL";

    private static final String ACTIVE_POINTER_SQL = """
            SELECT b.bundle_digest, b.revision, a.activated_at
              FROM config_bundle_active a
              JOIN config_bundle b ON b.bundle_digest = a.bundle_digest
             WHERE a.id = 1
            """;

    /** 单语句原子 CAS：expected revision = 客户端预期的当前激活 revision（0 = 未激活态）；
     *  EXISTS 复验目标资格未撤销（与资格事务内重验互补的语句级守卫） */
    private static final String QUALIFIED_ACTIVATE_SQL = """
            UPDATE config_bundle_active a
               SET bundle_digest = :toDigest, activated_at = :at, activated_by = :by
              FROM config_bundle b
             WHERE a.id = 1
               AND ( (:expectedRevision = 0 AND a.bundle_digest IS NULL)
                  OR (a.bundle_digest = b.bundle_digest AND b.revision = :expectedRevision) )
               AND EXISTS (SELECT 1 FROM release_qualification q
                            WHERE q.candidate_digest = :toDigest
                              AND q.quality_verdict = 'PASS'
                              AND q.revoked_at IS NULL)
            """;

    /** EN-02：资格行锁——与撤销 UPDATE 同行锁串行化，锁内读到的 revoked_at 即权威
     *  （P07"事务内重验拒绝陈旧资格"：撤销先提交则本事务零移动） */
    private static final String LOCK_QUALIFICATION_SQL = """
            SELECT id FROM release_qualification
             WHERE candidate_digest = :toDigest
               AND quality_verdict = 'PASS'
               AND revoked_at IS NULL
             FOR UPDATE
            """;

    /**
     * EX-B1（V40）：激活/回滚事实行——与上方 CAS 同一 tx.execute（Thread 绑连接同事务），
     * INSERT 失败整事务回滚 = 配置生效与证据同生死；CAS 0 行则零 INSERT（败者零事件）。
     */
    private static final String INSERT_CHANGE_EVENT_SQL = """
            INSERT INTO change_event (
                id, deploy_id, source, action, service, environment,
                image_digest, config_digest, commit_sha, actor,
                started_at, effective_at, rollback_of, status
            ) VALUES (
                :id, :deployId, 'config_activation', :action, :service, :environment,
                NULL, :configDigest, NULL, :actor,
                NULL, :effectiveAt, :rollbackOf, 'SUCCEEDED'
            )
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public PostgresConfigBundleRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource 不得为 null"));
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public long nextRevision() {
        return jdbc.sql(NEXT_REVISION_SQL).query(Long.class).single();
    }

    @Override
    public boolean insert(ConfigBundle bundle) {
        try {
            jdbc.sql(INSERT_BUNDLE_SQL)
                    .param("id", bundle.id())
                    .param("bundleDigest", bundle.bundleDigest().hex())
                    .param("revision", bundle.revision())
                    .param("content", writeJson(bundle.content()))
                    .param("createdBy", bundle.createdBy())
                    .param("createdAt", Timestamp.from(bundle.createdAt()))
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public Optional<ConfigBundle> findByDigest(Digest digest) {
        return jdbc.sql(FIND_BUNDLE_SQL)
                .param("bundleDigest", digest.hex())
                .query((rs, i) -> {
                    // 构造期重新深冻结 + 密钥扫描（读路径同样过闸，防御纵深）
                    return new ConfigBundle(rs.getObject("id", java.util.UUID.class),
                            new Digest(rs.getString("bundle_digest")), rs.getLong("revision"),
                            readJson(rs.getString("content")), rs.getString("created_by"),
                            rs.getTimestamp("created_at").toInstant());
                })
                .optional();
    }

    @Override
    public Optional<Digest> activeDigest() {
        return jdbc.sql(ACTIVE_DIGEST_SQL)
                .query((rs, i) -> new Digest(rs.getString("bundle_digest")))
                .optional();
    }

    @Override
    public Optional<ActivePointer> findActivePointer() {
        return jdbc.sql(ACTIVE_POINTER_SQL)
                .query((rs, i) -> new ActivePointer(
                        new Digest(rs.getString("bundle_digest")), rs.getLong("revision"),
                        rs.getTimestamp("activated_at").toInstant()))
                .optional();
    }

    /**
     * EN-02 资格化激活：单事务内 ①资格行 FOR UPDATE 锁定（与撤销串行化，P07）
     * → ②expected revision CAS（P06，0 = 未激活态）→ ③change_event 同事务（EX-B1）。
     * 返回 false = 预期陈旧/竞争败者/资格在事务内被撤销（零移动零事实）。
     */
    @Override
    public boolean activateQualified(Digest toDigest, long expectedActiveRevision,
            String by, Instant at, ActivationFact fact) {
        if (fact == null) {
            return activateQualified(toDigest, expectedActiveRevision, by, at);
        }
        Integer updated = tx.execute(status -> {
            jdbc.sql(LOCK_QUALIFICATION_SQL)
                    .param("toDigest", toDigest.hex())
                    .query((rs, i) -> rs.getString("id"))
                    .list();
            int moved = jdbc.sql(QUALIFIED_ACTIVATE_SQL)
                    .param("toDigest", toDigest.hex())
                    .param("expectedRevision", expectedActiveRevision)
                    .param("at", Timestamp.from(at))
                    .param("by", by)
                    .update();
            if (moved != 1) {
                return 0; // 预期陈旧/竞争败者/资格撤销：事务内零 INSERT（同事务面自动成立）
            }
            jdbc.sql(INSERT_CHANGE_EVENT_SQL)
                    .param("id", java.util.UUID.randomUUID())
                    .param("deployId", java.util.UUID.randomUUID().toString())
                    .param("action", fact.action())
                    .param("service", fact.service())
                    .param("environment", fact.environment())
                    .param("configDigest", toDigest.hex())
                    .param("actor", by)
                    .param("effectiveAt", Timestamp.from(at))
                    .param("rollbackOf",
                            fact.rollbackOf() == null ? null : fact.rollbackOf().hex())
                    .update();
            return 1;
        });
        return updated != null && updated == 1;
    }

    /** 资格化激活（无事实面） */
    @Override
    public boolean activateQualified(Digest toDigest, long expectedActiveRevision,
            String by, Instant at) {
        Integer updated = tx.execute(status -> jdbc.sql(QUALIFIED_ACTIVATE_SQL)
                .param("toDigest", toDigest.hex())
                .param("expectedRevision", expectedActiveRevision)
                .param("at", Timestamp.from(at))
                .param("by", by)
                .update());
        return updated != null && updated == 1;
    }

    // ------------------------------------------------------------------ 内部

    private static String writeJson(Map<String, Object> content) {
        try {
            return JSON.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("bundle content 序列化失败", e);
        }
    }

    private static Map<String, Object> readJson(String content) {
        try {
            return JSON.readValue(content,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("bundle content 解析失败", e);
        }
    }
}
