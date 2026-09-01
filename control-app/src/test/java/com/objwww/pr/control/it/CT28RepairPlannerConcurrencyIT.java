package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.application.OutboxWriter;
import com.objwww.pr.control.application.PublicationRequest;
import com.objwww.pr.control.application.RepairDispatchService;
import com.objwww.pr.control.domain.model.RepairCandidate;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.domain.repository.ArtifactRepository;
import com.objwww.pr.control.domain.repository.OutboxCommandRepository;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.control.domain.service.RepairCommandFactory;
import com.objwww.pr.control.domain.service.SequenceAllocator;
import com.objwww.pr.control.infrastructure.cas.LocalCasArtifactStore;
import com.objwww.pr.control.infrastructure.persistence.PostgresArtifactRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresExecutionEventRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresOutboxCommandRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRepairRequestRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReviewRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresSequenceAllocator;
import com.objwww.pr.control.infrastructure.persistence.PostgresWorkItemRepository;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.OutboxCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CT-28（docs/M2-技术方案.md §11 L2 表，回指 I27 / §4.3 铸造点单短事务）。
 *
 * <p>场景：双 RepairPlanner 并发领同一批 repair_request（SKIP LOCKED 验证）；Planner
 * 在插 outbox 前/后各崩溃一次（故障注入 OutboxWriter）；崩溃在提交后一次。
 *
 * <p>断言：并发领取只铸一条 repair 命令且零阻塞（后到的 SKIP LOCKED 立即返回而非等锁）；
 * 崩溃结果为零命令或一条命令、无半截态（REPAIR Run/命令/事件/artifact 同事务同生同灭）；
 * REPAIR Run 零 Step、不进 work_item 领取面；repair 命令 aggregate_sequence 单调（>原命令）
 * 且 depends_on 指原命令、fence_mode=CURRENT_EPOCH、payload 携带 repair 血缘且不含旧远端身份。
 *
 * <p>取证：outbox_command（经 review_run.run_mode='REPAIR' 关联）/ outbox_dependency /
 * repair_request.repair_operation_id / review_run.run_mode,state,run_key /
 * execution_event REPAIR_DISPATCHED / artifact 行数 / work_item 领取面。
 */
class CT28RepairPlannerConcurrencyIT extends PostgresITBase {

    private static final String PAYLOAD = "{\"name\":\"ai-review\",\"conclusion\":\"success\"}";

    @TempDir
    Path casDir;

    private final ObjectMapper om = new ObjectMapper();
    private PostgresRepairRequestRepository requests;
    private ArtifactStore cas;
    private RepairCommandFactory factory;

    @BeforeEach
    void setUp() {
        requests = new PostgresRepairRequestRepository(controlJdbc);
        cas = new LocalCasArtifactStore(casDir);
        factory = new RepairCommandFactory(om);
    }

    private record Fixture(UUID requestId, UUID resourceId, UUID originalOperationId, RepairSeed seed) {}

    private Fixture newFixture(String tag) {
        RepairSeed seed = seedRepairScope(tag);
        UUID op = seedConfirmedCommand(seed, "CREATE_CHECK", PAYLOAD);
        cas.putIfAbsent(Digest.sha256Of(PAYLOAD), PAYLOAD.getBytes(StandardCharsets.UTF_8));
        UUID resource = seedResource(seed, op, "CHECK_RUN", "MISSING", tag + "-res");
        UUID requestId = seedRepairRequest(resource, "CHECK_RUN", "AUTO", "PENDING", 0, 5, 0);
        return new Fixture(requestId, resource, op, seed);
    }

    private RepairCommandFactory.Prepared prepare(Fixture f) {
        RepairCandidate candidate = requests.findReady(10).stream()
                .filter(c -> c.requestId().equals(f.requestId())).findFirst().orElseThrow();
        return factory.prepare(candidate,
                cas.get(candidate.payloadHash()).orElseThrow(),
                cas.get(candidate.basePayloadHash()).orElseThrow());
    }

    private RepairDispatchService dispatcher(OutboxWriter outbox) {
        return new RepairDispatchService(requests, new PostgresReviewRunRepository(controlJdbc),
                outbox, new ExecutionLedger(new PostgresExecutionEventRepository(controlJdbc, om)));
    }

