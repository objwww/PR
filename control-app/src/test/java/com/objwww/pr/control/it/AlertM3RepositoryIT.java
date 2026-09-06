package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.ExecutionStatus;
import com.objwww.pr.control.alert.domain.model.InvestigationResult;
import com.objwww.pr.control.alert.domain.model.NotifyOutboxEntry;
import com.objwww.pr.control.alert.domain.model.ReportPublication;
import com.objwww.pr.control.alert.domain.model.RcaToolCall;
import com.objwww.pr.control.alert.domain.model.ToolCallStatus;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.infrastructure.persistence.PostgresInvestigationResultRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresNotifyOutboxRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReportPublicationRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaToolCallRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3-04/07 仓储行为 IT（真 PG）：generation 栅栏（FUT-50 晚到旧代写入 0 行拒绝）、
 * STARTED→终态 CAS（二次收尾拒绝）、attempt_id 幂等锚（崩溃重放返回现行）、
 * tool_call 批量栅栏整批拒绝、publication/outbox 唯一键防重。
 * 约束面（CHECK/FK/授权）在 {@link AlertV9MigrationContractIT}。
 */
class AlertM3RepositoryIT extends PostgresITBase {

    private record Seed(UUID incidentId, UUID runId, UUID taskId, UUID attemptId, int generation) {
    }

    private PostgresInvestigationResultRepository investigations;
    private PostgresRcaToolCallRepository toolCalls;
    private PostgresReportPublicationRepository publications;
    private PostgresNotifyOutboxRepository outboxes;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUpRepositories() {
        investigations = new PostgresInvestigationResultRepository(controlJdbc);
        toolCalls = new PostgresRcaToolCallRepository(controlJdbc);
        publications = new PostgresReportPublicationRepository(controlJdbc);
        outboxes = new PostgresNotifyOutboxRepository(controlJdbc);
    }

