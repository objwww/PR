package com.objwww.pr.control.it;

import com.objwww.pr.control.domain.sandbox.SandboxJob;
import com.objwww.pr.control.infrastructure.persistence.JdbcSandboxJobRepository;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.sandbox.FailureClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * CT-51: JdbcSandboxJobRepository 真 PG 集成测试（M4 §4.1 sandbox_job 表 + §4.2 生命周期）。
 *
 * <p>验证：
 * <ul>
 *   <li>租约状态机转换（PENDING → LEASED → SUCCEEDED/FAILED）</li>
 *   <li>claim 并发安全：SKIP LOCKED 防领同一行 + uq_sandbox_job_inflight 部分唯一索引
 *       全局并发闸（第二个 claim 抛 23505）</li>
 *   <li>lease_epoch 乐观锁（update/renewLease 的 CAS 语义）</li>
 *   <li>V6 字段完整性（全列写入读回）</li>
 * </ul>
 *
 * <p>被测仓储由 PersistenceConfig 以 NamedParameterJdbcTemplate 装配，测试用基座的
 * control_app DataSource 手工 new（应用路径的真实列级授权兜底）；FK 父行
 * （review_run/run_step/work_item/step_attempt/tool_call）经 admin 种子插入，
 * tool_call 的工具入参列是 jsonb，种子 SQL 显式 CAST。
 */
class JdbcSandboxJobRepositoryIT extends PostgresITBase {

    /** JobSpec 不可变列的 JSON 样本（§4.1：image/entrypoint/cmd/env/security_profile/workspace_digests/timeouts） */
    private static final String JOB_SPEC_JSON =
            "{\"image\":\"registry/job@sha256:0123456789abcdef\",\"entrypoint\":[\"/wrapper/run.sh\"],"
                    + "\"cmd\":[],\"env\":[],\"security_profile\":{},\"workspace_digests\":[],\"timeouts\":{}}";

    private JdbcSandboxJobRepository repo;
    private RepairSeed seed;
    private UUID stepId;
    private UUID attemptId;

    @BeforeEach
    void setUp() {
        repo = new JdbcSandboxJobRepository(new NamedParameterJdbcTemplate(controlDataSource()));
        seed = seedRepairScope("sandbox-job");
        // tool_call/sandbox_job 的 FK 前提链：run_step + work_item + step_attempt
        stepId = UUID.randomUUID();
        UUID workItemId = UUID.randomUUID();
        attemptId = UUID.randomUUID();
        adminJdbc.sql("""
                INSERT INTO run_step(id,review_run_id,step_key,operation_id,step_type,state,
                    ordinal,timeout_seconds,created_at,updated_at)
                VALUES (:id,:run,'step-sandbox',:op,'REVIEW','READY',1,600,now(),now())
                """).param("id", stepId).param("run", seed.runId()).param("op", UUID.randomUUID())
                .update();
        adminJdbc.sql("""
                INSERT INTO work_item(id,review_run_id,step_id,work_type,state,available_at,
                    max_attempts,created_at,updated_at)
                VALUES (:id,:run,:step,'REVIEW','READY',now(),3,now(),now())
                """).param("id", workItemId).param("run", seed.runId()).param("step", stepId)
                .update();
        adminJdbc.sql("""
                INSERT INTO step_attempt(id,step_id,work_item_id,attempt_no,lease_epoch,
                    worker_id,status,started_at)
                VALUES (:id,:step,:wi,1,1,'it-worker','STARTED',now())
                """).param("id", attemptId).param("step", stepId).param("wi", workItemId)
                .update();
    }

    /** 造一条 RUNNING tool_call（admin；tool_args 为 jsonb，需显式 CAST）。 */
    private UUID seedToolCall(int callSeq) {
        UUID toolCallId = UUID.randomUUID();
        adminJdbc.sql("""
                INSERT INTO tool_call(id,review_run_id,run_step_id,attempt_id,call_seq,
                    tool_name,tool_args,state,lease_epoch,started_at)
                VALUES (:id,:run,:step,:attempt,:seq,'REVIEW_TOOL_CALL',CAST(:args AS jsonb),
                    'RUNNING',0,now())
                """).param("id", toolCallId).param("run", seed.runId()).param("step", stepId)
                .param("attempt", attemptId).param("seq", callSeq).param("args", "{}").update();
        return toolCallId;
    }

    private SandboxJob newPendingJob(UUID toolCallId) {
        return SandboxJob.createPending(UUID.randomUUID(), toolCallId, seed.runId(), stepId,
                attemptId, JOB_SPEC_JSON);
    }

    private String stateOf(UUID jobId) {
        return adminJdbc.sql("SELECT state FROM sandbox_job WHERE id=:id")
                .param("id", jobId).query(String.class).single();
    }

