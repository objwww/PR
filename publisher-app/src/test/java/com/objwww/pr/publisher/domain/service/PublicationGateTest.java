package com.objwww.pr.publisher.domain.service;

import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.domain.model.DependencyRow;
import com.objwww.pr.publisher.domain.model.SubjectCursor;
import com.objwww.pr.publisher.fakes.TestFixtures;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.DependencyMode;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.OperationId;
import com.objwww.pr.shared.OutboxState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3-A 第①–④步判定（E1/E2/E3 + F9）：schema 白名单 → 依赖终态 → 跳号 → fence。
 */
class PublicationGateTest {

    private final PublicationGate gate = new PublicationGate();

    private ClaimedCommand checkCommand() {
        return TestFixtures.command(CommandType.CREATE_CHECK, 1, 1, OutboxState.PENDING, 0, 3);
    }

    private SubjectCursor cursorOf(ClaimedCommand command) {
        return new SubjectCursor(1, command.aggregateSequence() - 1);
    }

    // ---------- ① schema/白名单（EX-09） ----------

    @Test
    void validPayloadProceeds() {
        ClaimedCommand cmd = checkCommand();
        T3ADecision decision = gate.evaluate(cmd, TestFixtures.checkPayload(cmd), List.of(), cursorOf(cmd));
        assertEquals(T3ADecision.Action.PROCEED, decision.action());
    }

    @Test
    void nonWhitelistedCheckNameRejected() {
        ClaimedCommand cmd = checkCommand();
        Map<String, Object> payload = TestFixtures.checkPayload(cmd);
        payload.put("name", "arbitrary-check"); // 非白名单 → FAILED_TERMINAL + SAFETY_REJECTED
        T3ADecision decision = gate.evaluate(cmd, payload, List.of(), cursorOf(cmd));
        assertEquals(T3ADecision.Action.MARK_FAILED_TERMINAL, decision.action());
        assertEquals(ExecutionEventType.SAFETY_REJECTED, decision.eventType());
        assertEquals("SCHEMA_REJECTED", decision.errorCode());
    }

    @Test
    void operationIdMismatchRejected() {
        ClaimedCommand cmd = checkCommand();
        Map<String, Object> payload = TestFixtures.checkPayload(cmd);
        payload.put("operation_id", OperationId.random().toString()); // 探针与主键不同源
        T3ADecision decision = gate.evaluate(cmd, payload, List.of(), cursorOf(cmd));
        assertEquals(T3ADecision.Action.MARK_FAILED_TERMINAL, decision.action());
    }

    @Test
    void reviewMarkerMismatchRejected() {
        ClaimedCommand cmd = TestFixtures.command(CommandType.PUBLISH_REVIEW, 1, 1,
                OutboxState.PENDING, 0, 3);
        Map<String, Object> payload = TestFixtures.reviewPayload(cmd);
        payload.put("marker", "<!-- ai-review:forged -->");
        T3ADecision decision = gate.evaluate(cmd, payload, List.of(), cursorOf(cmd));
        assertEquals(T3ADecision.Action.MARK_FAILED_TERMINAL, decision.action());
    }

    @Test
    void missingRequiredFieldRejected() {
        ClaimedCommand cmd = checkCommand();
        Map<String, Object> payload = TestFixtures.checkPayload(cmd);
        payload.remove("head_sha");
        T3ADecision decision = gate.evaluate(cmd, payload, List.of(), cursorOf(cmd));
        assertEquals(T3ADecision.Action.MARK_FAILED_TERMINAL, decision.action());
    }

    // ---------- ② 依赖终态（E3 归类表经 gate 的落点） ----------

    @Test
    void dependencyConfirmedProceeds() {
        ClaimedCommand cmd = checkCommand();
        List<DependencyRow> deps = List.of(new DependencyRow(OperationId.random(),
                OutboxState.CONFIRMED, DependencyMode.REQUIRE_CONFIRMED));
        assertEquals(T3ADecision.Action.PROCEED,
                gate.evaluate(cmd, TestFixtures.checkPayload(cmd), deps, cursorOf(cmd)).action());
    }

    @Test
    void dependencySupersededCascades() {
        ClaimedCommand cmd = checkCommand();
        List<DependencyRow> deps = List.of(new DependencyRow(OperationId.random(),
                OutboxState.SUPERSEDED, DependencyMode.REQUIRE_CONFIRMED));
        T3ADecision decision = gate.evaluate(cmd, TestFixtures.checkPayload(cmd), deps, cursorOf(cmd));
        assertEquals(T3ADecision.Action.MARK_SUPERSEDED, decision.action());
        assertEquals("DEPENDENCY_SUPERSEDED", decision.errorCode());
    }

    @Test
    void dependencySupersededOptionalProceeds() {
        ClaimedCommand cmd = checkCommand();
        List<DependencyRow> deps = List.of(new DependencyRow(OperationId.random(),
                OutboxState.SUPERSEDED, DependencyMode.OPTIONAL));
        assertEquals(T3ADecision.Action.PROCEED,
                gate.evaluate(cmd, TestFixtures.checkPayload(cmd), deps, cursorOf(cmd)).action());
    }

