package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.DatasetRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCasePage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCaseRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalRunPage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalRunRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.KeysetCursor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EvalQueryService 单测（UI-5；IncidentQueryServiceTest 同模式——假端口纯函数段）：
 * runs/cases 游标编解码与校验、state/verdict 枚举校验、detail caseCount 装配、
 * root cause/failureSample jsonb 摘要化、诚实 null 穿透。
 */
class EvalQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T10:30:00Z");

    private final FakeReader reader = new FakeReader();
    private final EvalQueryService service = new EvalQueryService(reader, new ObjectMapper());

    // ------------------------------------------------------------------ runs 列表 / 游标

    @Test
    void listRunsEncodesKeysetCursorFromLastRowOnlyWhenHasMore() {
        Instant started = Instant.parse("2026-09-09T09:00:00Z");
        UUID id = UUID.randomUUID();
        reader.runPage = new EvalRunPage(
                List.of(runRow(UUID.randomUUID(), started.plusSeconds(60), "SUCCEEDED"),
                        runRow(id, started, "SUCCEEDED")), true);

        EvalQueryService.EvalRunListResponse out = service.listRuns("SUCCEEDED", null, 50);

        assertThat(out.nextCursor()).isEqualTo(started + "/" + id);
        assertThat(reader.lastState).isEqualTo("SUCCEEDED");
        assertThat(reader.lastRunCursor).isNull();
        assertThat(reader.lastRunLimit).isEqualTo(50);
    }

    @Test
    void listRunsWithoutMorePagesEmitsNullCursor() {
        reader.runPage = new EvalRunPage(List.of(runRow(UUID.randomUUID(), NOW, "RUNNING")), false);
        assertThat(service.listRuns(null, null, 50).nextCursor()).isNull();
    }

    @Test
    void listRunsParsesIncomingCursorIntoStructuredKeyset() {
        Instant at = Instant.parse("2026-09-08T01:02:03Z");
        UUID id = UUID.randomUUID();
        reader.runPage = new EvalRunPage(List.of(), false);

        service.listRuns(null, at + "/" + id, 50);

        assertThat(reader.lastRunCursor).isEqualTo(new KeysetCursor(at, id));
    }

    @Test
    void malformedCursorAndBadStateAreRejected() {
        assertThatThrownBy(() -> service.listRuns(null, "garbage", 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listRuns(null,
                "2026-09-08T01:02:03Z/not-a-uuid", 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listRuns("running", null, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state");
    }

    @Test
    void runningRunKeepsHonestNullMetrics() {
        reader.runPage = new EvalRunPage(List.of(runRow(UUID.randomUUID(), NOW, "RUNNING")), false);
        EvalRunRow row = service.listRuns(null, null, 50).items().get(0);
        assertThat(row.coverage()).isNull();
        assertThat(row.tp()).isNull();
        assertThat(row.finishedAt()).isNull();
    }

    // ------------------------------------------------------------------ detail

    @Test
    void detailAssemblesRowWithCaseCount() {
        UUID id = UUID.randomUUID();
        reader.run = runRow(id, NOW, "SUCCEEDED");
        reader.caseCount = 12;

        EvalQueryService.EvalRunDetailResponse out = service.detail(id).orElseThrow();

        assertThat(out.runId()).isEqualTo(id);
        assertThat(out.caseCount()).isEqualTo(12);
        assertThat(out.datasetVersion()).isEqualTo("rca100-v1");
    }

    @Test
    void detailOfUnknownRunIsEmpty() {
        reader.run = null;
        assertThat(service.detail(UUID.randomUUID())).isEmpty();
    }

    // ------------------------------------------------------------------ cases

    @Test
    void listCasesSummarizesRootCauseAndEncodesCursor() {
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        reader.casePage = new EvalCasePage(List.of(
                new EvalCaseRow("s1", 1, "DECIDABLE", true,
                        "{\"component\":\"redis\",\"fault_type\":\"OOM\",\"reason_code\":\"eviction\"}",
                        "{\"component\":\"redis\",\"fault_type\":\"OOM\",\"reason_code\":\"eviction\"}",
                        4200L, null),
                new EvalCaseRow("s2", 1, "TIMEOUT_OR_ABSENT", false,
                        "{\"component\":\"db\",\"fault_type\":\"lock\",\"reason_code\":\"deadlock\"}",
                        null, null, "\"NO_MATCH: report absent\"")), true);

        EvalQueryService.EvalCaseListResponse out =
                service.listCases(runId, null, null, 50).orElseThrow();

        assertThat(out.items()).hasSize(2);
        EvalQueryService.EvalCaseItem first = out.items().get(0);
        assertThat(first.expectedRootCause()).isEqualTo("redis/OOM/eviction");
        assertThat(first.actualRootCause()).isEqualTo("redis/OOM/eviction");
        assertThat(first.latencyMs()).isEqualTo(4200L);
        assertThat(first.failureSample()).isNull();
        EvalQueryService.EvalCaseItem second = out.items().get(1);
        assertThat(second.actualRootCause()).isNull();           // 无报告 → 诚实 null
        assertThat(second.failureSample()).isEqualTo("NO_MATCH: report absent"); // 文本标量解引
        assertThat(out.nextCursor()).isEqualTo("s2/1");
    }

    @Test
    void listCasesValidatesVerdictAndParsesCursorWithSlashInScenarioId() {
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        reader.casePage = new EvalCasePage(List.of(), false);

        service.listCases(runId, "DECIDABLE", "infra/redis-oom/2", 50);

        assertThat(reader.lastVerdict).isEqualTo("DECIDABLE");
        assertThat(reader.lastAfterScenario).isEqualTo("infra/redis-oom");
        assertThat(reader.lastAfterRound).isEqualTo(2);

        assertThatThrownBy(() -> service.listCases(runId, "decidable", null, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verdict");
        assertThatThrownBy(() -> service.listCases(runId, null, "no-round/", 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listCases(runId, null, "s1/abc", 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listCasesOfUnknownRunIsEmpty() {
        reader.run = null;
        assertThat(service.listCases(UUID.randomUUID(), null, null, 50)).isEmpty();
    }

    // ------------------------------------------------------------------ datasets

    @Test
    void datasetsPassThroughReaderRows() {
        reader.datasets = List.of(new DatasetRow("v1.1", "rca100", 30,
                List.of("redis-oom", "db-lock"), NOW));
        EvalQueryService.DatasetListResponse out = service.datasets();
        assertThat(out.items()).hasSize(1);
        assertThat(out.items().get(0).caseCount()).isEqualTo(30);
        assertThat(out.items().get(0).families()).containsExactly("redis-oom", "db-lock");
    }

    // ------------------------------------------------------------------ fakes

    private static EvalRunRow runRow(UUID id, Instant startedAt, String state) {
        boolean terminal = !"RUNNING".equals(state);
        return new EvalRunRow(id, "rca100-v1", "a".repeat(64), "gpt-5", "p3",
                "b".repeat(64), state, startedAt, terminal ? startedAt.plusSeconds(600) : null,
                terminal ? 0.9 : null, terminal ? 0.8 : null, terminal ? 0.7 : null,
                terminal ? 0.1 : null, terminal ? 5 : null, terminal ? 1 : null,
                terminal ? 2 : null);
    }

    private static final class FakeReader implements EvalQueryReader {
        EvalRunPage runPage = new EvalRunPage(List.of(), false);
        EvalRunRow run;
        long caseCount;
        EvalCasePage casePage = new EvalCasePage(List.of(), false);
        List<DatasetRow> datasets = List.of();
        String lastState;
        KeysetCursor lastRunCursor;
        int lastRunLimit;
        String lastVerdict;
        String lastAfterScenario;
        Integer lastAfterRound;

        @Override
        public EvalRunPage listRuns(String state, KeysetCursor cursor, int limit) {
            this.lastState = state;
            this.lastRunCursor = cursor;
            this.lastRunLimit = limit;
            return runPage;
        }

        @Override
        public Optional<EvalRunRow> findRun(UUID runId) {
            return Optional.ofNullable(run);
        }

        @Override
        public long countCases(UUID runId) {
            return caseCount;
        }

        @Override
        public EvalCasePage listCases(UUID runId, String verdict, String afterScenario,
                                      Integer afterRound, int limit) {
            this.lastVerdict = verdict;
            this.lastAfterScenario = afterScenario;
            this.lastAfterRound = afterRound;
            return casePage;
        }

        @Override
        public List<DatasetRow> listDatasets() {
            return datasets;
        }
    }
}