    private OutboxWriter outboxWriter() {
        return new OutboxWriter(new PostgresOutboxCommandRepository(controlJdbc),
                new PostgresSequenceAllocator(controlJdbc), cas,
                new PostgresArtifactRepository(controlJdbc));
    }

    private long repairCommandCount() {
        return adminJdbc.sql("""
                SELECT count(*) FROM outbox_command o JOIN review_run r ON r.id = o.review_run_id
                 WHERE r.run_mode = 'REPAIR'
                """).query(Long.class).single();
    }

    // ------------------------------------------------------------------ 并发领取（SKIP LOCKED）

    @Test
    void twoPlannersRaceOnSameBatchExactlyOneCommand() throws Exception {
        Fixture f = newFixture("ct28-race");
        RepairCommandFactory.Prepared preparedA = prepare(f);
        RepairCommandFactory.Prepared preparedB = prepare(f);

        CountDownLatch aHoldsRowLock = new CountDownLatch(1);
        // A 的 OutboxWriter 在拿到行锁后（requestPublication 内）挂起 1.5s，把并发窗口钉死
        OutboxWriter slowWriter = new OutboxWriter(new PostgresOutboxCommandRepository(controlJdbc),
                new PostgresSequenceAllocator(controlJdbc), cas,
                new PostgresArtifactRepository(controlJdbc)) {
            @Override
            public OutboxCommand requestPublication(PublicationRequest request) {
                aHoldsRowLock.countDown();
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.requestPublication(request);
            }
        };

        ConcurrentLinkedQueue<Throwable> escaped = new ConcurrentLinkedQueue<>();
        AtomicBoolean resultA = new AtomicBoolean();
        AtomicBoolean resultB = new AtomicBoolean();
        AtomicLong aDoneNanos = new AtomicLong();
        AtomicLong bDoneNanos = new AtomicLong();

        Thread a = Thread.ofVirtual().name("ct28-planner-a").start(() -> {
            try {
                resultA.set(Boolean.TRUE.equals(
                        controlTx.execute(tx -> dispatcher(slowWriter).dispatch(f.requestId(), preparedA))));
            } catch (Throwable t) {
                escaped.add(t);
            } finally {
                aDoneNanos.set(System.nanoTime());
            }
        });
        assertThat(aHoldsRowLock.await(10, TimeUnit.SECONDS)).isTrue();
        Thread b = Thread.ofVirtual().name("ct28-planner-b").start(() -> {
            try {
                // SKIP LOCKED：A 持锁期间 B 立即领不到 → 返回 false，不阻塞等锁
                resultB.set(Boolean.TRUE.equals(
                        controlTx.execute(tx -> dispatcher(outboxWriter()).dispatch(f.requestId(), preparedB))));
            } catch (Throwable t) {
                escaped.add(t);
            } finally {
                bDoneNanos.set(System.nanoTime());
            }
        });
        a.join(30_000);
        b.join(30_000);

        assertThat(escaped).isEmpty();
        assertThat(bDoneNanos.get()).as("B 零阻塞完成于 A 持锁挂起期间").isLessThan(aDoneNanos.get());
        assertThat(resultA.get()).isTrue();
        assertThat(resultB.get()).isFalse();

        // 只铸一条 repair 命令、一个 REPAIR Run、一条 dispatch 事件；request 指向该命令
        assertThat(repairCommandCount()).isEqualTo(1);
        assertThat(countWhere("review_run", "run_mode='REPAIR'")).isEqualTo(1);
        assertThat(countWhere("execution_event", "event_type='REPAIR_DISPATCHED'")).isEqualTo(1);
        assertThat(repairStateOf(f.requestId())).isEqualTo("DISPATCHED");
        UUID mintedOp = repairCommandOperationId();
        assertThat(adminJdbc.sql("SELECT repair_operation_id FROM repair_request WHERE id=:id")
                .param("id", f.requestId()).query(UUID.class).single()).isEqualTo(mintedOp);
    }

    // ------------------------------------------------------------------ 崩溃注入：插 outbox 前