    @Test
    void dependencyFailedTerminalSupersedesSelf() {
        ClaimedCommand cmd = checkCommand();
        List<DependencyRow> deps = List.of(new DependencyRow(OperationId.random(),
                OutboxState.FAILED_TERMINAL, DependencyMode.REQUIRE_CONFIRMED));
        assertEquals(T3ADecision.Action.MARK_SUPERSEDED,
                gate.evaluate(cmd, TestFixtures.checkPayload(cmd), deps, cursorOf(cmd)).action());
    }

    @Test
    void dependencyManualDefers() {
        ClaimedCommand cmd = checkCommand();
        List<DependencyRow> deps = List.of(new DependencyRow(OperationId.random(),
                OutboxState.MANUAL, DependencyMode.OPTIONAL));
        assertEquals(T3ADecision.Action.DEFER,
                gate.evaluate(cmd, TestFixtures.checkPayload(cmd), deps, cursorOf(cmd)).action());
    }

    @Test
    void dependencyNotTerminalDefers() {
        ClaimedCommand cmd = checkCommand();
        List<DependencyRow> deps = List.of(new DependencyRow(OperationId.random(),
                OutboxState.IN_FLIGHT, DependencyMode.REQUIRE_CONFIRMED));
        assertEquals(T3ADecision.Action.DEFER,
                gate.evaluate(cmd, TestFixtures.checkPayload(cmd), deps, cursorOf(cmd)).action());
    }

    // ---------- ③ 跳号检测（E2） ----------

    @Test
    void sequenceGapRecordsEventAndDefers() {
        ClaimedCommand cmd = TestFixtures.command(CommandType.CREATE_CHECK, 5, 1,
                OutboxState.PENDING, 0, 3);
        // 游标停在 2：期望 3 实到 5 → 跳号，不执行
        T3ADecision decision = gate.evaluate(cmd, TestFixtures.checkPayload(cmd), List.of(),
                new SubjectCursor(1, 2));
        assertEquals(T3ADecision.Action.RECORD_GAP, decision.action());
        assertEquals(ExecutionEventType.SEQUENCE_GAP_DETECTED, decision.eventType());
        assertEquals(3L, decision.eventPayload().get("expected_sequence"));
    }

    // ---------- ④ epoch fence（F9） ----------

    @Test
    void staleEpochSupersedes() {
        ClaimedCommand cmd = checkCommand(); // epoch=1
        T3ADecision decision = gate.evaluate(cmd, TestFixtures.checkPayload(cmd), List.of(),
                new SubjectCursor(2, 0)); // subject 已换届
        assertEquals(T3ADecision.Action.MARK_SUPERSEDED, decision.action());
        assertEquals("STALE_EPOCH", decision.errorCode());
    }

    @Test
    void aheadEpochDefers() {
        ClaimedCommand cmd = TestFixtures.command(CommandType.CREATE_CHECK, 1, 3,
                OutboxState.PENDING, 0, 3);
        // 命令 epoch 超前 = 读取陈旧，可重试不 fence 误杀（EX-05）
        T3ADecision decision = gate.evaluate(cmd, TestFixtures.checkPayload(cmd), List.of(),
                new SubjectCursor(2, 0));
        assertEquals(T3ADecision.Action.DEFER, decision.action());
    }

    @Test
    void ownedGenerationBypassesEpochFence() {
        ClaimedCommand cmd = new ClaimedCommand(OperationId.random(), TestFixtures.SUBJECT_ID,
                TestFixtures.RUN_ID, TestFixtures.REVISION_ID, "pr:1#1", 1, 1,
                com.objwww.pr.shared.FenceMode.OWNED_GENERATION, CommandType.CREATE_CHECK,
                OutboxState.PENDING, "m0-policy-v1", null, TestFixtures.PAYLOAD_HASH,
                com.objwww.pr.shared.RemoteIdentityType.EXTERNAL_ID, 1L, 0, 3, 0);
        T3ADecision decision = gate.evaluate(cmd, TestFixtures.checkPayload(cmd), List.of(),
                new SubjectCursor(5, 0));
        assertEquals(T3ADecision.Action.PROCEED, decision.action());
    }

    @Test
    void schemaCheckedBeforeDependencyAndFence() {
        // schema 不合法时即使 fence 也该落 FAILED_TERMINAL（fail-closed 优先）
        ClaimedCommand cmd = checkCommand();
        Map<String, Object> payload = TestFixtures.checkPayload(cmd);
        payload.put("name", "evil");
        T3ADecision decision = gate.evaluate(cmd, payload, List.of(), new SubjectCursor(9, 0));
        assertEquals(T3ADecision.Action.MARK_FAILED_TERMINAL, decision.action());
        assertTrue(decision.eventPayload().get("violations").toString().contains("白名单"));
    }
}
