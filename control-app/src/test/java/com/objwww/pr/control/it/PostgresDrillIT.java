package com.objwww.pr.control.it;

import com.objwww.pr.control.drill.domain.model.DrillEvent;
import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.infrastructure.persistence.PostgresDrillEventRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresDrillJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DR-02 验收（真 PG）：V86 授权矩阵（control_app 只增 + 停止两列 / eval_app 列级
 * 推进 / publisher·notify 零权限）、drill_event insert-only（两身份零 update/delete）、
 * 幂等锚 uq（同键二次插入违约）、活动占位部分唯一索引（同靶场活动双作业违约，
 * RECOVERY_FAILED 同样占位 DU15）、CLOSED 形状 CHECK（缺 outcome 违约）。
 *
 * <p>本机无 Docker → Testcontainers 整类跳过（NOT_RUN）；真 PG 环境跑 Flyway 全迁移。
 */
class PostgresDrillIT extends PostgresITBase {

    private PostgresDrillJobRepository controlJobs;
    private PostgresDrillEventRepository controlEvents;
    private PostgresDrillJobRepository evalJobs;
    private PostgresDrillEventRepository evalEvents;

    @BeforeEach
    void setUpRepositories() {
        controlJobs = new PostgresDrillJobRepository(controlJdbc);
        controlEvents = new PostgresDrillEventRepository(controlJdbc);
        evalJobs = new PostgresDrillJobRepository(evalJdbc);
        evalEvents = new PostgresDrillEventRepository(evalJdbc);
    }

    private static DrillJob job(String env, String key) {
        return DrillJob.queued(UUID.randomUUID(), "S3", "F1 幂等失效",
                "0".repeat(64), env, "operator", "{}", "1".repeat(64), key,
                Instant.now());
    }

    // ------------------------------------------------------------------ 授权矩阵