    /** CT-51: PENDING → LEASED——claim 置租约三列、epoch 0→1、attempt_count 0→1；无候选时返回 empty。 */
    @Test
    void claimPendingTransitionsToLeasedAndIncrementsEpoch() {
        SandboxJob job = newPendingJob(seedToolCall(1));
        repo.save(job);

        SandboxJob claimed = repo.claimNext("lease-001", 300, "it-worker").orElseThrow();

        assertThat(claimed.id()).isEqualTo(job.id());
        assertThat(claimed.state()).isEqualTo(SandboxJob.JobState.LEASED);
        assertThat(claimed.leaseOwner()).isEqualTo("lease-001");
        assertThat(claimed.workerId()).isEqualTo("it-worker");
        assertThat(claimed.leaseEpoch()).isEqualTo(1L);
        assertThat(claimed.attemptCount()).isEqualTo(1);
        assertThat(claimed.leaseUntil()).isAfter(Instant.now());
        assertThat(claimed.startedAt()).isNotNull();
        // 唯一 PENDING 已被领走：再领无候选行，返回 empty
        assertThat(repo.claimNext("lease-002", 300, "it-worker")).isEmpty();
    }

    /** CT-51: LEASED → SUCCEEDED——列级 UPDATE + epoch CAS；旧 epoch 的写入被拒（乐观锁）。 */
    @Test
    void leasedJobCompletesToSucceededWithEpochFencing() {
        SandboxJob job = newPendingJob(seedToolCall(1));
        repo.save(job);
        SandboxJob claimed = repo.claimNext("lease-001", 300, "it-worker").orElseThrow();

        Digest resultDigest = Digest.sha256Of("ct51-result");
        Digest logDigest = Digest.sha256Of("ct51-log");
        claimed.complete("container-abc123", 0, resultDigest, logDigest);
        assertThat(repo.update(claimed, 1L)).isTrue();

        SandboxJob reloaded = repo.findById(job.id()).orElseThrow();
        assertThat(reloaded.state()).isEqualTo(SandboxJob.JobState.SUCCEEDED);
        assertThat(reloaded.containerId()).isEqualTo("container-abc123");
        assertThat(reloaded.exitCode()).isEqualTo(0);
        assertThat(reloaded.resultDigest()).isEqualTo(resultDigest);
        assertThat(reloaded.logDigest()).isEqualTo(logDigest);
        assertThat(reloaded.finishedAt()).isNotNull();
        // lease_epoch 乐观锁：旧 epoch(0) 的写入 CAS 不匹配，零行更新，状态不被改写
        assertThat(repo.update(claimed, 0L)).isFalse();
        assertThat(stateOf(job.id())).isEqualTo("SUCCEEDED");
    }

    /** CT-51: LEASED → FAILED——failure_class/retryable 落库（USER_CODE 恒不可重试）。 */
    @Test
    void leasedJobFailsToFailedWithFailureClass() {
        SandboxJob job = newPendingJob(seedToolCall(1));
        repo.save(job);
        SandboxJob claimed = repo.claimNext("lease-001", 300, "it-worker").orElseThrow();

        Digest logDigest = Digest.sha256Of("ct51-fail-log");
        claimed.fail("container-abc123", 1, logDigest, "TOOL_EXIT_NONZERO", "tool exited 1",
                FailureClass.USER_CODE);
        assertThat(repo.update(claimed, 1L)).isTrue();

        SandboxJob reloaded = repo.findById(job.id()).orElseThrow();
        assertThat(reloaded.state()).isEqualTo(SandboxJob.JobState.FAILED);
        assertThat(reloaded.exitCode()).isEqualTo(1);
        assertThat(reloaded.logDigest()).isEqualTo(logDigest);
        assertThat(reloaded.errorCode()).isEqualTo("TOOL_EXIT_NONZERO");
        assertThat(reloaded.sanitizedMessage()).isEqualTo("tool exited 1");
        assertThat(reloaded.failureClass()).isEqualTo(FailureClass.USER_CODE);
        assertThat(reloaded.retryable()).isFalse();
        assertThat(reloaded.finishedAt()).isNotNull();
    }

