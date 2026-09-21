package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.model.EvalComparisonRecord;
import com.objwww.pr.control.eval.domain.repository.EvalComparisonRepository;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseEvidenceRefRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseIdentityRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseLogEvidenceRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseTokenRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CompareCaseRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CompareRunMeta;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.DatasetRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvidenceMetaRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCaseDetailRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCasePage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalRunPage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalRunRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.KeysetCursor;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.PartitionCountRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * EvalComparisonAutoRecorder 单测（EV-07 终态自动落档；假端口沿 EvalCompareServiceTest
 * 模式——compare 计算走真 EvalCompareService，落档面 FakeComparisons 捕获）：
 * 首跑无 baseline 诚实不落档、二跑自动落档（含 INCONCLUSIVE 前置分支照实落档）、
 * 重复终态幂等不重复落档、落档失败不拖垮终态。
 */
class EvalComparisonAutoRecorderTest {

    private static final UUID BASELINE = UUID.randomUUID();
    private static final UUID CANDIDATE = UUID.randomUUID();
    private static final String ACTOR = "auto-terminal:worker-test";

    private final FakeReader reader = new FakeReader();
    private final FakeComparisons comparisons = new FakeComparisons();
    private final EvalComparisonAutoRecorder recorder = new EvalComparisonAutoRecorder(
            reader, comparisons,
            new EvalCompareService(reader, comparisons, new ObjectMapper()), ACTOR);

    private static CompareRunMeta meta(UUID runId, String state) {
        return new CompareRunMeta(runId, "ds-v1", "r".repeat(64), "a".repeat(64), 3,
                "driver-v1", "gpt-5", "p1", "c".repeat(64), state,
                "{\"displayName\":\"t\",\"mode\":\"L\",\"datasetVersion\":\"ds-v1\","
                        + "\"roundsPerScenario\":2}");
    }

    /** 5 簇 × 2 轮全命中（持平）→ 门 PASS（EvalCompareServiceTest 同形摆数） */
    private void setupFiveFlatClusters(UUID baseline, UUID candidate) {
        List<CompareCaseRow> b = new ArrayList<>();
        List<CompareCaseRow> c = new ArrayList<>();
        List<String> planKeys = new ArrayList<>();
        for (int f = 0; f < 5; f++) {
            planKeys.add("fam" + f + "-s");
            for (int r = 1; r <= 2; r++) {
                b.add(caseRow("fam" + f + "-s", r, "fam" + f));
                c.add(caseRow("fam" + f + "-s", r, "fam" + f));
            }
        }
        reader.casesByRun.put(baseline, b);
        reader.casesByRun.put(candidate, c);
        reader.planKeysByDataset.put("ds-v1", List.copyOf(planKeys));
    }

    private static CompareCaseRow caseRow(String scenarioId, int roundNo, String family) {
        return new CompareCaseRow(UUID.randomUUID(), scenarioId, roundNo, "DECIDABLE", true,
                "{\"component\":\"redis\",\"fault_type\":\"oom\",\"reason_code\":\"x\"}",
                "policy-v1", "digest-" + scenarioId, family, null, null);
    }

    @Test
    @DisplayName("首跑：无同数据集/panel 前序终态 run → 诚实不落档")
    void firstRunWithoutBaselineRecordsNothing() {
        reader.baseline = Optional.empty();

        recorder.onTerminal(CANDIDATE);

        assertThat(comparisons.inserted).isEmpty();
    }

    @Test
    @DisplayName("二跑：存在前序终态 baseline → 自动落档（baseline=上一终态 run，actor=worker 身份）")
    void secondRunRecordsAgainstPreviousTerminalRun() {
        reader.metaById.put(BASELINE, meta(BASELINE, "SUCCEEDED"));
        reader.metaById.put(CANDIDATE, meta(CANDIDATE, "SUCCEEDED"));
        reader.baseline = Optional.of(BASELINE);
        setupFiveFlatClusters(BASELINE, CANDIDATE);

        recorder.onTerminal(CANDIDATE);

        assertThat(comparisons.inserted).hasSize(1);
        EvalComparisonRecord record = comparisons.inserted.get(0);
        assertThat(record.baselineRunId()).isEqualTo(BASELINE);
        assertThat(record.candidateRunId()).isEqualTo(CANDIDATE);
        assertThat(record.actor()).isEqualTo(ACTOR);
        assertThat(record.gateOutcome()).isEqualTo("PASS");
    }

    @Test
    @DisplayName("前置分支照实落档：候选 FAILED 终态 → 就绪度不足 INCONCLUSIVE 也是合法产出")
    void inconclusiveGateIsRecordedHonestly() {
        reader.metaById.put(BASELINE, meta(BASELINE, "SUCCEEDED"));
        reader.metaById.put(CANDIDATE, meta(CANDIDATE, "FAILED"));
        reader.baseline = Optional.of(BASELINE);
        setupFiveFlatClusters(BASELINE, CANDIDATE);

        recorder.onTerminal(CANDIDATE);

        assertThat(comparisons.inserted).hasSize(1);
        assertThat(comparisons.inserted.get(0).gateOutcome()).isEqualTo("INCONCLUSIVE");
    }

