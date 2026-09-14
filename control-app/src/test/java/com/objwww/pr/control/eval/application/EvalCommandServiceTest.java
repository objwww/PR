package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.model.EvalLaunchPlan;
import com.objwww.pr.control.eval.domain.model.EvalRunCommand;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CompareCaseRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CompareRunMeta;
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
        public Optional<EvalRunCommand> findLatestLaunch(UUID evalRunId) {
            return byKey.values().stream()
                    .filter(c -> c.commandType() == EvalRunCommand.Type.LAUNCH
                            && c.evalRunId().equals(evalRunId))
                    .max(java.util.Comparator.comparing(EvalRunCommand::createdAt));
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
                    null, null, null, null, null));
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

        @Override
        public List<PartitionCountRow> listPartitionCounts() {
            return List.of();
        }

        @Override
        public Optional<EvalCaseDetailRow> findCaseDetail(UUID runId, UUID caseExecutionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CaseIdentityRow> findCaseIdentity(String datasetVersion,
                                                          String scenarioId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CaseEvidenceRefRow> listCaseEvidenceRefs(UUID runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<EvidenceMetaRow> listEvidenceMeta(UUID rcaRunId, List<UUID> evidenceIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CaseLogEvidenceRow> listCaseLogEvidence(UUID runId, String scenarioId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CompareRunMeta> findCompareMeta(UUID runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CompareCaseRow> listCasesForCompare(UUID runId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EvalPhaseEventPage listPhaseEvents(UUID runId, KeysetCursor cursor,
                                                  int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<EvalQueryReader.UsageCallRow> listUsageCalls(UUID evalRunId) {
            return List.of();
        }

        @Override
        public List<EvalQueryReader.UsageCallRow> listUsageCallsForRuns(
                Iterable<UUID> evalRunIds) {
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
        // EU09/取消语义沿用全支持面闸门（budget/deadline 放行）——能力拒绝语义归
        // closed 闸门专项用例（本类末尾 + EvalLaunchGateTest）
        service = new EvalCommandService(commands, reader, new ObjectMapper(),
                new EvalLaunchGate(java.util.Set.of("E", "B", "L"), java.util.Set.of("eval-ds-1"),
                        32, 100, true, true, true, true, true));
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

    // ------------------------------------------------------------------ PAGE-03 能力闸门

    /** 生产同款闭面闸门（仅 L、固定并发 1、覆盖项/限额全闭） */
    private EvalCommandService closedGateService() {
        return new EvalCommandService(commands, reader, new ObjectMapper(),
                EvalLaunchGate.closed(java.util.Set.of("L"), "eval-ds-1", 1, 10));
    }

    @Test
    @DisplayName("PAGE-03 入队前拒绝：E/B 模式、覆盖项、非空限额全部 400 族异常且零命令落库")
    void closedGateRejectsUnsupportedLaunchesBeforeInsert() {
        EvalCommandService gated = closedGateService();

        record Case(String name, EvalLaunchPlan plan, String code) {}
        java.util.List<Case> cases = List.of(
                new Case("mode E", new EvalLaunchPlan("n", "E", "eval-ds-1", null, null,
                        null, null, null, null), "MODE_NOT_SUPPORTED"),
                new Case("mode B", new EvalLaunchPlan("n", "B", "eval-ds-1", null, null,
                        null, null, null, null), "MODE_NOT_SUPPORTED"),
                new Case("model 覆盖", new EvalLaunchPlan("n", "L", "eval-ds-1",
                        "qwen-x", null, null, null, null, null), "MODEL_OVERRIDE_NOT_SUPPORTED"),
                new Case("prompt 覆盖", new EvalLaunchPlan("n", "L", "eval-ds-1", null,
                        "p9", null, null, null, null), "PROMPT_OVERRIDE_NOT_SUPPORTED"),
                new Case("动态数据集", new EvalLaunchPlan("n", "L", "eval-ds-99", null,
                        null, null, null, null, null), "DATASET_VERSION_NOT_SUPPORTED"),
                new Case("预算", new EvalLaunchPlan("n", "L", "eval-ds-1", null, null,
                        10000L, null, null, null), "BUDGET_NOT_SUPPORTED"),
                new Case("截止", new EvalLaunchPlan("n", "L", "eval-ds-1", null, null,
                        null, null, 3600L, null), "DEADLINE_NOT_SUPPORTED"),
                new Case("并发 2", new EvalLaunchPlan("n", "L", "eval-ds-1", null, null,
                        null, 2, null, null), "CONCURRENCY_NOT_SUPPORTED"),
                new Case("轮次超限", new EvalLaunchPlan("n", "L", "eval-ds-1", null, null,
                        null, null, null, 11), "ROUNDS_OUT_OF_RANGE"));

        for (Case c : cases) {
            assertThatThrownBy(() -> gated.launch(c.plan, "key-" + c.code(), "operator"))
                    .as(c.name())
                    .isInstanceOf(EvalLaunchGate.EvalLaunchUnsupportedException.class)
                    .satisfies(e -> assertThat(
                            ((EvalLaunchGate.EvalLaunchUnsupportedException) e).code())
                            .isEqualTo(c.code()));
        }
        assertThat(commands.inserted).as("能力拒绝零命令落库").isEmpty();
    }

    @Test
    @DisplayName("PAGE-03 闭面放行：L + 部署版本 + 缺省覆盖/限额 + 轮次上限内 → 正常受理")
    void closedGateAcceptsSupportedLaunch() {
        EvalCommandService gated = closedGateService();

        EvalCommandService.LaunchResult result = gated.launch(
                new EvalLaunchPlan("n", "L", "eval-ds-1", null, null, null, null, null, 10),
                "key-ok", "operator");

        assertThat(result.status()).isEqualTo(EvalCommandService.LaunchStatus.ACCEPTED);
        assertThat(commands.inserted).hasSize(1);
    }

    @Test
    @DisplayName("SAFE-02 发起面关闭：launchDisabled 闸门下支持面内的 L 也被拒（LAUNCH_DISABLED），"
            + "零命令落库——服务/worker 两端同源拒绝")
    void launchDisabledGateRejectsEvenSupportedPlan() {
        EvalCommandService gated = new EvalCommandService(commands, reader, new ObjectMapper(),
                EvalLaunchGate.launchDisabled(java.util.Set.of("L"), "eval-ds-1", 1, 10));

        assertThatThrownBy(() -> gated.launch(
                new EvalLaunchPlan("n", "L", "eval-ds-1", null, null, null, null, null, null),
                "key-disabled", "operator"))
                .isInstanceOfSatisfying(EvalLaunchGate.EvalLaunchUnsupportedException.class,
                        e -> assertThat(e.code()).isEqualTo("LAUNCH_DISABLED"));
        assertThat(commands.inserted).isEmpty();
    }

    @Test
    @DisplayName("PAGE-03 异常携带支持范围（与 /launch-capability 同源），供 400 应答透出")
    void unsupportedExceptionCarriesSupportedDescribe() {
        EvalCommandService gated = closedGateService();

        assertThatThrownBy(() -> gated.launch(
                new EvalLaunchPlan("n", "E", "eval-ds-1", null, null, null, null, null, null),
                "key-e", "operator"))
                .isInstanceOfSatisfying(EvalLaunchGate.EvalLaunchUnsupportedException.class,
                        e -> {
                            assertThat(e.supported())
                                    .containsEntry("modes", List.of("L"))
                                    .containsEntry("maxConcurrency", 1)
                                    .containsEntry("budgetMaxTokens", false);
                        });
    }

    // ------------------------------------------------------------------ PAGE-10 受理投影

    @Test
    @DisplayName("PAGE-10 acceptedLaunch：run 未落库窗口按预定 runId 读到命令，计划字段取自 payload")
    void acceptedLaunchProjectsCommandAndWaitPlan() {
        EvalLaunchPlan plan = new EvalLaunchPlan("排队中的实验", "L", "eval-ds-1",
                null, null, null, null, null, 2);
        EvalCommandService.LaunchResult launch = service.launch(plan, "key-p10", "operator");

        EvalCommandService.AcceptedLaunch accepted =
                service.acceptedLaunch(launch.runId()).orElseThrow();

        assertThat(accepted.commandId()).isEqualTo(launch.commandId());
        assertThat(accepted.commandState()).isEqualTo("PENDING");
        assertThat(accepted.displayName()).isEqualTo("排队中的实验");
        assertThat(accepted.mode()).isEqualTo("L");
        assertThat(accepted.datasetVersion()).isEqualTo("eval-ds-1");
    }

    @Test
    @DisplayName("PAGE-10 未知 runId 前探 → empty（404 面，不做无限等待）")
    void acceptedLaunchUnknownRunIsEmpty() {
        assertThat(service.acceptedLaunch(UUID.randomUUID())).isEmpty();
    }
}
