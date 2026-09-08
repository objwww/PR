package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.model.OperatorCommand;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.OperatorCommandRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CommandService 单测（M5-14；落码方案 §M5-14③/④）：先持久化再生效（崩溃窗口
 * 重放续走 apply）、幂等键重放返回原命令行（含原拒绝态）、旧 revision REJECTED_STALE
 * 零副作用、终态 Run 越权 REJECTED_FORBIDDEN、Hint 标 UNTRUSTED 且文本不进事件。
 */
class CommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final Supplier<Instant> CLOCK = () -> NOW;

    private FakeCommands commands = new FakeCommands();
    private FakeRuns runs = new FakeRuns();
    private FakeAppender appender = new FakeAppender();
    private CommandService service;

    @BeforeEach
    void setUp() {
        service = new CommandService(commands, runs, appender, CLOCK);
    }

    @Test
    void cancelPersistsFirstThenAppliesRunTransitionAndEvent() {
        UUID runId = run(RcaRunState.RUNNING);

        CommandService.Result result =
                service.submit(runId, OperatorCommand.Type.CANCEL, "op-1", 0, Map.of(), "operator");

        assertThat(result.state()).isEqualTo(OperatorCommand.State.APPLIED);
        assertThat(result.replayed()).isFalse();
        // 先持久化再生效：insert(PERSISTED) 先于终态推进
        assertThat(commands.opOrder).containsExactly("insert", "updateState");
        // 生效 = run 状态机迁移 + 收尾时间戳 + 状态事实事件
        RcaRun cancelled = runs.rows.get(runId);
        assertThat(cancelled.state()).isEqualTo(RcaRunState.CANCELLED);
        assertThat(cancelled.finishedAt()).isEqualTo(NOW);
        assertThat(appender.events).hasSize(1);
        assertThat(appender.events.get(0).eventType()).isEqualTo("RUN_CANCELLED");
        assertThat(commands.rows.values().iterator().next().state())
                .isEqualTo(OperatorCommand.State.APPLIED);
    }

    @Test
    void staleRevisionRejectedWithZeroSideEffectAndReplaysRejection() {
        UUID runId = run(RcaRunState.RUNNING);

        CommandService.Result first =
                service.submit(runId, OperatorCommand.Type.CANCEL, "op-1", 5, Map.of(), "operator");
        assertThat(first.state()).isEqualTo(OperatorCommand.State.REJECTED_STALE);
        // 零副作用：run 不动、无事件、命令行停在 REJECTED_STALE
        assertThat(runs.rows.get(runId).state()).isEqualTo(RcaRunState.RUNNING);
        assertThat(appender.events).isEmpty();
        assertThat(commands.rows.values().iterator().next().state())
                .isEqualTo(OperatorCommand.State.REJECTED_STALE);

        // 幂等重放：原命令行 + 原拒绝态原样返回
        CommandService.Result replay =
                service.submit(runId, OperatorCommand.Type.CANCEL, "op-1", 5, Map.of(), "operator");
        assertThat(replay.state()).isEqualTo(OperatorCommand.State.REJECTED_STALE);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.commandId()).isEqualTo(first.commandId());
        assertThat(commands.rows).hasSize(1);
    }

    @Test
    void forbiddenOnTerminalRunAndReplaysRejection() {
        UUID runId = run(RcaRunState.SUCCEEDED);

        CommandService.Result result =
                service.submit(runId, OperatorCommand.Type.HINT, "op-1", 0, Map.of("text", "x"), "operator");

        assertThat(result.state()).isEqualTo(OperatorCommand.State.REJECTED_FORBIDDEN);
        assertThat(appender.events).isEmpty();
        CommandService.Result replay =
                service.submit(runId, OperatorCommand.Type.HINT, "op-1", 0, Map.of("text", "x"), "operator");
        assertThat(replay.state()).isEqualTo(OperatorCommand.State.REJECTED_FORBIDDEN);
        assertThat(replay.replayed()).isTrue();
    }

    @Test
    void hintAppliesMarkedUntrustedWithoutTextLeakingIntoEvent() {
        UUID runId = run(RcaRunState.RUNNING);
        Map<String, Object> payload = Map.of("text", "重点查 payments 表锁等待");

        CommandService.Result result =
                service.submit(runId, OperatorCommand.Type.HINT, "op-1", 0, payload, "sre-li");

        assertThat(result.state()).isEqualTo(OperatorCommand.State.APPLIED);
        // Hint 文本只落命令行 payload（上下文消费面读表），不复制进事件流
        assertThat(commands.rows.values().iterator().next().payload())
                .containsEntry("text", "重点查 payments 表锁等待");
        assertThat(appender.events).hasSize(1);
        String eventPayload = appender.events.get(0).payloadJson();
        assertThat(eventPayload).contains("UNTRUSTED");
        assertThat(eventPayload).doesNotContain("payments 表锁等待");
        // run 行零改动（Hint/Feedback 不迁移 Run 状态）
        assertThat(runs.rows.get(runId).state()).isEqualTo(RcaRunState.RUNNING);
    }

    @Test
    void idempotentReplayReturnsOriginalCommandWithoutDoubleEffect() {
        UUID runId = run(RcaRunState.RUNNING);

        CommandService.Result first =
                service.submit(runId, OperatorCommand.Type.CANCEL, "op-1", 0, Map.of(), "operator");
        CommandService.Result replay =
                service.submit(runId, OperatorCommand.Type.CANCEL, "op-1", 0, Map.of(), "operator");

        assertThat(replay.commandId()).isEqualTo(first.commandId());
        assertThat(replay.replayed()).isTrue();
        assertThat(commands.rows).hasSize(1);
        assertThat(appender.events).as("重放零二次生效").hasSize(1);
    }

    @Test
    void crashBeforeApplyResumesOnRetryWithSameKey() {
        // 生效前崩溃重放：phase1 已落 PERSISTED 行，retry 同键续走 apply
        UUID runId = run(RcaRunState.RUNNING);
        UUID orphanId = UUID.randomUUID();
        commands.rows.put(orphanId, persisted(orphanId, runId, OperatorCommand.Type.CANCEL, "op-1"));

        CommandService.Result result =
                service.submit(runId, OperatorCommand.Type.CANCEL, "op-1", 0, Map.of(), "operator");

        assertThat(result.state()).isEqualTo(OperatorCommand.State.APPLIED);
        assertThat(result.commandId()).isEqualTo(orphanId);
        assertThat(runs.rows.get(runId).state()).isEqualTo(RcaRunState.CANCELLED);
        assertThat(appender.events).hasSize(1);
    }

    @Test
    void cancelEffectAlreadyLandedRecoversMarkWithoutDoubleEvent() {
        // 生效已落、标记未落的崩溃窗口：run 已 CANCELLED + 行仍 PERSISTED → 恢复面
        // 补标 APPLIED，不重放事件、不再迁移 run
        UUID runId = run(RcaRunState.CANCELLED);
        UUID orphanId = UUID.randomUUID();
        commands.rows.put(orphanId, persisted(orphanId, runId, OperatorCommand.Type.CANCEL, "op-1"));

        CommandService.Result result =
                service.submit(runId, OperatorCommand.Type.CANCEL, "op-1", 0, Map.of(), "operator");

        assertThat(result.state()).isEqualTo(OperatorCommand.State.APPLIED);
        assertThat(appender.events).as("恢复面不重放事件").isEmpty();
    }

    @Test
    void concurrentSameKeyLoserReadsWinnerRowAsReplay() {
        UUID runId = run(RcaRunState.RUNNING);
        UUID winnerId = UUID.randomUUID();
        commands.rows.put(winnerId, persisted(winnerId, runId, OperatorCommand.Type.CANCEL, "op-1"));
        commands.simulateUniqueOnInsert = true;

        CommandService.Result result =
                service.submit(runId, OperatorCommand.Type.CANCEL, "op-1", 0, Map.of(), "operator");

        assertThat(result.commandId()).isEqualTo(winnerId);
        assertThat(result.replayed()).isTrue();
    }

    // ------------------------------------------------------------------ fixtures

    private UUID run(RcaRunState state) {
        UUID id = UUID.randomUUID();
        runs.rows.put(id, new RcaRun(id, UUID.randomUUID(), 0, RunTrigger.INITIAL, state,
                Digest.sha256Of("inv"), NOW.minus(Duration.ofMinutes(5)), NOW, NOW, null, null));
        return id;
    }

    private static OperatorCommand persisted(UUID id, UUID runId, OperatorCommand.Type type,
                                             String key) {
        return new OperatorCommand(id, runId, type, key, 0, Map.of(),
                OperatorCommand.State.PERSISTED, "operator", NOW.minusSeconds(5), null);
    }

    /** 记录调用序（先持久化再生效断言）；simulateUniqueOnInsert 模拟并发同键败者 */
    static final class FakeCommands implements OperatorCommandRepository {
        final Map<UUID, OperatorCommand> rows = new LinkedHashMap<>();
        final List<String> opOrder = new ArrayList<>();
        boolean simulateUniqueOnInsert;

        @Override
        public void insert(OperatorCommand command) {
            opOrder.add("insert");
            if (simulateUniqueOnInsert
                    || rows.values().stream().anyMatch(r ->
                    r.runId().equals(command.runId()) && r.type() == command.type()
                            && r.idempotencyKey().equals(command.idempotencyKey()))) {
                throw new DuplicateKeyException("uq_operator_command_idem 模拟");
            }
            rows.put(command.id(), command);
        }

        @Override
        public Optional<OperatorCommand> find(UUID runId, OperatorCommand.Type type,
                                              String idempotencyKey) {
            return rows.values().stream()
                    .filter(r -> r.runId().equals(runId) && r.type() == type
                            && r.idempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        @Override
        public boolean updateState(UUID id, OperatorCommand.State state, Instant appliedAt) {
            opOrder.add("updateState");
            OperatorCommand row = rows.get(id);
            if (row == null || row.state().isTerminal()) {
                return false;
            }
            rows.put(id, row.withState(state, appliedAt));
            return true;
        }
    }

    static final class FakeRuns implements RcaRunRepository {
        final Map<UUID, RcaRun> rows = new LinkedHashMap<>();

        @Override
        public void insert(RcaRun run) {
            rows.put(run.id(), run);
        }

        // C-61 fake 镜像：无路由记录 = 普通 insert 存量行 = 默认 HOLMES
        @Override
        public boolean existsNativeRunByIncidentId(UUID incidentId) {
            return false;
        }

        @Override
        public Optional<RcaRun> findByIdForUpdate(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<RcaRun> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public boolean update(RcaRun run) {
            if (!rows.containsKey(run.id())) {
                return false;
            }
            rows.put(run.id(), run);
            return true;
        }

        @Override
        public Optional<RcaRun> findActiveByIncidentId(UUID incidentId) {
            return rows.values().stream()
                    .filter(r -> r.incidentId().equals(incidentId) && r.state().isActive())
                    .findFirst();
        }

        @Override
        public List<RcaRun> findAll() {
            return rows.values().stream()
                    .sorted(Comparator.comparing(RcaRun::createdAt).reversed()
                            .thenComparing(RcaRun::id))
                    .toList();
        }

        @Override
        public Optional<RoutingView> findRoutingById(UUID id) {
            return Optional.empty();
        }

        @Override
        public OptionalLong currentRevision(UUID id) {
            return rows.containsKey(id) ? OptionalLong.of(0) : OptionalLong.empty();
        }
    }

    record AppendedEvent(UUID runId, String eventType, String payloadJson) {
    }

    static final class FakeAppender implements RcaEventAppender {
        final List<AppendedEvent> events = new ArrayList<>();

        @Override
        public long append(UUID runId, EventDraft draft) {
            events.add(new AppendedEvent(runId, draft.eventType(), draft.payloadJson()));
            return events.size();
        }

        @Override
        public long appendIndependent(UUID runId, EventDraft draft) {
            return append(runId, draft);
        }
    }
}
