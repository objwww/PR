package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.domain.mutation.OperationStatus;
import com.objwww.pr.control.alert.domain.mutation.RcaOperation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BA-191 真执行链单测：白名单三元命中 → prepareReal（dry_run=false）→ 路由真派发；
 * 白名单未命中/停用/env 不符 → dry_run 保持不变；真执行 FAILED → FAILED_CONFIRMED
 * 终态 + 中文原因留痕 + 锁放行 + 零 ACK/VERIFIED 事件；路由缺席 → UNKNOWN 不静默。
 * 假件复用 {@link Pb4OutboxChainTest} 同包夹具。
 */
class Ba191RealRollbackChainTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"),
            ZoneOffset.UTC);
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID INTENT = UUID.randomUUID();
    private static final String FLAG_UID = "flag://flagd/paymentFailure";

    /** V121 注册表假件：一行 scope + inventory env 面 */
    static final class FakeUnlockScopes implements UnlockScopeStore {
        UnlockScope scope;
        String envOfUid = "production";

        @Override
        public Optional<UnlockScope> findByTool(String toolName) {
            return Optional.ofNullable(scope)
                    .filter(s -> s.toolName().equals(toolName));
        }

        @Override
        public Optional<String> envOfResource(String resourceUid) {
            return Optional.ofNullable(envOfUid);
        }
    }

    private static Pb4OutboxChainTest.FakeIntents rollbackIntents() {
        var intents = new Pb4OutboxChainTest.FakeIntents();
        intents.rows.put(INTENT, new ActionIntentStore.IntentView(INTENT, RUN, null,
                "a".repeat(64), "service.rollback", "R3", FLAG_UID, "h".repeat(64),
                "{\"service\":\"payment\"}"));
        return intents;
    }

    private static OperationPlanner planner(Pb4OutboxChainTest.FakeIntents intents,
            Pb4OutboxChainTest.FakeOperations operations,
            Pb4OutboxChainTest.FakeOutbox outbox, Pb4OutboxChainTest.FakeLocks locks,
            Pb4OutboxChainTest.FakeEvents events, UnlockScopeStore scopes) {
        return new OperationPlanner(intents, operations, outbox, locks, null, events,
                new Pb4OutboxChainTest.DirectTx(), TTL, true, false, null, scopes, FIXED);
    }

    @Test
    @DisplayName("白名单三元命中：prepareReal 铸造（dry_run=false）→ 路由真派发全链 COMPLETED")
    void unlockHitRoutesToRealExecutor() {
        var intents = rollbackIntents();
        var operations = new Pb4OutboxChainTest.FakeOperations();
        var outbox = new Pb4OutboxChainTest.FakeOutbox();
        var locks = new Pb4OutboxChainTest.FakeLocks();
        var events = new Pb4OutboxChainTest.FakeEvents();
        var scopes = new FakeUnlockScopes();
        scopes.scope = new UnlockScopeStore.UnlockScope("service.rollback", FLAG_UID,
                "production", true);

        var planned = planner(intents, operations, outbox, locks, events, scopes)
                .plan(INTENT);
        assertThat(planned.status()).isEqualTo(OperationPlanner.Outcome.Status.PLANNED);
        assertThat(operations.rows.get(planned.operationId()).dryRun()).isFalse();
        assertThat(events.rows.get(0).payload())
                .contains("\"dry_run\":false").contains("\"unlocked\":true");

        // 路由命中：service.rollback → 专属执行面（假件返回 EXECUTED）
        boolean[] realTouched = {false};
        ActionRunner flagdExecutor = op -> {
            realTouched[0] = true;
            return ActionRunner.Outcome.EXECUTED;
        };
        var dispatcher = new OperationOutboxDispatcher(outbox, operations, locks,
                new DryRunActionRunner(DryRunActionRunner.Behavior.SUCCEED),
                new RoutedActionRunner(Map.of("service.rollback", flagdExecutor), null),
                events, "w1", Duration.ofSeconds(30), Duration.ofSeconds(5), FIXED);
        locks.releaseGateOpen = true;

        dispatcher.dispatchOnce();

        assertThat(realTouched[0]).isTrue();
        assertThat(operations.rows.get(planned.operationId()).status())
                .isEqualTo(OperationStatus.COMPLETED);
        assertThat(events.rows.stream().map(Pb4OutboxChainTest.EventRow::type))
                .contains("OPERATION_DISPATCHED", "OPERATION_COMPLETED");
        assertThat(events.rows.stream()
                .filter(r -> r.type().equals("OPERATION_DISPATCHED"))
                .findFirst().orElseThrow().payload()).contains("\"dry_run\":\"false\"");
    }

    @Test
    @DisplayName("白名单未命中/停用/env 不符：dry_run=true 保持不变（模拟执行面零漂移）")
    void unlockMissStaysDryRun() {
        // 三种未命中形：无行 / enabled=false / env 不符
        UnlockScopeStore[] misses = {
                new FakeUnlockScopes(), // 无行
                withScope(new UnlockScopeStore.UnlockScope("service.rollback", FLAG_UID,
                        "production", false)),
                withScope(new UnlockScopeStore.UnlockScope("service.rollback", FLAG_UID,
                        "staging", true))};
        for (UnlockScopeStore scopes : misses) {
            var operations = new Pb4OutboxChainTest.FakeOperations();
            var outbox = new Pb4OutboxChainTest.FakeOutbox();
            var locks = new Pb4OutboxChainTest.FakeLocks();
            var events = new Pb4OutboxChainTest.FakeEvents();
            var planned = planner(rollbackIntents(), operations, outbox, locks, events,
                    scopes).plan(INTENT);
            assertThat(planned.status()).isEqualTo(OperationPlanner.Outcome.Status.PLANNED);
            assertThat(operations.rows.get(planned.operationId()).dryRun()).isTrue();
            assertThat(events.rows.get(0).payload())
                    .contains("\"dry_run\":true").contains("\"unlocked\":false");
        }
    }

    private static UnlockScopeStore withScope(UnlockScopeStore.UnlockScope scope) {
        var scopes = new FakeUnlockScopes();
        scopes.scope = scope;
        return scopes;
    }

    @Test
    @DisplayName("真执行 FAILED：FAILED_CONFIRMED 终态 + 中文原因留痕 + 锁放行 + 零 ACK/VERIFIED")
    void realFailureWalksToFailedConfirmed() {
        var operations = new Pb4OutboxChainTest.FakeOperations();
        var outbox = new Pb4OutboxChainTest.FakeOutbox();
        var locks = new Pb4OutboxChainTest.FakeLocks();
        var events = new Pb4OutboxChainTest.FakeEvents();
        UUID opId = UUID.randomUUID();
        operations.insert(RcaOperation.prepareReal(opId, INTENT, RUN, null,
                "service.rollback", "a".repeat(64), FLAG_UID, 1,
                "{\"service\":\"payment\"}", FIXED.instant()));
        locks.acquire(FLAG_UID, opId, RUN, TTL, FIXED.instant());
        outbox.insert(UUID.randomUUID(), opId, FIXED.instant());
        ActionRunner failing = new ActionRunner() {
            @Override
            public Outcome run(RcaOperation operation) {
                return Outcome.FAILED;
            }

            @Override
            public Result runDetailed(RcaOperation operation) {
                return new Result(Outcome.FAILED,
                        "flag_restore_conflict: 他者已改写不覆盖，确定性判败不重试");
            }
        };
        var dispatcher = new OperationOutboxDispatcher(outbox, operations, locks,
                new DryRunActionRunner(DryRunActionRunner.Behavior.SUCCEED),
                new RoutedActionRunner(Map.of("service.rollback", failing), null),
                events, "w1", Duration.ofSeconds(30), Duration.ofSeconds(5), FIXED);
        locks.releaseGateOpen = true;

        dispatcher.dispatchOnce();

        RcaOperation op = operations.rows.get(opId);
        assertThat(op.status()).isEqualTo(OperationStatus.FAILED_CONFIRMED);
        var types = events.rows.stream().map(Pb4OutboxChainTest.EventRow::type).toList();
        assertThat(types).contains("OPERATION_FAILED", "OPERATION_LOCK_RELEASED");
        assertThat(types).doesNotContain("OPERATION_ACKNOWLEDGED", "OPERATION_VERIFIED",
                "OPERATION_COMPLETED");
        assertThat(events.rows.stream()
                .filter(r -> r.type().equals("OPERATION_FAILED")).findFirst().orElseThrow()
                .payload()).contains("他者已改写不覆盖");
        assertThat(locks.released).hasSize(1);
    }

    @Test
    @DisplayName("路由缺席且无兜底：UNKNOWN 不静默（锁保持，reconcile 裁决），原因留痕")
    void routeAbsentIsHonestUnknown() {
        var operations = new Pb4OutboxChainTest.FakeOperations();
        var outbox = new Pb4OutboxChainTest.FakeOutbox();
        var locks = new Pb4OutboxChainTest.FakeLocks();
        var events = new Pb4OutboxChainTest.FakeEvents();
        UUID opId = UUID.randomUUID();
        operations.insert(RcaOperation.prepareReal(opId, INTENT, RUN, null,
                "chaos.resolve", "a".repeat(64), "res://demo/checkout", 1, "{}",
                FIXED.instant()));
        locks.acquire("res://demo/checkout", opId, RUN, TTL, FIXED.instant());
        outbox.insert(UUID.randomUUID(), opId, FIXED.instant());
        var dispatcher = new OperationOutboxDispatcher(outbox, operations, locks,
                new DryRunActionRunner(DryRunActionRunner.Behavior.SUCCEED),
                new RoutedActionRunner(Map.of(), null), events, "w1",
                Duration.ofSeconds(30), Duration.ofSeconds(5), FIXED);

        dispatcher.dispatchOnce();

        assertThat(operations.rows.get(opId).status()).isEqualTo(OperationStatus.UNKNOWN);
        assertThat(locks.locks).containsKey("res://demo/checkout"); // 锁保持 BUSY
        assertThat(events.rows.stream()
                .filter(r -> r.type().equals("OPERATION_UNKNOWN")).findFirst().orElseThrow()
                .payload()).contains("REAL_EXECUTOR_ROUTE_ABSENT");
    }
}