    @Test
    void crashBeforeOutboxInsertLeavesZeroCommandThenRecoveryMintsOne() {
        Fixture f = newFixture("ct28-crash-pre");
        RepairDispatchService crashing = dispatcher(new CrashingOutboxWriter(true,
                new PostgresOutboxCommandRepository(controlJdbc),
                new PostgresSequenceAllocator(controlJdbc), cas,
                new PostgresArtifactRepository(controlJdbc)));

        assertThatThrownBy(() -> controlTx.executeWithoutResult(
                tx -> crashing.dispatch(f.requestId(), prepare(f)))).isInstanceOf(SimulatedCrash.class);
        assertZeroRepairArtifacts(f.requestId());

        // 恢复：重放同一意图，恰好一条命令
        assertThat(Boolean.TRUE.equals(controlTx.execute(
                tx -> dispatcher(outboxWriter()).dispatch(f.requestId(), prepare(f))))).isTrue();
        assertThat(repairCommandCount()).isEqualTo(1);
        assertThat(repairStateOf(f.requestId())).isEqualTo("DISPATCHED");
    }

    // ------------------------------------------------------------------ 崩溃注入：插 outbox 后（提交前）

    @Test
    void crashAfterOutboxInsertRollsBackThenRecoveryMintsOne() {
        Fixture f = newFixture("ct28-crash-post");
        RepairDispatchService crashing = dispatcher(new CrashingOutboxWriter(false,
                new PostgresOutboxCommandRepository(controlJdbc),
                new PostgresSequenceAllocator(controlJdbc), cas,
                new PostgresArtifactRepository(controlJdbc)));

        assertThatThrownBy(() -> controlTx.executeWithoutResult(
                tx -> crashing.dispatch(f.requestId(), prepare(f)))).isInstanceOf(SimulatedCrash.class);
        // 命令已插但未提交：回滚后依然是零命令，无半截态
        assertZeroRepairArtifacts(f.requestId());

        assertThat(Boolean.TRUE.equals(controlTx.execute(
                tx -> dispatcher(outboxWriter()).dispatch(f.requestId(), prepare(f))))).isTrue();
        assertThat(repairCommandCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 崩溃在提交后：一条命令，不重复铸

    @Test
    void crashAfterCommitKeepsExactlyOneCommand() {
        Fixture f = newFixture("ct28-crash-commit");
        RepairCommandFactory.Prepared prepared = prepare(f);
        assertThat(Boolean.TRUE.equals(controlTx.execute(
                tx -> dispatcher(outboxWriter()).dispatch(f.requestId(), prepared)))).isTrue();
        assertThat(repairCommandCount()).isEqualTo(1);
        // 此处模拟 Planner 进程死亡（命令已提交）……

        // ……重启后重领同一单：DISPATCHED 不在扫描面，拒绝重复铸造
        // （dispatch 在 lockReady 空集处即返回 false，prepared 参数不会被使用）
        assertThat(requests.lockReady(f.requestId())).isEmpty();
        assertThat(Boolean.TRUE.equals(controlTx.execute(
                tx -> dispatcher(outboxWriter()).dispatch(f.requestId(), prepared)))).isFalse();
        assertThat(repairCommandCount()).isEqualTo(1);
        assertThat(countWhere("execution_event", "event_type='REPAIR_DISPATCHED'")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ REPAIR Run 形态与命令血缘

    @Test
    void repairRunStaysOffClaimSurfaceAndCommandLineageIsMonotone() throws Exception {
        Fixture f = newFixture("ct28-lineage");
        assertThat(Boolean.TRUE.equals(controlTx.execute(
                tx -> dispatcher(outboxWriter()).dispatch(f.requestId(), prepare(f))))).isTrue();

        // REPAIR Run 零 Step、不进 work_item 领取面
        UUID repairRunId = adminJdbc.sql(
                "SELECT repair_run_id FROM repair_request WHERE id=:id")
                .param("id", f.requestId()).query(UUID.class).single();
        assertThat(adminJdbc.sql("SELECT count(*) FROM run_step WHERE review_run_id=:id")
                .param("id", repairRunId).query(Long.class).single()).isZero();
        assertThat(count("work_item")).isZero();
        assertThat(new PostgresWorkItemRepository(controlJdbc).claimNext("ct28-w", 600)).isEmpty();

        // REPAIR Run：CREATED、允许发布（RM2-10）、run_key=sha256("repair:{operation_id}")
        UUID repairOp = repairCommandOperationId();
        var runRow = adminJdbc.sql("""
                SELECT state, publisher_disabled, run_key FROM review_run WHERE id=:id
                """).param("id", repairRunId).query((rs, n) -> new Object[]{
                rs.getString(1), rs.getBoolean(2), rs.getString(3)}).single();
        assertThat(runRow[0]).isEqualTo("CREATED");
        assertThat(runRow[1]).isEqualTo(false);
        assertThat(runRow[2]).isEqualTo(Digest.sha256Of("repair:" + repairOp).value());

        // sequence 单调：repair 命令 sequence=2 > 原命令 1（同 aggregate_key 取号链）
        var cmdRow = adminJdbc.sql("""
                SELECT aggregate_sequence, fence_mode, command_type, state, payload_hash
                  FROM outbox_command WHERE operation_id=:op
                """).param("op", repairOp).query((rs, n) -> new Object[]{
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)})
                .single();
        assertThat((Long) cmdRow[0]).isGreaterThan(1L);
        assertThat(cmdRow[1]).isEqualTo("CURRENT_EPOCH");
        assertThat(cmdRow[2]).isEqualTo("CREATE_CHECK");
        assertThat(cmdRow[3]).isEqualTo("PENDING");

        // depends_on 指原命令（REQUIRE_CONFIRMED）
        var dep = adminJdbc.sql("""
                SELECT depends_on_operation_id, dependency_mode FROM outbox_dependency
                 WHERE operation_id=:op
                """).param("op", repairOp).query((rs, n) -> new Object[]{
                rs.getObject(1, UUID.class), rs.getString(2)}).single();
        assertThat(dep[0]).isEqualTo(f.originalOperationId());
        assertThat(dep[1]).isEqualTo("REQUIRE_CONFIRMED");

        // payload：携带 repair 血缘（request/resource/新 operation_id），不含旧远端身份
        JsonNode payload = om.readTree(cas.get(new Digest(((String) cmdRow[4]).trim())).orElseThrow());
        assertThat(payload.get("repair_request_id").asText()).isEqualTo(f.requestId().toString());
        assertThat(payload.get("repair_of_resource_id").asText()).isEqualTo(f.resourceId().toString());
        assertThat(payload.get("operation_id").asText()).isEqualTo(repairOp.toString());
        assertThat(payload.has("remote_id")).isFalse();
        assertThat(payload.has("remote_url")).isFalse();
        assertThat(payload.has("check_run_id")).isFalse();
    }

    /** 崩溃后零态断言：无 REPAIR Run、无 repair 命令、无 dispatch 事件、无 artifact 登记、request 仍 PENDING。 */
    private void assertZeroRepairArtifacts(UUID requestId) {
        assertThat(repairCommandCount()).isZero();
        assertThat(countWhere("review_run", "run_mode='REPAIR'")).isZero();
        assertThat(countWhere("execution_event", "event_type='REPAIR_DISPATCHED'")).isZero();
        assertThat(count("artifact")).isZero();
        assertThat(repairStateOf(requestId)).isEqualTo("PENDING");
        assertThat(adminJdbc.sql(
                "SELECT repair_run_id IS NULL AND repair_operation_id IS NULL FROM repair_request WHERE id=:id")
                .param("id", requestId).query(Boolean.class).single()).isTrue();
    }

    private UUID repairCommandOperationId() {
        return adminJdbc.sql("""
                SELECT o.operation_id FROM outbox_command o JOIN review_run r ON r.id = o.review_run_id
                 WHERE r.run_mode = 'REPAIR'
                """).query(UUID.class).single();
    }

    private long countWhere(String table, String where) {
        return adminJdbc.sql("SELECT count(*) FROM " + table + " WHERE " + where)
                .query(Long.class).single();
    }

    private static final class SimulatedCrash extends RuntimeException {
        SimulatedCrash() {
            super("simulated planner crash", null, false, false);
        }
    }

    /** 故障注入 OutboxWriter：crashBefore=true 在插 outbox 前崩；false 在插后（提交前）崩。 */
    private static final class CrashingOutboxWriter extends OutboxWriter {
        private final boolean crashBefore;

        CrashingOutboxWriter(boolean crashBefore, OutboxCommandRepository outboxRepository,
                             SequenceAllocator sequenceAllocator, ArtifactStore artifactStore,
                             ArtifactRepository artifactRepository) {
            super(outboxRepository, sequenceAllocator, artifactStore, artifactRepository);
            this.crashBefore = crashBefore;
        }

        @Override
        public OutboxCommand requestPublication(PublicationRequest request) {
            if (crashBefore) {
                throw new SimulatedCrash();
            }
            OutboxCommand command = super.requestPublication(request);
            throw new SimulatedCrash();
        }
    }
}