    /** incident → QUEUED run（可指定 generation）→ READY task → STARTED attempt */
    private Seed seedChain(int generation) {
        Seed seed = new Seed(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), generation);
        controlJdbc.sql("""
                INSERT INTO incident(id, incident_key, status, generation, episode_started_at,
                    first_seen_at, last_event_at, created_at, updated_at)
                VALUES (:id, :key, 'FIRING', :gen, now(), now(), now(), now(), now())
                """).param("id", seed.incidentId())
                .param("key", "alertname=HighErrorRate|service=it-" + seed.incidentId())
                .param("gen", generation).update();
        controlJdbc.sql("""
                INSERT INTO rca_run(id, incident_id, generation, trigger_kind, state,
                    investigation_hash, created_at, updated_at)
                VALUES (:id, :inc, :gen, 'INITIAL', 'QUEUED', :hash, now(), now())
                """).param("id", seed.runId()).param("inc", seed.incidentId())
                .param("gen", generation)
                .param("hash", Digest.sha256Of("it-" + seed.runId()).value()).update();
        controlJdbc.sql("""
                INSERT INTO rca_task(id, run_id, task_key, state, priority,
                    available_at, ready_since, deadline_at, created_at, updated_at)
                VALUES (:id, :run, 'HOLMES_INVESTIGATE', 'READY', 100,
                    now(), now(), now(), now(), now())
                """).param("id", seed.taskId()).param("run", seed.runId()).update();
        controlJdbc.sql("""
                INSERT INTO rca_attempt(id, task_id, attempt_no, lease_epoch, worker_id, status, started_at)
                VALUES (:id, :task, 1, 0, 'it-worker', 'STARTED', now())
                """).param("id", seed.attemptId()).param("task", seed.taskId()).update();
        return seed;
    }

    private InvestigationResult startedRow(Seed seed, UUID resultId, int observedGeneration) {
        return InvestigationResult.started(resultId, seed.attemptId(), seed.runId(),
                observedGeneration, 2, "deepseek-v3", Instant.now());
    }

    /** rca_report 真实行（report_publication/notify_outbox 的 FK 面） */
    private UUID seedReport(Seed seed) {
        UUID reportId = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO rca_report(id, run_id, attempt_id, schema_version, validation_status,
                    package_json, raw_text, usage_missing, created_at)
                VALUES (:id, :run, :attempt, 2, 'STRUCTURE_VALIDATED',
                        CAST('{"schema_version":2}' AS jsonb), 'raw', true, now())
                """).param("id", reportId).param("run", seed.runId())
                .param("attempt", seed.attemptId()).update();
        return reportId;
    }

    // ------------------------------------------------------------------ InvestigationResult

    @Test
    @DisplayName("STARTED 先行：与 run.generation 一致可写；晚到旧代（栅栏不匹配）抛出且零行")
    void startedInsertHonorsGenerationFence() {
        Seed seed = seedChain(3);

        investigations.insertStartedIfAbsent(startedRow(seed, UUID.randomUUID(), 3));
        assertThat(count("rca_investigation_result")).isEqualTo(1);

        // 旧代 executor 晚到：observed_generation=2 ≠ run.generation=3 → 0 行拒绝
        assertThatThrownBy(() -> investigations.insertStartedIfAbsent(
                startedRow(seed, UUID.randomUUID(), 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("栅栏");
        assertThat(count("rca_investigation_result")).isEqualTo(1);
    }

    @Test
    @DisplayName("attempt_id 幂等锚：崩溃重放同 attempt 再铸返回现行行，不覆盖")
    void duplicateAttemptReturnsCurrentRow() {
        Seed seed = seedChain(0);
        UUID resultId = UUID.randomUUID();
        InvestigationResult first = investigations.insertStartedIfAbsent(
                startedRow(seed, resultId, 0));

        InvestigationResult replay = investigations.insertStartedIfAbsent(
                startedRow(seed, UUID.randomUUID(), 0));

        assertThat(replay.id()).isEqualTo(first.id()).isEqualTo(resultId);
        assertThat(count("rca_investigation_result")).isEqualTo(1);
    }

    @Test
    @DisplayName("终态 CAS：STARTED→SUCCEEDED 一次成功（含 package/digest/model 全列）；二次收尾 0 行拒绝")
    void terminalCasWritesOnceThenRejects() {
        Seed seed = seedChain(0);
        UUID resultId = UUID.randomUUID();
        investigations.insertStartedIfAbsent(startedRow(seed, resultId, 0));

        String packageJson = "{\"schema_version\":2,\"summary\":\"s\"}";
        InvestigationResult terminal = investigations.findByAttemptId(seed.attemptId()).orElseThrow()
                .withTerminal(ExecutionStatus.SUCCEEDED, ValidationStatus.STRUCTURE_VALIDATED,
                        List.of(), packageJson, "ab/cdef", Digest.sha256Of("raw"),
                        Digest.sha256Of(packageJson),
                        "{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}",
                        Instant.now());
        assertThat(investigations.finishTerminal(terminal)).isTrue();

        var stored = investigations.findByAttemptId(seed.attemptId()).orElseThrow();
        assertThat(stored.executionStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(stored.validationStatus()).isEqualTo(ValidationStatus.STRUCTURE_VALIDATED);
        // jsonb 落库会规范化（键序重排 + 冒号后带空格），断言按规范化形态
        assertThat(stored.packageJson()).contains("\"schema_version\": 2");
        assertThat(stored.rawArtifactRef()).isEqualTo("ab/cdef");
        assertThat(stored.rawDigest()).isEqualTo(Digest.sha256Of("raw"));
        assertThat(stored.payloadDigest()).isEqualTo(Digest.sha256Of(packageJson));
        assertThat(stored.usageJson()).contains("prompt_tokens");
        assertThat(stored.model()).isEqualTo("deepseek-v3");
        assertThat(stored.finishedAt()).isNotNull();

        // CAS：终态行不再接受第二次收尾（晚到 worker 提交被拒）
        assertThat(investigations.finishTerminal(terminal)).isFalse();
    }

    @Test
    @DisplayName("终态栅栏：run.generation 已前进时旧代终态回写 0 行拒绝")
    void terminalFinishHonorsGenerationFence() {
        Seed seed = seedChain(0);
        UUID resultId = UUID.randomUUID();
        investigations.insertStartedIfAbsent(startedRow(seed, resultId, 0));

        // 模拟晚到场景：run 行代数已变化（测试动作用，生产中 generation 不可变）
        controlJdbc.sql("UPDATE rca_run SET generation = 7 WHERE id = :id")
                .param("id", seed.runId()).update();

        InvestigationResult terminal = investigations.findByAttemptId(seed.attemptId())
                .orElseThrow()
                .withTerminal(ExecutionStatus.FAILED, ValidationStatus.NOT_VALIDATED,
                        null, null, null, null, null, null, Instant.now());
        assertThat(investigations.finishTerminal(terminal)).isFalse();

        var stored = investigations.findByAttemptId(seed.attemptId()).orElseThrow();
        assertThat(stored.executionStatus()).isEqualTo(ExecutionStatus.STARTED);
    }

    @Test
    @DisplayName("悬挂扫描：STARTED 且 created_at 早于阈值被找到；终态行不出现")
    void hangingScanFindsOnlyStartedRows() {
        Seed seed = seedChain(0);
        investigations.insertStartedIfAbsent(startedRow(seed, UUID.randomUUID(), 0));

        assertThat(investigations.findHangingStarted(Instant.now().plusSeconds(60)))
                .hasSize(1);

        var terminal = investigations.findByAttemptId(seed.attemptId()).orElseThrow()
                .withTerminal(ExecutionStatus.UNKNOWN, ValidationStatus.NOT_VALIDATED,
                        null, null, null, null, null, null, Instant.now());
        investigations.finishTerminal(terminal);
        assertThat(investigations.findHangingStarted(Instant.now().plusSeconds(60)))
                .isEmpty();
    }

    @Test
    @DisplayName("run 维度查询：findByRunId 只取本 run 的调查记录")
    void findByRunIdScopesCorrectly() {
        Seed seedA = seedChain(0);
        Seed seedB = seedChain(0);
        investigations.insertStartedIfAbsent(startedRow(seedA, UUID.randomUUID(), 0));
        investigations.insertStartedIfAbsent(startedRow(seedB, UUID.randomUUID(), 0));

        assertThat(investigations.findByRunId(seedA.runId())).hasSize(1);
        assertThat(investigations.findByRunId(seedB.runId())).hasSize(1);
    }

    // ------------------------------------------------------------------ RcaToolCall

    @Test
    @DisplayName("tool_call 批量：栅栏内整批写入；跨 generation 整批 0 行；重复 PK 幂等跳过")
    void toolCallBatchFenceAndIdempotency() {
        Seed seed = seedChain(2);
        UUID resultId = UUID.randomUUID();
        investigations.insertStartedIfAbsent(startedRow(seed, resultId, 2));
        Digest payload = Digest.sha256Of("pkg");

        RcaToolCall call = new RcaToolCall(resultId, "tc-1", 1, "prometheus_query",
                ToolCallStatus.SUCCESS, Digest.sha256Of("p"), Digest.sha256Of("r"),
                null, null, seed.runId(), 2, 2, payload);
        assertThat(toolCalls.insertAll(List.of(call))).isEqualTo(1);
        assertThat(count("rca_tool_call")).isEqualTo(1);

        // 重复 PK 幂等跳过（ON CONFLICT DO NOTHING），不抛不重复
        assertThat(toolCalls.insertAll(List.of(call))).isEqualTo(0);
        assertThat(count("rca_tool_call")).isEqualTo(1);

        // 栅栏：observed_generation ≠ run.generation → 整批拒绝（不落半批）
        RcaToolCall stale = new RcaToolCall(UUID.randomUUID(), "tc-9", 1, "t",
                ToolCallStatus.ERROR, null, null, null, null,
                seed.runId(), 1, 2, payload);
        assertThat(toolCalls.insertAll(List.of(stale))).isEqualTo(0);
        assertThat(count("rca_tool_call")).isEqualTo(1);

        assertThat(toolCalls.findByResultId(resultId)).hasSize(1);
        assertThat(toolCalls.findByRunId(seed.runId())).hasSize(1);
    }

    // ------------------------------------------------------------------ Publication / Outbox

    @Test
    @DisplayName("publication：一报告一记录（unique report_id → DuplicateKeyException），可按报告读回")
    void publicationUniquePerReport() {
        Seed seed = seedChain(0);
        UUID reportId = seedReport(seed);
        publications.insert(ReportPublication.ready(UUID.randomUUID(), reportId, Instant.now()));

        assertThatThrownBy(() -> publications.insert(
                ReportPublication.ready(UUID.randomUUID(), reportId, Instant.now())))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(publications.findByReportId(reportId)).isPresent();
        assertThat(publications.findByReportId(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("outbox：(report, channel, template) 唯一防重；publication 维度读回一对多")
    void outboxDeliveryKeyDeduplicates() {
        Seed seed = seedChain(0);
        UUID reportId = seedReport(seed);
        UUID publicationId = UUID.randomUUID();
        publications.insert(ReportPublication.ready(publicationId, reportId, Instant.now()));
        UUID operationId = UUID.randomUUID();
        String payload = mapper.createObjectNode().put("candidate", true).toString();

        outboxes.insert(NotifyOutboxEntry.pending(UUID.randomUUID(), publicationId, reportId,
                "test", "am3-candidate-v1", operationId, payload, Instant.now()));
        // 同键重复出生（重放/双写）被唯一键拒绝
        assertThatThrownBy(() -> outboxes.insert(NotifyOutboxEntry.pending(
                UUID.randomUUID(), publicationId, reportId, "test", "am3-candidate-v1",
                UUID.randomUUID(), payload, Instant.now())))
                .isInstanceOf(DuplicateKeyException.class);
        // 不同渠道可并存
        outboxes.insert(NotifyOutboxEntry.pending(UUID.randomUUID(), publicationId, reportId,
                "wecom", "am3-candidate-v1", operationId, payload, Instant.now()));

        assertThat(outboxes.findByPublicationId(publicationId)).hasSize(2);
    }
}