    @Test
    @DisplayName("重复终态幂等：本 run 已有 candidate 落档行 → 不重复落档")
    void repeatTerminalEventDoesNotDuplicate() {
        comparisons.inserted.add(new EvalComparisonRecord(UUID.randomUUID(), BASELINE,
                CANDIDATE, true, "[]", 10, 0, 0, 0, 10, null, null, "PASS", List.of(),
                "eval-compare-gate-v2", ACTOR, Instant.now().minusSeconds(60)));
        reader.baseline = Optional.of(BASELINE);

        recorder.onTerminal(CANDIDATE);

        assertThat(comparisons.inserted).hasSize(1);
    }

    @Test
    @DisplayName("落档失败不拖垮 finalize：baseline 解析抛错 → 异常吞为 WARN，钩子正常返回")
    void recordFailureNeverPropagates() {
        reader.baselineFailure = new IllegalStateException("db down");

        assertThatCode(() -> recorder.onTerminal(CANDIDATE)).doesNotThrowAnyException();
        assertThat(comparisons.inserted).isEmpty();
    }

    // ------------------------------------------------------------------ 假端口

    private static final class FakeReader implements EvalQueryReader {
        final Map<UUID, CompareRunMeta> metaById = new LinkedHashMap<>();
        final Map<UUID, List<CompareCaseRow>> casesByRun = new LinkedHashMap<>();
        final Map<String, List<String>> planKeysByDataset = new LinkedHashMap<>();
        Optional<UUID> baseline = Optional.empty();
        RuntimeException baselineFailure;

        @Override
        public EvalRunPage listRuns(String state, KeysetCursor cursor, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<EvalRunRow> findRun(UUID runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EvalCasePage listCases(UUID runId, String verdict, String afterScenario,
                                      Integer afterRound, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DatasetRow> listDatasets() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PartitionCountRow> listPartitionCounts() {
            throw new UnsupportedOperationException();
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
            return Optional.ofNullable(metaById.get(runId));
        }

        @Override
        public List<CompareCaseRow> listCasesForCompare(UUID runId, int limit) {
            return casesByRun.getOrDefault(runId, List.of());
        }

        @Override
        public List<String> listPlanCaseKeys(String datasetVersion) {
            return planKeysByDataset.getOrDefault(datasetVersion, List.of());
        }

        @Override
        public Optional<UUID> findAutoCompareBaseline(UUID candidateRunId) {
            if (baselineFailure != null) {
                throw baselineFailure;
            }
            return baseline;
        }

        @Override
        public EvalPhaseEventPage listPhaseEvents(UUID runId, KeysetCursor cursor,
                                                  int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UsageCallRow> listUsageCalls(UUID evalRunId) {
            return List.of();
        }

        @Override
        public List<UsageCallRow> listUsageCallsForRuns(Iterable<UUID> evalRunIds) {
            return List.of();
        }

        @Override
        public List<CaseTokenRow> listCaseTokenTotals(UUID evalRunId,
                                                      List<UUID> caseExecutionIds) {
            return List.of();
        }

        @Override
        public List<ScenarioRoundStatRow> listScenarioRoundStatsForRuns(
                Iterable<UUID> evalRunIds) {
            return List.of();
        }

        @Override
        public List<CaseSafetyRow> listCaseSafety(UUID evalRunId) {
            // D03 v3：缺省按案例行派生全 PASS（模拟 P4 落档面完整覆盖，
            // EvalCompareServiceTest FakeReader 同律）
            return casesByRun.getOrDefault(evalRunId, List.of()).stream()
                    .map(c -> new CaseSafetyRow(c.scenarioId(), c.roundNo(),
                            "PASS", "[]", false, c.rootCauseHit()))
                    .toList();
        }

        @Override
        public List<CaseJudgeRow> listJudge(UUID evalRunId) {
            return List.of();
        }
    }

    private static final class FakeComparisons implements EvalComparisonRepository {
        final List<EvalComparisonRecord> inserted = new ArrayList<>();

        @Override
        public void insert(EvalComparisonRecord record) {
            inserted.add(record);
        }

        @Override
        public Optional<EvalComparisonRecord> findLatestByPair(UUID baselineRunId,
                                                               UUID candidateRunId) {
            return inserted.stream()
                    .filter(r -> r.baselineRunId().equals(baselineRunId)
                            && r.candidateRunId().equals(candidateRunId))
                    .reduce((a, b) -> b);
        }

        @Override
        public Optional<EvalComparisonRecord> findLatestByCandidate(UUID candidateRunId) {
            return inserted.stream()
                    .filter(r -> r.candidateRunId().equals(candidateRunId))
                    .reduce((a, b) -> b);
        }
    }
}