    /**
     * CT-51: 全局并发闸——同时 LEASED ≤ 1。两个 PENDING job 先后 claim，第二次 claim 的
     * UPDATE 撞 uq_sandbox_job_inflight 部分唯一索引（V6），抛 23505（Spring 翻译为
     * DataIntegrityViolationException）；冲突语句整体回滚，第二个 job 保持 PENDING。
     */
    @Test
    void secondClaimHitsInflightGateWithSqlState23505() {
        repo.save(newPendingJob(seedToolCall(1)));
        SandboxJob second = newPendingJob(seedToolCall(2));
        repo.save(second);

        assertThat(repo.claimNext("lease-001", 300, "it-worker")).isPresent();

        Throwable t = catchThrowable(() -> repo.claimNext("lease-002", 300, "it-worker"));
        assertThat(t).isInstanceOf(DataIntegrityViolationException.class);
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(PSQLException.class);
        assertThat(((PSQLException) root).getSQLState()).isEqualTo("23505");
        assertThat(((PSQLException) root).getServerErrorMessage().getConstraint())
                .isEqualTo("uq_sandbox_job_inflight");
        // 冲突语句回滚：第二个 job 仍是 PENDING，LEASED 行全局仍只有一条
        assertThat(stateOf(second.id())).isEqualTo("PENDING");
        assertThat(adminJdbc.sql("SELECT count(*) FROM sandbox_job WHERE state='LEASED'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    /** CT-51: 续租 fencing——epoch 不匹配拒写；匹配则 lease_until 延长且 heartbeat_at 落时间戳。 */
    @Test
    void renewLeaseRespectsEpochFencing() {
        SandboxJob job = newPendingJob(seedToolCall(1));
        repo.save(job);
        repo.claimNext("lease-001", 300, "it-worker");

        assertThat(repo.renewLease(job.id(), 0L, 300)).isFalse();
        assertThat(repo.renewLease(job.id(), 1L, 300)).isTrue();
        assertThat(adminJdbc.sql("SELECT heartbeat_at IS NOT NULL FROM sandbox_job WHERE id=:id")
                .param("id", job.id()).query(Boolean.class).single()).isTrue();
    }

    /**
     * CT-51: V6 字段完整性——全列写入读回逐一相等。时间戳固定到微秒精度字面量
     * （timestamptz 微秒精度，round-trip 精确）；job_spec_immutable 是 jsonb
     * （键序/空白不保留），用 jsonb 语义相等断言。
     */
    @Test
    void saveRoundTripsAllV6Columns() {
        UUID toolCallId = seedToolCall(1);
        Instant created = Instant.parse("2026-09-02T10:00:00.123456Z");
        Instant leaseUntil = Instant.parse("2026-09-02T10:05:00.654321Z");
        Instant heartbeat = Instant.parse("2026-09-02T10:01:00.111111Z");
        Instant started = Instant.parse("2026-09-02T10:00:05.222222Z");
        Instant finished = Instant.parse("2026-09-02T10:02:00.333333Z");
        Digest resultDigest = Digest.sha256Of("ct51-full-result");
        Digest logDigest = Digest.sha256Of("ct51-full-log");
        UUID jobId = UUID.randomUUID();
        SandboxJob job = new SandboxJob(jobId, toolCallId, seed.runId(), stepId, attemptId,
                JOB_SPEC_JSON, created, "it-worker", SandboxJob.JobState.FAILED, "lease-xyz",
                leaseUntil, 5L, heartbeat, 2, 3, "container-xyz789", 1, resultDigest, logDigest,
                "TOOL_EXIT_NONZERO", "tool exited 1", FailureClass.INFRASTRUCTURE, true,
                started, finished);

        repo.save(job);

        SandboxJob found = repo.findById(jobId).orElseThrow();
        assertThat(found.toolCallId()).isEqualTo(toolCallId);
        assertThat(found.reviewRunId()).isEqualTo(seed.runId());
        assertThat(found.runStepId()).isEqualTo(stepId);
        assertThat(found.attemptId()).isEqualTo(attemptId);
        assertThat(found.createdAt()).isEqualTo(created);
        assertThat(found.workerId()).isEqualTo("it-worker");
        assertThat(found.state()).isEqualTo(SandboxJob.JobState.FAILED);
        assertThat(found.leaseOwner()).isEqualTo("lease-xyz");
        assertThat(found.leaseUntil()).isEqualTo(leaseUntil);
        assertThat(found.leaseEpoch()).isEqualTo(5L);
        assertThat(found.heartbeatAt()).isEqualTo(heartbeat);
        assertThat(found.attemptCount()).isEqualTo(2);
        assertThat(found.maxAttempts()).isEqualTo(3);
        assertThat(found.containerId()).isEqualTo("container-xyz789");
        assertThat(found.exitCode()).isEqualTo(1);
        assertThat(found.resultDigest()).isEqualTo(resultDigest);
        assertThat(found.logDigest()).isEqualTo(logDigest);
        assertThat(found.errorCode()).isEqualTo("TOOL_EXIT_NONZERO");
        assertThat(found.sanitizedMessage()).isEqualTo("tool exited 1");
        assertThat(found.failureClass()).isEqualTo(FailureClass.INFRASTRUCTURE);
        assertThat(found.retryable()).isTrue();
        assertThat(found.startedAt()).isEqualTo(started);
        assertThat(found.finishedAt()).isEqualTo(finished);
        assertThat(adminJdbc.sql(
                "SELECT job_spec_immutable = CAST(:spec AS jsonb) FROM sandbox_job WHERE id=:id")
                .param("spec", JOB_SPEC_JSON).param("id", jobId).query(Boolean.class).single())
                .isTrue();
        // 单向 FK 一对一查询通道
        assertThat(repo.findByToolCallId(toolCallId).orElseThrow().id()).isEqualTo(jobId);
    }
}
