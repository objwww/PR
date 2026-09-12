package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.model.ReviewAssignment;
import com.objwww.pr.control.eval.domain.model.ReviewVerdict;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseIdentityRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCaseDetailRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCaseRow;
import com.objwww.pr.control.eval.domain.repository.ReviewAssignmentRepository;
import com.objwww.pr.control.eval.domain.repository.ReviewVerdictRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EV-08 人工评审服务（假端口真断言）：领取 CAS（双人同领一人赢/同人幂等/已提交拒领/
 * 同人第二份拒领）、租约超时惰性回收（有效状态投影 + 过期可重领 + 重领后旧持有者
 * 提交撞 revision）、提交不可改（重复提交 409、重评分 = 新任务新行双留档）、rubric
 * 版本必带且在注册表、理由必填、HOLDOUT 盲评投影零 GT 字段、分歧检测（最新行参与、
 * 重评分取代旧行）、进度分桶、任务生成 ensure 幂等。
 */
class EvalReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");
    private static final Instant PAST = NOW.minusSeconds(7200);
    private static final String RUBRIC = "eval-review-rubric-v1";
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID CASE_ID = UUID.randomUUID();
    private static final String ALICE = "alice";
    private static final String BOB = "bob";

    /** review_assignment 内存账本（CAS 谓词与 PG 实现同语义；uq 部分唯一索引模拟） */
    private static final class InMemoryAssignments implements ReviewAssignmentRepository {
        final Map<UUID, ReviewAssignment> byId = new HashMap<>();

        @Override
        public void insert(ReviewAssignment assignment) {
            byId.put(assignment.id(), assignment);
        }

        @Override
        public Optional<ReviewAssignment> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public boolean claim(UUID id, String reviewer, Instant claimedAt,
                             Instant leaseExpiresAt, Instant now) {
            ReviewAssignment a = byId.get(id);
            if (a == null || !a.claimable(now)) {
                return false;
            }
            // uq_review_assignment_active_reviewer：同人对同案例已有进行中任务 → 违约
            boolean selfActive = byId.values().stream().anyMatch(x ->
                    x.status() == ReviewAssignment.Status.IN_PROGRESS
                            && reviewer.equals(x.reviewer())
                            && x.caseExecutionId().equals(a.caseExecutionId())
                            && !x.leaseExpired(now));
            if (selfActive) {
                throw new DuplicateKeyException("uq_review_assignment_active_reviewer");
            }
            byId.put(id, new ReviewAssignment(a.id(), a.runId(), a.caseExecutionId(),
                    ReviewAssignment.Status.IN_PROGRESS, reviewer, a.revision() + 1,
                    claimedAt, leaseExpiresAt, null, a.createdBy(), a.createdAt()));
            return true;
        }

        @Override
        public boolean submit(UUID id, String reviewer, int expectedRevision,
                              Instant submittedAt) {
            ReviewAssignment a = byId.get(id);
            if (a == null || a.status() != ReviewAssignment.Status.IN_PROGRESS
                    || !reviewer.equals(a.reviewer()) || a.revision() != expectedRevision) {
                return false;
            }
            byId.put(id, new ReviewAssignment(a.id(), a.runId(), a.caseExecutionId(),
                    ReviewAssignment.Status.SUBMITTED, reviewer, a.revision() + 1,
                    a.claimedAt(), a.leaseExpiresAt(), submittedAt, a.createdBy(),
                    a.createdAt()));
            return true;
        }

        @Override
        public AssignmentPage listByRun(UUID runId, String reviewer,
                                        ReviewAssignment.Status statusFilter, Instant now,
                                        KeysetCursor cursor, int limit) {
            List<ReviewAssignment> rows = byId.values().stream()
                    .filter(a -> a.runId().equals(runId))
                    .filter(a -> reviewer == null || reviewer.equals(a.reviewer()))
                    .filter(a -> statusFilter == null
                            || a.effectiveStatus(now) == statusFilter)
                    .sorted(Comparator.comparing(ReviewAssignment::createdAt)
                            .thenComparing(ReviewAssignment::id).reversed())
                    .toList();
            return new AssignmentPage(new ArrayList<>(rows), false);
        }

        @Override
        public List<ReviewAssignment> listAllByRun(UUID runId) {
            return byId.values().stream().filter(a -> a.runId().equals(runId)).toList();
        }

        @Override
        public int countOpenByCase(UUID runId, UUID caseExecutionId, Instant now) {
            return (int) byId.values().stream()
                    .filter(a -> a.runId().equals(runId)
                            && a.caseExecutionId().equals(caseExecutionId))
                    .filter(a -> a.status() == ReviewAssignment.Status.PENDING
                            || (a.status() == ReviewAssignment.Status.IN_PROGRESS
                                    && !a.leaseExpired(now)))
                    .count();
        }
    }

    /** review_verdict 内存账本（insert-only；uq(assignment_id) 模拟） */
    private static final class InMemoryVerdicts implements ReviewVerdictRepository {
        final List<ReviewVerdict> rows = new ArrayList<>();

        @Override
        public void insert(ReviewVerdict verdict) {
            boolean dup = rows.stream()
                    .anyMatch(v -> v.assignmentId().equals(verdict.assignmentId()));
            if (dup) {
                throw new DuplicateKeyException("uq_review_verdict_assignment");
            }
            rows.add(verdict);
        }

        @Override
        public Optional<ReviewVerdict> findByAssignmentId(UUID assignmentId) {
            return rows.stream().filter(v -> v.assignmentId().equals(assignmentId))
                    .findFirst();
        }

        @Override
        public List<ReviewVerdict> listByCase(UUID runId, UUID caseExecutionId) {
            return rows.stream()
                    .filter(v -> v.runId().equals(runId)
                            && v.caseExecutionId().equals(caseExecutionId))
                    .toList();
        }

        @Override
        public List<ReviewVerdict> listByRun(UUID runId) {
            return rows.stream().filter(v -> v.runId().equals(runId)).toList();
        }
    }

    /** 读面桩：run 存在性 + 案例页 + 案例详情 + 身份解析（身份缺席 = HOLDOUT 盲评面） */
    private static final class StubReader implements EvalQueryReader {
        EvalCaseDetailRow detail;
        CaseIdentityRow identity;
        EvalCaseRow caseRow;

        @Override
        public EvalRunPage listRuns(String state, KeysetCursor cursor, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<EvalRunRow> findRun(UUID runId) {
            if (!RUN_ID.equals(runId)) {
                return Optional.empty();
            }
            return Optional.of(new EvalRunRow(runId, "ds", "r".repeat(64), "m", "p",
                    "c".repeat(64), "SUCCEEDED", NOW, NOW, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, 1, null,
                    null, null, null, null, null, null, null));
        }

        @Override
        public EvalCasePage listCases(UUID runId, String verdict, String afterScenario,
                                      Integer afterRound, int limit) {
            return new EvalCasePage(caseRow == null ? List.of() : List.of(caseRow), false);
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
            return Optional.ofNullable(detail);
        }

        @Override
        public Optional<CaseIdentityRow> findCaseIdentity(String datasetVersion,
                                                          String scenarioId) {
            return Optional.ofNullable(identity);
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
        public List<EvalQueryReader.UsageCallRow> listUsageCalls(UUID evalRunId) {
            return List.of();
        }

        @Override
        public List<EvalQueryReader.UsageCallRow> listUsageCallsForRuns(
                Iterable<UUID> evalRunIds) {
            return List.of();
        }
    }

    private InMemoryAssignments assignments;
    private InMemoryVerdicts verdicts;
    private StubReader reader;
    private EvalReviewService service;

    @BeforeEach
    void setUp() {
        assignments = new InMemoryAssignments();
        verdicts = new InMemoryVerdicts();
        reader = new StubReader();
        reader.caseRow = new EvalCaseRow(CASE_ID, "scn-1", 1, "DECIDABLE", true,
                "{\"component\":\"db\"}", "{\"component\":\"db\"}", 1200L, null, null, null);
        EvalRubricRegistry rubrics = EvalRubricRegistry.load("""
                registry_version: 1
                rubrics:
                  - id: rca-eval-review
                    version: eval-review-rubric-v1
                    current: true
                    items:
                      - id: root_cause_correct
                        label: 根因判定正确性
                        required: true
                """);
        service = new EvalReviewService(assignments, verdicts, reader, rubrics,
                new ObjectMapper());
    }

    private ReviewAssignment pendingAssignment() {
        ReviewAssignment a = ReviewAssignment.pending(UUID.randomUUID(), RUN_ID, CASE_ID,
                ALICE, NOW);
        assignments.insert(a);
        return a;
    }

    private EvalReviewService.SubmitResult submit(UUID assignmentId, String actor,
                                                  int revision, String verdictWord) {
        return service.submit(assignmentId, actor, RUBRIC, verdictWord, 4,
                List.of("tag"), "理由：证据充分", List.of(), revision).orElseThrow();
    }

    // ------------------------------------------------------------------ 任务生成

    @Test
    @DisplayName("生成：ensure 幂等——首次补足 perCase 份，重复调用补 0")
    void generateIsIdempotentEnsure() {
        var first = service.generate(RUN_ID, null, 2, ALICE).orElseThrow();
        assertThat(first.created()).isEqualTo(2);
        var second = service.generate(RUN_ID, null, 2, ALICE).orElseThrow();
        assertThat(second.created()).isZero();
        assertThat(second.openTotal()).isEqualTo(2);
        assertThat(assignments.byId).hasSize(2);
    }

    @Test
    @DisplayName("生成：run 未知 → 404 面；perCase 越界与外来案例 → 400 面")
    void generateValidatesRunAndPerCase() {
        assertThat(service.generate(UUID.randomUUID(), null, 1, ALICE)).isEmpty();
        assertThatThrownBy(() -> service.generate(RUN_ID, null, 0, ALICE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.generate(RUN_ID, null, 5, ALICE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.generate(RUN_ID,
                List.of(UUID.randomUUID()), 1, ALICE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于该 run");
    }

    // ------------------------------------------------------------------ 领取 CAS

    @Test
    @DisplayName("领取：双人同领一人赢（CAS 行数 0 = 他人持有 409）；同人重领幂等 REPLAYED")
    void claimCasSingleWinner() {
        ReviewAssignment a = pendingAssignment();
        var alice = service.claim(a.id(), ALICE).orElseThrow();
        assertThat(alice.status()).isEqualTo(EvalReviewService.ClaimStatus.CLAIMED);
        assertThat(alice.assignment().reviewer()).isEqualTo(ALICE);
        assertThat(alice.assignment().revision()).isEqualTo(1);
        assertThat(alice.assignment().leaseExpiresAt()).isNotNull();

        var bob = service.claim(a.id(), BOB).orElseThrow();
        assertThat(bob.status()).isEqualTo(EvalReviewService.ClaimStatus.CONFLICT_HELD);

        var replay = service.claim(a.id(), ALICE).orElseThrow();
        assertThat(replay.status()).isEqualTo(EvalReviewService.ClaimStatus.REPLAYED);
        assertThat(replay.assignment().revision()).isEqualTo(1);
    }

    @Test
    @DisplayName("领取：租约超时惰性回收——有效状态投影 PENDING，他人可重领且 revision+1")
    void claimReclaimsExpiredLease() {
        ReviewAssignment a = ReviewAssignment.pending(UUID.randomUUID(), RUN_ID, CASE_ID,
                ALICE, PAST);
        // 手工构造过期租约的 IN_PROGRESS（NOW 前 2 小时领取、30 分钟租约早过）
        assignments.insert(new ReviewAssignment(a.id(), a.runId(), a.caseExecutionId(),
                ReviewAssignment.Status.IN_PROGRESS, ALICE, 1,
                PAST, PAST.plusSeconds(1800), null, ALICE, PAST));

        ReviewAssignment stored = assignments.findById(a.id()).orElseThrow();
        assertThat(stored.effectiveStatus(Instant.now()))
                .isEqualTo(ReviewAssignment.Status.PENDING);

        var bob = service.claim(a.id(), BOB).orElseThrow();
        assertThat(bob.status()).isEqualTo(EvalReviewService.ClaimStatus.CLAIMED);
        assertThat(bob.assignment().reviewer()).isEqualTo(BOB);
        assertThat(bob.assignment().revision()).isEqualTo(2);
    }

    @Test
    @DisplayName("领取：已提交任务拒领；同人对同案例第二份进行中任务 409（uq 兜底）")
    void claimRejectsSubmittedAndSelfDuplicate() {
        ReviewAssignment a = pendingAssignment();
        service.claim(a.id(), ALICE);
        submit(a.id(), ALICE, 1, "CORRECT");
        assertThat(service.claim(a.id(), ALICE).orElseThrow().status())
                .isEqualTo(EvalReviewService.ClaimStatus.CONFLICT_SUBMITTED);

        ReviewAssignment second = pendingAssignment();
        assertThat(service.claim(second.id(), ALICE).orElseThrow().status())
                .isEqualTo(EvalReviewService.ClaimStatus.CLAIMED);
        ReviewAssignment third = pendingAssignment();
        assertThat(service.claim(third.id(), ALICE).orElseThrow().status())
                .isEqualTo(EvalReviewService.ClaimStatus.CONFLICT_SELF_ACTIVE);
    }

    // ------------------------------------------------------------------ 提交

    @Test
    @DisplayName("提交：rubric 版本必带且在注册表、理由必填、expectedRevision 必带（400 面）")
    void submitValidatesInputs() {
        ReviewAssignment a = pendingAssignment();
        service.claim(a.id(), ALICE);
        assertThatThrownBy(() -> service.submit(a.id(), ALICE, null, "CORRECT", 4,
                List.of(), "理由", List.of(), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rubricVersion");
        assertThatThrownBy(() -> service.submit(a.id(), ALICE, "rubric-v999", "CORRECT",
                4, List.of(), "理由", List.of(), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知");
        assertThatThrownBy(() -> service.submit(a.id(), ALICE, RUBRIC, "WRONG", 4,
                List.of(), "理由", List.of(), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verdict");
        assertThatThrownBy(() -> service.submit(a.id(), ALICE, RUBRIC, "CORRECT", 4,
                List.of(), "  ", List.of(), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        assertThatThrownBy(() -> service.submit(a.id(), ALICE, RUBRIC, "CORRECT", 4,
                List.of(), "理由", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedRevision");
    }

    @Test
    @DisplayName("提交：成功落档后不可改——重复提交 CONFLICT_STATE，结论行 insert-only")
    void submitOnceThenImmutable() {
        ReviewAssignment a = pendingAssignment();
        service.claim(a.id(), ALICE);
        var ok = submit(a.id(), ALICE, 1, "CORRECT");
        assertThat(ok.status()).isEqualTo(EvalReviewService.SubmitStatus.SUBMITTED);
        assertThat(ok.currentRevision()).isEqualTo(2);

        var again = submit(a.id(), ALICE, 2, "INCORRECT");
        assertThat(again.status()).isEqualTo(EvalReviewService.SubmitStatus.CONFLICT_STATE);
        assertThat(verdicts.rows).hasSize(1);
        assertThat(verdicts.rows.get(0).verdict())
                .isEqualTo(ReviewVerdict.Verdict.CORRECT);
        assertThat(verdicts.rows.get(0).rubricVersion()).isEqualTo(RUBRIC);
    }

    @Test
    @DisplayName("提交：越权/revision 漂移 409——租约被回收重领后旧持有者提交必撞")
    void submitConflictsOnActorAndRevision() {
        ReviewAssignment a = pendingAssignment();
        service.claim(a.id(), ALICE);
        var foreign = submit(a.id(), BOB, 1, "CORRECT");
        assertThat(foreign.status()).isEqualTo(EvalReviewService.SubmitStatus.CONFLICT_ACTOR);
        var stale = submit(a.id(), ALICE, 9, "CORRECT");
        assertThat(stale.status())
                .isEqualTo(EvalReviewService.SubmitStatus.CONFLICT_REVISION);

        // 租约过期 → Bob 重领（revision 2）→ Alice 旧 revision 提交撞 409
        ReviewAssignment b = ReviewAssignment.pending(UUID.randomUUID(), RUN_ID, CASE_ID,
                ALICE, PAST);
        assignments.insert(new ReviewAssignment(b.id(), b.runId(), b.caseExecutionId(),
                ReviewAssignment.Status.IN_PROGRESS, ALICE, 1,
                PAST, PAST.plusSeconds(1800), null, ALICE, PAST));
        service.claim(b.id(), BOB);
        var aliceStale = submit(b.id(), ALICE, 1, "CORRECT");
        assertThat(aliceStale.status())
                .isEqualTo(EvalReviewService.SubmitStatus.CONFLICT_ACTOR);
        var bobOk = submit(b.id(), BOB, 2, "CORRECT");
        assertThat(bobOk.status()).isEqualTo(EvalReviewService.SubmitStatus.SUBMITTED);
    }

    @Test
    @DisplayName("重评分 = 新任务 + 新结论行，旧行永不覆盖（审计闭环）")
    void rescoreIsNewRowNeverOverwrite() {
        ReviewAssignment first = pendingAssignment();
        service.claim(first.id(), ALICE);
        submit(first.id(), ALICE, 1, "INCORRECT");

        ReviewAssignment second = pendingAssignment();
        service.claim(second.id(), BOB);
        submit(second.id(), BOB, 1, "CORRECT");

        List<ReviewVerdict> history = verdicts.listByCase(RUN_ID, CASE_ID);
        assertThat(history).hasSize(2);
        assertThat(history.stream().map(ReviewVerdict::verdict).toList())
                .containsExactlyInAnyOrder(ReviewVerdict.Verdict.INCORRECT,
                        ReviewVerdict.Verdict.CORRECT);
        // 旧行原样保留（第一行仍是 ALICE/INCORRECT）
        assertThat(history.stream()
                .filter(v -> v.assignmentId().equals(first.id())).findFirst().orElseThrow()
                .reviewer()).isEqualTo(ALICE);
    }

    // ------------------------------------------------------------------ 盲评投影

    private void stubDetail() {
        reader.detail = new EvalCaseDetailRow(CASE_ID, RUN_ID, "scn-1", 1, "ds-v1",
                "sel-v1", "DECIDABLE", true,
                "{\"component\":\"db\",\"fault_type\":\"lock\"}",
                "{\"component\":\"db\",\"fault_type\":\"lock\"}",
                "[\"SYM_A\"]", "[\"SYM_A\",\"SYM_B\"]",
                1, 0, 0, 1200L, false, "\"sample failure\"", NOW,
                UUID.randomUUID(), null, UUID.randomUUID(),
                "SUCCEEDED", UUID.randomUUID(), NOW, NOW,
                2, "VALID", "model-x", NOW, null);
    }

    @Test
    @DisplayName("盲评：HOLDOUT 案例身份 RLS 不可见 → blind=true，GT 三字段恒 null，"
            + "只给症状与系统输出")
    void workspaceBlindProjectionHidesGt() {
        stubDetail();
        reader.identity = null; // HOLDOUT：case_version RLS 不可见
        ReviewAssignment a = pendingAssignment();
        var ws = service.workspace(a.id()).orElseThrow();
        EvalReviewService.ReviewCaseProjection view = ws.reviewCase();
        assertThat(view.blind()).isTrue();
        assertThat(view.expectedRootCause()).isNull();
        assertThat(view.expectedSymptomCodes()).isNull();
        assertThat(view.rootCauseHit()).isNull();
        // 症状与系统输出可见
        assertThat(view.actualRootCause()).isEqualTo("db/lock");
        assertThat(view.actualSymptomCodes()).containsExactly("SYM_A", "SYM_B");
        assertThat(view.machineVerdict()).isEqualTo("DECIDABLE");
        assertThat(view.failureSample()).isEqualTo("sample failure");
    }

    @Test
    @DisplayName("非盲评：身份解析可见且非 HOLDOUT → GT 字段透出")
    void workspaceNonHoldoutShowsGt() {
        stubDetail();
        reader.identity = new CaseIdentityRow("scn-1", "fam-1", "d".repeat(64),
                NOW, null, "TUNING", "rca100", "ds-v1", "PRIVATE");
        ReviewAssignment a = pendingAssignment();
        var view = service.workspace(a.id()).orElseThrow().reviewCase();
        assertThat(view.blind()).isFalse();
        assertThat(view.expectedRootCause()).isEqualTo("db/lock");
        assertThat(view.expectedSymptomCodes()).containsExactly("SYM_A");
        assertThat(view.rootCauseHit()).isTrue();
    }

    // ------------------------------------------------------------------ 分歧检测

    private ReviewVerdict verdict(UUID assignmentId, String reviewer, String word,
                                  Integer score, Instant at) {
        return new ReviewVerdict(UUID.randomUUID(), assignmentId, RUN_ID, CASE_ID,
                reviewer, RUBRIC, ReviewVerdict.Verdict.valueOf(word), score,
                List.of(), "理由", List.of(), at);
    }

    @Test
    @DisplayName("分歧：同案例两评审结论不一致入清单；一致不入；单人双份（重评分）取最新行")
    void disagreementDetection() {
        UUID case2 = UUID.randomUUID();
        // 案例 1：CORRECT vs INCORRECT → 分歧
        verdicts.rows.add(verdict(UUID.randomUUID(), ALICE, "CORRECT", 5, NOW));
        verdicts.rows.add(verdict(UUID.randomUUID(), BOB, "INCORRECT", 1,
                NOW.plusSeconds(1)));
        // 案例 2：ALICE 先 INCORRECT 后重评分 CORRECT（新行）→ 与 BOB 一致，非分歧
        verdicts.rows.add(new ReviewVerdict(UUID.randomUUID(), UUID.randomUUID(), RUN_ID,
                case2, ALICE, RUBRIC, ReviewVerdict.Verdict.INCORRECT, 1,
                List.of(), "理由", List.of(), NOW));
        verdicts.rows.add(new ReviewVerdict(UUID.randomUUID(), UUID.randomUUID(), RUN_ID,
                case2, ALICE, RUBRIC, ReviewVerdict.Verdict.CORRECT, 5,
                List.of(), "更正理由", List.of(), NOW.plusSeconds(2)));
        verdicts.rows.add(new ReviewVerdict(UUID.randomUUID(), UUID.randomUUID(), RUN_ID,
                case2, BOB, RUBRIC, ReviewVerdict.Verdict.CORRECT, 5,
                List.of(), "理由", List.of(), NOW.plusSeconds(3)));

        var out = service.disagreements(RUN_ID).orElseThrow();
        assertThat(out.items()).hasSize(1);
        assertThat(out.items().get(0).caseExecutionId()).isEqualTo(CASE_ID);
        assertThat(out.items().get(0).latestByReviewer()).hasSize(2);
    }

    @Test
    @DisplayName("分歧：分数不一致（同结论词）也算分歧")
    void disagreementOnScoreDifference() {
        verdicts.rows.add(verdict(UUID.randomUUID(), ALICE, "PARTIAL", 3, NOW));
        verdicts.rows.add(verdict(UUID.randomUUID(), BOB, "PARTIAL", 4,
                NOW.plusSeconds(1)));
        assertThat(service.disagreements(RUN_ID).orElseThrow().items()).hasSize(1);
    }

    // ------------------------------------------------------------------ 进度分桶与列表

    @Test
    @DisplayName("进度：待评/进行/已提交/已评案例/分歧分桶（过期租约计入待评）")
    void progressBuckets() {
        ReviewAssignment expired = ReviewAssignment.pending(UUID.randomUUID(), RUN_ID,
                CASE_ID, ALICE, PAST);
        assignments.insert(new ReviewAssignment(expired.id(), RUN_ID, CASE_ID,
                ReviewAssignment.Status.IN_PROGRESS, ALICE, 1,
                PAST, PAST.plusSeconds(1800), null, ALICE, PAST));
        ReviewAssignment active = pendingAssignment();
        service.claim(active.id(), BOB);
        ReviewAssignment done = pendingAssignment();
        service.claim(done.id(), ALICE);
        submit(done.id(), ALICE, 1, "CORRECT");

        var progress = service.progress(RUN_ID).orElseThrow();
        assertThat(progress.totalAssignments()).isEqualTo(3);
        assertThat(progress.pending()).isEqualTo(1);      // 过期租约惰性回收
        assertThat(progress.inProgress()).isEqualTo(1);
        assertThat(progress.submitted()).isEqualTo(1);
        assertThat(progress.reviewedCases()).isEqualTo(1);
        assertThat(progress.caseCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("列表：scope=mine 只出我的；status 按有效状态过滤（过期租约归 PENDING）")
    void listAssignmentsScopeAndEffectiveStatus() {
        ReviewAssignment expired = ReviewAssignment.pending(UUID.randomUUID(), RUN_ID,
                CASE_ID, ALICE, PAST);
        assignments.insert(new ReviewAssignment(expired.id(), RUN_ID, CASE_ID,
                ReviewAssignment.Status.IN_PROGRESS, ALICE, 1,
                PAST, PAST.plusSeconds(1800), null, ALICE, PAST));
        ReviewAssignment mine = pendingAssignment();
        service.claim(mine.id(), ALICE);

        var all = service.listAssignments(RUN_ID, "all", null, ALICE, null, 50)
                .orElseThrow();
        assertThat(all.items()).hasSize(2);
        var mineOnly = service.listAssignments(RUN_ID, "mine", null, ALICE, null, 50)
                .orElseThrow();
        assertThat(mineOnly.items()).hasSize(2); // 过期那份 reviewer 也是 ALICE
        var bobMine = service.listAssignments(RUN_ID, "mine", null, BOB, null, 50)
                .orElseThrow();
        assertThat(bobMine.items()).isEmpty();
        var pendingOnly = service.listAssignments(RUN_ID, "all", "PENDING", ALICE,
                null, 50).orElseThrow();
        assertThat(pendingOnly.items()).hasSize(1);
        assertThat(pendingOnly.items().get(0).effectiveStatus()).isEqualTo("PENDING");
        assertThat(pendingOnly.items().get(0).status()).isEqualTo("IN_PROGRESS");
        assertThatThrownBy(() -> service.listAssignments(RUN_ID, "bad", null, ALICE,
                null, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.listAssignments(UUID.randomUUID(), "all", null, ALICE,
                null, 50)).isEmpty();
    }
}
