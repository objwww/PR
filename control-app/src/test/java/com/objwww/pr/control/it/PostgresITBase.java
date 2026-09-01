package com.objwww.pr.control.it;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.objwww.pr.shared.Digest;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * control-app 集成测试基座（与 publisher 侧 PostgresITBase 同形态，M1-T02 引入）：
 * Testcontainers 真 PG（postgres:16-alpine）+ 真实角色/授权/Flyway 迁移（V1~V4 在本模块
 * classpath:db/migration）。
 *
 * <p>形态：
 * <ul>
 *   <li>静态容器全 IT 类共享一个 PG 实例；每个测试方法前 TRUNCATE 全部 15 张表清场
 *       （V1 的 12 张 + V3/V4 新表，RESTART IDENTITY CASCADE 兜底）；</li>
 *   <li>admin（容器超级用户）先执行与 deploy/db/01-roles.sh 等价的 DO 块创建
 *       control_app / publisher_app 两角色（V2/V3 的 grant 依赖两角色存在），
 *       再以 admin 身份跑 Flyway；</li>
 *   <li>之后测试只经三条 DataSource 触库：admin（清场/拨时间等测试动作）、
 *       control_app、publisher_app——应用路径的角色权限由真实授权兜底；</li>
 *   <li>INC-09：3.10 内核宿主必须 seccomp=unconfined，否则 initdb EPERM；</li>
 *   <li>本机无 docker 时整类自动跳过（disabledWithoutDocker），本机 mvn test 不受影响。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresITBase {

    protected static final String CONTROL_ROLE = "control_app";
    protected static final String PUBLISHER_ROLE = "publisher_app";
    protected static final String CONTROL_PASSWORD = "it-control-pass";
    protected static final String PUBLISHER_PASSWORD = "it-publisher-pass";

    /** V1 的 12 张 + V3/V4 新表（TRUNCATE 清场顺序无关，CASCADE 兜底） */
    private static final List<String> ALL_TABLES = List.of(
            "pr_subject", "pr_revision", "review_run", "run_step", "work_item", "step_attempt",
            "execution_event", "outbox_command", "outbox_dependency", "publication_resource",
            "review_finding", "artifact", "webhook_inbox", "step_checkpoint", "repair_request");

    @SuppressWarnings("resource") // 容器由 ryuk 回收；静态生命周期贯穿整个 IT JVM
    protected static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    // INC-09：老内核宿主的 seccomp 不支持新镜像 syscall 面
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                            .withSecurityOpts(List.of("seccomp=unconfined")));

    private static HikariDataSource adminDs;
    private static HikariDataSource controlDs;
    private static HikariDataSource publisherDs;

    protected static JdbcClient adminJdbc;
    protected static JdbcClient controlJdbc;
    protected static JdbcClient publisherJdbc;
    protected static TransactionTemplate controlTx;
    protected static TransactionTemplate publisherTx;

    private static synchronized void ensureStarted() {
        if (adminDs != null) {
            return;
        }
        PG.start();

        adminDs = pool(PG.getUsername(), PG.getPassword(), 2);
        adminJdbc = JdbcClient.create(adminDs);

        // 与 deploy/db/01-roles.sh 等价的幂等角色创建（授权在 V2/V3，以 owner 身份跑 Flyway）
        adminJdbc.sql("""
                do $$
                begin
                    if not exists (select from pg_roles where rolname = 'control_app') then
                        create role control_app login password '%s';
                    else
                        alter role control_app with login password '%s';
                    end if;
                    if not exists (select from pg_roles where rolname = 'publisher_app') then
                        create role publisher_app login password '%s';
                    else
                        alter role publisher_app with login password '%s';
                    end if;
                end
                $$;
                """.formatted(CONTROL_PASSWORD, CONTROL_PASSWORD, PUBLISHER_PASSWORD, PUBLISHER_PASSWORD))
                .update();

        Flyway.configure()
                .dataSource(adminDs)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        controlDs = pool(CONTROL_ROLE, CONTROL_PASSWORD, 24);
        publisherDs = pool(PUBLISHER_ROLE, PUBLISHER_PASSWORD, 6);
        controlJdbc = JdbcClient.create(controlDs);
        publisherJdbc = JdbcClient.create(publisherDs);
        controlTx = new TransactionTemplate(new DataSourceTransactionManager(controlDs));
        publisherTx = new TransactionTemplate(new DataSourceTransactionManager(publisherDs));
    }

    private static HikariDataSource pool(String user, String password, int size) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(PG.getJdbcUrl());
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(size);
        return new HikariDataSource(config);
    }

    static {
        ensureStarted();
    }

    @BeforeEach
    void truncateAll() {
        adminJdbc.sql("TRUNCATE " + String.join(", ", ALL_TABLES) + " RESTART IDENTITY CASCADE")
                .update();
    }

    // 连接池与静态容器同生命周期（贯穿整个 failsafe fork），不随单个测试类关闭。

    // ------------------------------------------------------------------ 测试动作助手

    protected static DataSource controlDataSource() {
        return controlDs;
    }

    protected static DataSource publisherDataSource() {
        return publisherDs;
    }

    /** admin 计数（断言 DB 全貌用，绕开角色视角） */
    protected long count(String table) {
        return adminJdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    // ------------------------------------------------------------------ M2 修复链路种子助手（CT-22/25/27/28/29 共用）

    /** 修复链路测试骨架：subject + current revision + 已完结 NORMAL run（漂移检测发生时的典型现场）。 */
    protected record RepairSeed(UUID subjectId, UUID revisionId, UUID runId) {}

    private static final AtomicLong SEED_SEQ = new AtomicLong(10_000);

    /**
     * 造一 PR 骨架（admin）：subject（next_outbox_sequence=2，给原命令留 sequence=1）
     * + revision（设为 current）+ COMPLETED 的 NORMAL run。列清单 V3/V4 通用（CT-22 在 V3 形态库复用）。
     */
    protected static RepairSeed seedRepairScope(String tag) {
        return seedRepairScope(adminJdbc, tag);
    }

    /** 同 {@link #seedRepairScope(String)}，写入指定库（CT-22 的独立升级库用）。 */
    protected static RepairSeed seedRepairScope(JdbcClient jdbc, String tag) {
        long n = SEED_SEQ.getAndIncrement();
        RepairSeed seed = new RepairSeed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String d = Digest.sha256Of("seed-" + tag + "-" + n).value();
        jdbc.sql("""
                INSERT INTO pr_subject(id,github_installation_id,github_repository_id,repository_full_name,
                    pr_number,state,draft,merged,current_policy_version,publication_epoch,
                    next_outbox_sequence,last_resolved_sequence,version,created_at,updated_at)
                VALUES (:s,1,:repo,'octo/demo',:pr,'OPEN',false,false,'p',1,2,0,0,now(),now())
                """).param("s", seed.subjectId()).param("repo", n).param("pr", (int) (n % Integer.MAX_VALUE))
                .update();
        jdbc.sql("""
                INSERT INTO pr_revision(id,pr_subject_id,head_sha,base_ref,base_sha,diff_digest,
                    revision_fingerprint,observed_at,created_at)
                VALUES (:r,:s,:head,'main','base',:d,:d,now(),now())
                """).param("r", seed.revisionId()).param("s", seed.subjectId())
                .param("head", "head-" + tag + "-" + n).param("d", d).update();
        jdbc.sql("UPDATE pr_subject SET current_revision_id=:r WHERE id=:s")
                .param("r", seed.revisionId()).param("s", seed.subjectId()).update();
        jdbc.sql("""
                INSERT INTO review_run(id,pr_revision_id,run_key,trigger_key,run_mode,policy_version,
                    prompt_version,toolset_version,state,publisher_disabled,version,created_at,updated_at,
                    completed_at)
                VALUES (:id,:r,:key,'ct','NORMAL','p','prompt','tools','COMPLETED',false,0,now(),now(),now())
                """).param("id", seed.runId()).param("r", seed.revisionId())
                .param("key", Digest.sha256Of("run-" + tag + "-" + n).value()).update();
        return seed;
    }

    /**
     * 在骨架上插一条 CONFIRMED 原命令（aggregate_sequence=1，与 subject.next_outbox_sequence=2 配套，
     * 后续 repair 命令经 SequenceAllocator 取 2，天然单调）；payload_hash 取 payload 正文的 sha256，
     * 与 RepairPlanner 按 digest 读 CAS 的约定一致。返回 operation_id。
     */
    protected static UUID seedConfirmedCommand(RepairSeed seed, String commandType, String payloadJson) {
        return seedConfirmedCommand(adminJdbc, seed, commandType, payloadJson);
    }

    /** 同 {@link #seedConfirmedCommand(RepairSeed, String, String)}，写入指定库。 */
    protected static UUID seedConfirmedCommand(JdbcClient jdbc, RepairSeed seed,
                                               String commandType, String payloadJson) {
        UUID operationId = UUID.randomUUID();
        String hash = Digest.sha256Of(payloadJson).value();
        String remoteIdentityType = switch (commandType) {
            case "CREATE_CHECK" -> "EXTERNAL_ID";
            case "UPDATE_CHECK" -> "CHECK_RUN_ID";
            default -> "REVIEW_MARKER";
        };
        jdbc.sql("""
                INSERT INTO outbox_command(operation_id,pr_subject_id,review_run_id,pr_revision_id,
                    aggregate_key,aggregate_sequence,publication_epoch,fence_mode,command_type,state,
                    policy_version,payload_artifact_digest,payload_hash,remote_identity_type,
                    attempt_count,max_attempts,created_at,updated_at,confirmed_at)
                VALUES (:op,:s,:run,:rev,:agg,1,1,'CURRENT_EPOCH',:type,'CONFIRMED',
                    'p',:hash,:hash,:rit,1,3,now(),now(),now())
                """).param("op", operationId).param("s", seed.subjectId()).param("run", seed.runId())
                .param("rev", seed.revisionId()).param("agg", "agg-" + seed.subjectId())
                .param("type", commandType).param("hash", hash)
                .param("rit", remoteIdentityType).update();
        return operationId;
    }

    /** 插一条由指定命令创建的资源行（uq(resource_type, remote_id)，remoteId 须唯一），返回资源 id。 */
    protected static UUID seedResource(RepairSeed seed, UUID commandOperationId,
                                       String resourceType, String state, String remoteId) {
        return seedResource(adminJdbc, seed, commandOperationId, resourceType, state, remoteId);
    }

    /** 同 {@link #seedResource(RepairSeed, UUID, String, String, String)}，写入指定库。 */
    protected static UUID seedResource(JdbcClient jdbc, RepairSeed seed, UUID commandOperationId,
                                       String resourceType, String state, String remoteId) {
        UUID resourceId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO publication_resource(id,resource_type,created_by_operation_id,pr_subject_id,
                    remote_id,remote_url,state,created_at,updated_at)
                VALUES (:id,:type,:op,:s,:rid,:url,:state,now(),now())
                """).param("id", resourceId).param("type", resourceType).param("op", commandOperationId)
                .param("s", seed.subjectId()).param("rid", remoteId)
                .param("url", "https://api.github.test/" + remoteId).param("state", state).update();
        return resourceId;
    }

    /**
     * 铸一条 repair_request（admin）。V4 trg_repair_insert_pending 强制 INSERT 只能以 PENDING
     * 出生且审批三列恒空，因此目标态经随后 UPDATE 抵达（APPROVED 自动补审计三列，
     * RETRY_WAIT 把 next_attempt_at 拨到过去立即可领）。返回 request id。
     */
    protected static UUID seedRepairRequest(UUID resourceId, String resourceType, String tier,
                                            String targetState, int attemptCount, int maxAttempts,
                                            long createdAgeSeconds) {
        UUID id = UUID.randomUUID();
        adminJdbc.sql("""
                INSERT INTO repair_request(id,publication_resource_id,resource_type,policy_tier,state,
                    attempt_count,max_attempts,created_at,updated_at)
                VALUES (:id,:rid,:rtype,:tier,'PENDING',0,:maxA,
                    now()-make_interval(secs => :age),now()-make_interval(secs => :age))
                """).param("id", id).param("rid", resourceId).param("rtype", resourceType)
                .param("tier", tier).param("maxA", maxAttempts).param("age", createdAgeSeconds).update();
        if (!"PENDING".equals(targetState)) {
            adminJdbc.sql("""
                    UPDATE repair_request SET state=:state, attempt_count=:ac,
                        approved_by = CASE WHEN :state='APPROVED' THEN 'seed-approver' END,
                        approved_at = CASE WHEN :state='APPROVED' THEN now() END,
                        approval_reason = CASE WHEN :state='APPROVED' THEN 'seed approval' END,
                        next_attempt_at = CASE WHEN :state='RETRY_WAIT' THEN now()-interval '1 second' END,
                        updated_at=now()
                     WHERE id=:id
                    """).param("state", targetState).param("ac", attemptCount).param("id", id).update();
        }
        return id;
    }

    /** 读 repair_request 当前 state（admin 视角）。 */
    protected static String repairStateOf(UUID requestId) {
        return adminJdbc.sql("SELECT state FROM repair_request WHERE id=:id")
                .param("id", requestId).query(String.class).single();
    }
}