    @Test
    @DisplayName("授权矩阵：control_app 增读 + 停止两列可写，状态机推进列零开口")
    void controlAppGrantMatrix() {
        DrillJob job = job("arena-195", "it-k1");
        controlJobs.insert(job);
        assertThat(controlJobs.findById(job.id())).isPresent();
        // 停止两列 = control_app 唯一 update 面
        assertThat(controlJobs.requestStop(job.id(), "it-stop-1", Instant.now())).isTrue();
        // 状态机推进零开口（state 不在 control_app 列级授权内）
        assertThatThrownBy(() -> controlJdbc.sql("""
                UPDATE drill_job SET state = 'PRECHECK' WHERE id = :id
                """).param("id", job.id()).update())
                .hasMessageContaining("permission denied");
        // 正文列零开口
        assertThatThrownBy(() -> controlJdbc.sql("""
                UPDATE drill_job SET params = '{}'::jsonb WHERE id = :id
                """).param("id", job.id()).update())
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> controlJdbc.sql("""
                DELETE FROM drill_job WHERE id = :id
                """).param("id", job.id()).update())
                .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("授权矩阵：eval_app 列级推进可写（state/revision），正文与幂等键零开口")
    void evalAppGrantMatrix() {
        DrillJob job = job("arena-195", "it-k2");
        controlJobs.insert(job);
        assertThat(evalJobs.advance(job.id(), job.revision(), DrillJob.State.QUEUED,
                DrillJob.State.PRECHECK, Instant.now())).isTrue();
        assertThatThrownBy(() -> evalJdbc.sql("""
                UPDATE drill_job SET operator = 'hacked' WHERE id = :id
                """).param("id", job.id()).update())
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> evalJdbc.sql("""
                UPDATE drill_job SET idempotency_key = 'hacked' WHERE id = :id
                """).param("id", job.id()).update())
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> evalJdbc.sql("""
                INSERT INTO drill_job (id, scenario_id, scenario_name, template_digest,
                    target_env, operator, params, payload_hash, idempotency_key)
                VALUES (:id, 'S3', 'n', :dg, 'arena-195', 'op', '{}'::jsonb, :h, 'x')
                """).param("id", UUID.randomUUID()).param("dg", "2".repeat(64))
                .param("h", "3".repeat(64)).update())
                .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("授权矩阵：publisher/notify 对两表零权限（显式 revoke 归零）")
    void publisherNotifyZeroGrants() {
        assertThatThrownBy(() -> publisherJdbc.sql(
                "SELECT count(*) FROM drill_job").query(Long.class).single())
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> notifyJdbc.sql(
                "SELECT count(*) FROM drill_event").query(Long.class).single())
                .hasMessageContaining("permission denied");
    }

    // ------------------------------------------------------------------ insert-only

    @Test
    @DisplayName("drill_event insert-only：control_app 与 eval_app 零 update/delete 开口")
    void eventsInsertOnly() {
        DrillJob job = job("arena-195", "it-k3");
        controlJobs.insert(job);
        controlEvents.insert(DrillEvent.phaseTransition(job.id(), DrillJob.State.QUEUED,
                DrillJob.State.PRECHECK, "it-worker", "{}", Instant.now()));
        evalEvents.insert(DrillEvent.of(job.id(), DrillEvent.EventType.PRECHECK_RESULT,
                "it-worker", "{}", Instant.now()));
        assertThat(controlEvents.listByDrill(job.id())).hasSize(2);
        assertThatThrownBy(() -> controlJdbc.sql("""
                UPDATE drill_event SET actor = 'hacked' WHERE drill_id = :id
                """).param("id", job.id()).update())
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> evalJdbc.sql("""
                DELETE FROM drill_event WHERE drill_id = :id
                """).param("id", job.id()).update())
                .hasMessageContaining("permission denied");
    }

    // ------------------------------------------------------------------ 约束面

    @Test
    @DisplayName("幂等锚 uq：同 idempotency_key 二次插入违约（DU02 库侧兜底）")
    void idempotencyKeyUnique() {
        controlJobs.insert(job("arena-195", "it-dup"));
        assertThatThrownBy(() -> adminJdbc.sql("""
                INSERT INTO drill_job (id, scenario_id, scenario_name, template_digest,
                    target_env, operator, params, payload_hash, idempotency_key)
                VALUES (:id, 'S4', 'n', :dg, 'arena-x', 'op', '{}'::jsonb, :h, 'it-dup')
                """).param("id", UUID.randomUUID()).param("dg", "2".repeat(64))
                .param("h", "3".repeat(64)).update())
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("活动占位部分唯一索引：同靶场活动双作业违约；终态让位；"
            + "RECOVERY_FAILED 同样占位（DU15）")
    void activeEnvPlaceholderUnique() {
        DrillJob first = job("arena-195", "it-e1");
        controlJobs.insert(first);
        // 同靶场第二活动作业撞部分唯一索引（不同 faultType 同样互斥，§7.3）
        DrillJob second = DrillJob.queued(UUID.randomUUID(), "S4", "F2 状态回跳",
                "0".repeat(64), "arena-195", "operator", "{}", "4".repeat(64),
                "it-e2", Instant.now());
        assertThatThrownBy(() -> controlJobs.insert(second))
                .isInstanceOf(DuplicateKeyException.class);
        // 终态化（零副作用 FAILED）→ 占位让位，第三作业可入
        assertThat(evalJobs.finalize(first.id(), first.revision(), DrillJob.State.QUEUED,
                DrillJob.State.FAILED, "precheck_failed", null, null, Instant.now()))
                .isTrue();
        controlJobs.insert(second);
        // RECOVERY_FAILED 保留占位：第四作业仍撞（恢复未核验前阻止下一场）
        DrillJob staged = controlJobs.findById(second.id()).orElseThrow();
        assertThat(evalJobs.advance(staged.id(), staged.revision(),
                DrillJob.State.QUEUED, DrillJob.State.PRECHECK, Instant.now())).isTrue();
        staged = evalJobs.findById(second.id()).orElseThrow();
        assertThat(evalJobs.advance(staged.id(), staged.revision(),
                DrillJob.State.PRECHECK, DrillJob.State.INJECTING, Instant.now())).isTrue();
        staged = evalJobs.findById(second.id()).orElseThrow();
        assertThat(evalJobs.advance(staged.id(), staged.revision(),
                DrillJob.State.INJECTING, DrillJob.State.RECOVERING, Instant.now()))
                .isTrue();
        staged = evalJobs.findById(second.id()).orElseThrow();
        assertThat(evalJobs.finalize(staged.id(), staged.revision(),
                DrillJob.State.RECOVERING, DrillJob.State.RECOVERY_FAILED,
                "recovery_timeout", null, null, Instant.now())).isTrue();
        assertThatThrownBy(() -> controlJobs.insert(job("arena-195", "it-e3")))
                .isInstanceOf(DuplicateKeyException.class);
        // 异靶场不受占位影响
        controlJobs.insert(job("arena-other", "it-e4"));
    }

    @Test
    @DisplayName("CLOSED 形状 CHECK：缺 outcome 或 closedAt 违约（CLOSED≠成功，库侧钉死）")
    void closedShapeCheck() {
        DrillJob job = job("arena-195", "it-c1");
        controlJobs.insert(job);
        assertThat(evalJobs.advance(job.id(), job.revision(), DrillJob.State.QUEUED,
                DrillJob.State.PRECHECK, Instant.now())).isTrue();
        DrillJob staged = evalJobs.findById(job.id()).orElseThrow();
        assertThat(evalJobs.advance(staged.id(), staged.revision(),
                DrillJob.State.PRECHECK, DrillJob.State.INJECTING, Instant.now())).isTrue();
        staged = evalJobs.findById(job.id()).orElseThrow();
        assertThat(evalJobs.advance(staged.id(), staged.revision(),
                DrillJob.State.INJECTING, DrillJob.State.RECOVERING, Instant.now()))
                .isTrue();
        staged = evalJobs.findById(job.id()).orElseThrow();
        assertThat(evalJobs.advance(staged.id(), staged.revision(),
                DrillJob.State.RECOVERING, DrillJob.State.VERIFYING, Instant.now()))
                .isTrue();
        staged = evalJobs.findById(job.id()).orElseThrow();
        // 缺 outcome → CHECK 违约
        DrillJob verifying = staged;
        assertThatThrownBy(() -> evalJobs.finalize(verifying.id(), verifying.revision(),
                DrillJob.State.VERIFYING, DrillJob.State.CLOSED, null, null,
                Instant.now(), Instant.now()))
                .hasMessageContaining("ck_drill_job_closed_shape");
        // 带 outcome → CLOSED 成立
        assertThat(evalJobs.finalize(verifying.id(), verifying.revision(),
                DrillJob.State.VERIFYING, DrillJob.State.CLOSED, null, "INCONCLUSIVE",
                Instant.now(), Instant.now())).isTrue();
    }

    @Test
    @DisplayName("领取 CAS：SKIP LOCKED 单语句领取即迁移 PRECHECK，第二领取空手（多 worker 恰一人领到）")
    void claimSkipLocked() {
        DrillJob job = job("arena-195", "it-w1");
        controlJobs.insert(job);
        Optional<DrillJob> claimed = evalJobs.claimNext("worker-a", Instant.now());
        assertThat(claimed).isPresent();
        assertThat(claimed.get().workerId()).isEqualTo("worker-a");
        assertThat(claimed.get().revision()).isEqualTo(1);
        // BA-114：领取语句提交时行已落 PRECHECK（领取即迁移，与 EVAL 同律）
        assertThat(claimed.get().state()).isEqualTo(DrillJob.State.PRECHECK);
        assertThat(evalJobs.findById(job.id()).orElseThrow().state())
                .isEqualTo(DrillJob.State.PRECHECK);
        // 已离开 QUEUED 可见集 → 下一领取空手（修复前此处被重复领取，revision 1→2）
        assertThat(evalJobs.claimNext("worker-b", Instant.now())).isEmpty();
        // revision 对账：旧 revision/旧相位推进失败（租约过期 ≠ 可重做）
        assertThat(evalJobs.advance(job.id(), 0, DrillJob.State.QUEUED,
                DrillJob.State.PRECHECK, Instant.now())).isFalse();
    }
}
