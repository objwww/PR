package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.model.EvalLaunchPlan;
import com.objwww.pr.control.eval.domain.model.EvalRunCommand;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import com.objwww.pr.control.eval.domain.repository.EvalRunCommandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EV-04 发起/取消命令服务（假端口真断言；EU09/EU10 应用层面）：
 * 幂等键同计划重放返回原 run、异计划 409；取消仅 RUNNING 受理、终态 409、
 * 同键重放、异键重复取消幂等受理（取消中语义）、未知 run 404 面。
 */
class EvalCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");

    /** uq(command_type, idempotency_key) 语义的内存账本 */
    private static final class InMemoryCommands implements EvalRunCommandRepository {
        final Map<String, EvalRunCommand> byKey = new HashMap<>();
        final List<EvalRunCommand> inserted = new ArrayList<>();

        @Override
        public void insert(EvalRunCommand command) {
            String key = command.commandType() + "/" + command.idempotencyKey();
            if (byKey.containsKey(key)) {
                throw new DuplicateKeyException("uq_eval_run_command_idem");
            }
            byKey.put(key, command);
            inserted.add(command);
        }

        @Override
        public Optional<EvalRunCommand> findByKey(EvalRunCommand.Type type,
                                                  String idempotencyKey) {
            return Optional.ofNullable(byKey.get(type + "/" + idempotencyKey));
        }

        @Override
        public boolean cancelAccepted(UUID evalRunId) {
            return byKey.values().stream().anyMatch(c -> c.evalRunId().equals(evalRunId)
                    && c.commandType() == EvalRunCommand.Type.CANCEL);
        }

        @Override
        public Optional<Instant> cancelRequestedAt(UUID evalRunId) {
            return byKey.values().stream()
                    .filter(c -> c.evalRunId().equals(evalRunId)
                            && c.commandType() == EvalRunCommand.Type.CANCEL)
                    .map(EvalRunCommand::createdAt).min(Instant::compareTo);
        }

        @Override
        public Optional<EvalRunCommand> claimNextLaunch(String workerId, Instant claimedAt) {
            return Optional.empty();
        }

        @Override
        public boolean finish(UUID id, EvalRunCommand.State terminal, Instant finishedAt) {
            return true;
        }

        @Override
        public List<EvalRunCommand> findOrphanedClaims(Instant before) {
            return List.of();
        }

        @Override
        public boolean requeue(UUID id) {
            return true;
        }
    }

    /** 只承载 findRun 状态的读面桩 */
    private static final class StubReader implements EvalQueryReader {
        String state;
        UUID knownRunId;

        @Override
        public EvalRunPage listRuns(String state, KeysetCursor cursor, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<EvalRunRow> findRun(UUID runId) {
            if (knownRunId == null || !knownRunId.equals(runId)) {
                return Optional.empty();
            }
            return Optional.of(new EvalRunRow(runId, "ds", "r".repeat(64), "m", "p",
                    "c".repeat(64), state, NOW, null,
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, 0, null, null, null,
                    null, null, null, null));
        }

        @Override
        public EvalCasePage listCases(UUID runId, String verdict, String afterScenario,
                                      Integer afterRound, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DatasetRow> listDatasets() {
            return List.of();
        }
    }

    private InMemoryCommands commands;
    private StubReader reader;
    private EvalCommandService service;

    @BeforeEach
    void setUp() {
        commands = new InMemoryCommands();
        reader = new StubReader();
        service = new EvalCommandService(commands, reader, new ObjectMapper());
    }

    private static EvalLaunchPlan plan(String name) {
        return new EvalLaunchPlan(name, "L", "eval-ds-1", null, null,
                10000L, null, 3600L, null);
    }

    // ------------------------------------------------------------------ EU09 发起幂等

    @Test
    @DisplayName("发起受理：命令 PENDING 落库（先持久化再生效），202 面载荷带 run/command id")
    void launchPersistsPendingCommand() {
        EvalCommandService.LaunchResult result =
                service.launch(plan("实验甲"), "key-1", "operator");

        assertThat(result.status()).isEqualTo(EvalCommandService.LaunchStatus.ACCEPTED);
        assertThat(commands.inserted).hasSize(1);
        EvalRunCommand stored = commands.inserted.get(0);
        assertThat(stored.state()).isEqualTo(EvalRunCommand.State.PENDING);
        assertThat(stored.commandType()).isEqualTo(EvalRunCommand.Type.LAUNCH);
        assertThat(stored.evalRunId()).isEqualTo(result.runId());
        assertThat(stored.actor()).isEqualTo("operator");
        assertThat(stored.payloadHash()).isEqualTo(plan("实验甲").payloadHash().value());
        assertThat(stored.payloadJson()).contains("\"mode\":\"L\"")
                .contains("\"budgetMaxTokens\":10000");
    }

    @Test
    @DisplayName("EU09 响应丢失重试：同键同计划 → REPLAYED 返回原 run，账本仍只一行")
    void sameKeySamePlanReplaysOriginalRun() {
        EvalCommandService.LaunchResult first =
                service.launch(plan("实验甲"), "key-1", "operator");

        EvalCommandService.LaunchResult replay = service.launch(plan("实验甲"), "key-1",
                "operator");

        assertThat(replay.status()).isEqualTo(EvalCommandService.LaunchStatus.REPLAYED);
        assertThat(replay.runId()).isEqualTo(first.runId());
        assertThat(replay.commandId()).isEqualTo(first.commandId());
        assertThat(commands.inserted).hasSize(1);
    }

    @Test
    @DisplayName("EU09 异 payload：同键不同计划 → CONFLICT（409 面），带既有 runId")
    void sameKeyDifferentPlanConflicts() {
        service.launch(plan("实验甲"), "key-1", "operator");

        EvalCommandService.LaunchResult conflict = service.launch(plan("实验乙"), "key-1",
                "operator");

        assertThat(conflict.status()).isEqualTo(EvalCommandService.LaunchStatus.CONFLICT);
        assertThat(conflict.runId()).isNotNull();
    }

    @Test
    @DisplayName("idempotencyKey 空白/超长 → 400 面（IllegalArgumentException）")
    void blankIdempotencyKeyRejected() {
        assertThatThrownBy(() -> service.launch(plan("实验甲"), " ", "operator"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
        assertThatThrownBy(() -> service.launch(plan("实验甲"), "x".repeat(129), "operator"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(commands.inserted).isEmpty();
    }

    // ------------------------------------------------------------------ 取消受理

    @Test
    @DisplayName("RUNNING run 取消受理：CANCEL 命令落库，ACCEPTED（=取消中，非已停）")
    void cancelOnRunningAccepted() {
        UUID runId = UUID.randomUUID();
        reader.knownRunId = runId;
        reader.state = "RUNNING";

        EvalCommandService.CancelResult result =
                service.cancel(runId, "ck-1", "误发实验", "operator").orElseThrow();

        assertThat(result.status()).isEqualTo(EvalCommandService.CancelStatus.ACCEPTED);
        assertThat(commands.inserted).hasSize(1);
        EvalRunCommand stored = commands.inserted.get(0);
        assertThat(stored.commandType()).isEqualTo(EvalRunCommand.Type.CANCEL);
        assertThat(stored.evalRunId()).isEqualTo(runId);
        assertThat(stored.payloadJson()).contains("误发实验");
        assertThat(commands.cancelAccepted(runId)).isTrue();
    }

    @Test
    @DisplayName("终态 run 取消 = 非法迁移：CONFLICT_TERMINAL（409 面）零命令落库")
    void cancelOnTerminalRejected() {
        UUID runId = UUID.randomUUID();
        reader.knownRunId = runId;
        for (String terminal : new String[]{"SUCCEEDED", "FAILED"}) {
            reader.state = terminal;

            EvalCommandService.CancelResult result =
                    service.cancel(runId, "ck-" + terminal, null, "operator").orElseThrow();

            assertThat(result.status())
                    .isEqualTo(EvalCommandService.CancelStatus.CONFLICT_TERMINAL);
        }
        assertThat(commands.inserted).isEmpty();
    }

    @Test
    @DisplayName("未知 run 取消 → empty（404 面）")
    void cancelUnknownRunIsEmpty() {
        assertThat(service.cancel(UUID.randomUUID(), "ck-1", null, "operator")).isEmpty();
    }

    @Test
    @DisplayName("同键取消重放返回原命令（REPLAYED）；同键指向别的 run → CONFLICT_KEY")
    void cancelKeyReplayAndCrossRunConflict() {
        UUID runId = UUID.randomUUID();
        reader.knownRunId = runId;
        reader.state = "RUNNING";
        EvalCommandService.CancelResult first =
                service.cancel(runId, "ck-1", null, "operator").orElseThrow();

        EvalCommandService.CancelResult replay =
                service.cancel(runId, "ck-1", null, "operator").orElseThrow();
        assertThat(replay.status()).isEqualTo(EvalCommandService.CancelStatus.REPLAYED);
        assertThat(replay.commandId()).isEqualTo(first.commandId());
        assertThat(commands.inserted).hasSize(1);

        UUID other = UUID.randomUUID();
        reader.knownRunId = other;
        EvalCommandService.CancelResult cross =
                service.cancel(other, "ck-1", null, "operator").orElseThrow();
        assertThat(cross.status()).isEqualTo(EvalCommandService.CancelStatus.CONFLICT_KEY);
        assertThat(commands.inserted).hasSize(1);
    }

    @Test
    @DisplayName("异键重复取消：幂等受理（仍取消中语义），命令账本如实多一行（审计）")
    void repeatedCancelWithNewKeyAcceptedIdempotently() {
        UUID runId = UUID.randomUUID();
        reader.knownRunId = runId;
        reader.state = "RUNNING";

        service.cancel(runId, "ck-1", null, "operator");
        EvalCommandService.CancelResult second =
                service.cancel(runId, "ck-2", "再点一次", "operator").orElseThrow();

        assertThat(second.status()).isEqualTo(EvalCommandService.CancelStatus.ACCEPTED);
        assertThat(commands.inserted).hasSize(2);
        assertThat(commands.cancelAccepted(runId)).isTrue();
    }
}
