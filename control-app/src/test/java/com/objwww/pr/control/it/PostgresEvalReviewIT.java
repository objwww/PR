package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.ScenarioMetrics;
import com.objwww.pr.control.eval.domain.model.ReviewAssignment;
import com.objwww.pr.control.eval.domain.model.ReviewVerdict;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvalRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReviewAssignmentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReviewVerdictRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EV-08 验收（真 PG）：领取 CAS（双人同领行级只一人赢）、提交 CAS（revision 四锚）、
 * review_verdict insert-only（uq(assignment_id) 二次插入违约；control_app 零
 * update/delete）、review_assignment 列级授权（状态推进六列可改、run_id 等锚列
 * 不可改）、授权矩阵（eval_app/publisher_app/notify_app 两表零权限）、HOLDOUT
 * 计数针孔（RLS 下 control_app 不可见 HOLDOUT 案例行，但 security definer 函数
 * 只出聚合计数；函数对非 control_app 角色不可执行）。
 *
 * <p>本机无 Docker → Testcontainers 整类跳过（NOT_RUN）；真 PG 环境跑 Flyway 全迁移。
 */
class PostgresEvalReviewIT extends PostgresITBase {

    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");

    private PostgresEvalRunRepository evalRuns;
    private PostgresReviewAssignmentRepository assignments;
    private PostgresReviewVerdictRepository verdicts;

    @BeforeEach
    void setUpRepositories() {
        evalRuns = new PostgresEvalRunRepository(evalJdbc);
        assignments = new PostgresReviewAssignmentRepository(controlJdbc);
        verdicts = new PostgresReviewVerdictRepository(controlJdbc,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private EvalRunMetadata metadata() {
        return new EvalRunMetadata(1, "rca100-v1.1", Digest.sha256Of("registry"), 1,
                "deepseek-v3", "am3-rca-v2", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), new BigDecimal("0.7"), new BigDecimal("0.9"),
                null, null, null,
                "litellm/internal-v1#key-eval", Digest.sha256Of("rules"),
                "scenario-driver-v1", "grader-test-v1");
    }

    /** TIMEOUT_OR_ABSENT 案例（无 rca 链，V10 判定形态 CHECK 同律） */
    private EvalCaseResult caseOf(UUID evalRunId) {
        return new EvalCaseResult(UUID.randomUUID(), evalRunId, "scn-review", 1,
                "state-machine-selected-v1", null, null, null,
                ScenarioMetrics.ScoringVerdict.TIMEOUT_OR_ABSENT, false,
                new TypedRootCause("redis", "OOM", "eviction"), null,
                List.of("RedisDown"), null, 0, 0, 1, null, false, null);
    }

    private ReviewAssignment seedAssignment(UUID runId, UUID caseExecutionId) {
        ReviewAssignment a = ReviewAssignment.pending(UUID.randomUUID(), runId,
                caseExecutionId, "it-operator", NOW);
        assignments.insert(a);
        return a;
    }

    // ------------------------------------------------------------------ 领取/提交 CAS

    @Test
    @DisplayName("领取 CAS：双人同领行级只一人赢；已提交任务谓词拒领")
    void claimCasSingleWinner() {
        UUID runId = UUID.randomUUID();
        evalRuns.insertRunning(EvalRun.running(runId, metadata(), NOW));
        EvalCaseResult caseRow = caseOf(runId);
        evalRuns.insertCaseResult(caseRow);
        ReviewAssignment a = seedAssignment(runId, caseRow.id());

        boolean alice = assignments.claim(a.id(), "alice", NOW,
                NOW.plusSeconds(1800), NOW);
        boolean bob = assignments.claim(a.id(), "bob", NOW.plusSeconds(1),
                NOW.plusSeconds(1801), NOW.plusSeconds(1));
        assertThat(alice).isTrue();
        assertThat(bob).isFalse();

        // 租约超时后可重领（惰性回收：无回收 UPDATE，领取谓词覆盖回收面）
        boolean bobAfterExpiry = assignments.claim(a.id(), "bob", NOW.plusSeconds(3601),
                NOW.plusSeconds(5401), NOW.plusSeconds(3601));
        assertThat(bobAfterExpiry).isTrue();

        // 提交后谓词拒领
        assertThat(assignments.submit(a.id(), "bob", 2, NOW.plusSeconds(3700))).isTrue();
        assertThat(assignments.claim(a.id(), "carol", NOW.plusSeconds(3701),
                NOW.plusSeconds(5501), NOW.plusSeconds(3701))).isFalse();
    }

    @Test
    @DisplayName("提交 CAS：reviewer/status/revision 四锚缺一即败（行数 0）")
    void submitCasFourAnchors() {
        UUID runId = UUID.randomUUID();
        evalRuns.insertRunning(EvalRun.running(runId, metadata(), NOW));
        EvalCaseResult caseRow = caseOf(runId);
        evalRuns.insertCaseResult(caseRow);
        ReviewAssignment a = seedAssignment(runId, caseRow.id());
        assignments.claim(a.id(), "alice", NOW, NOW.plusSeconds(1800), NOW);

        assertThat(assignments.submit(a.id(), "bob", 1, NOW)).isFalse();   // 越权
        assertThat(assignments.submit(a.id(), "alice", 9, NOW)).isFalse(); // revision 漂移
        assertThat(assignments.submit(a.id(), "alice", 1, NOW)).isTrue();
        assertThat(assignments.submit(a.id(), "alice", 2, NOW)).isFalse(); // 已提交
    }

    // ------------------------------------------------------------------ insert-only / 授权矩阵

    @Test
    @DisplayName("review_verdict insert-only：uq(assignment_id) 二次插入违约；"
            + "control_app 零 update/delete")
    void verdictInsertOnly() {
        UUID runId = UUID.randomUUID();
        evalRuns.insertRunning(EvalRun.running(runId, metadata(), NOW));
        EvalCaseResult caseRow = caseOf(runId);
        evalRuns.insertCaseResult(caseRow);
        ReviewAssignment a = seedAssignment(runId, caseRow.id());
        assignments.claim(a.id(), "alice", NOW, NOW.plusSeconds(1800), NOW);
        assignments.submit(a.id(), "alice", 1, NOW);

        ReviewVerdict v = new ReviewVerdict(UUID.randomUUID(), a.id(), runId, caseRow.id(),
                "alice", "eval-review-rubric-v1", ReviewVerdict.Verdict.CORRECT, 5,
                List.of("tag-a"), "理由充分", List.of(), NOW);
        verdicts.insert(v);
        ReviewVerdict dup = new ReviewVerdict(UUID.randomUUID(), a.id(), runId,
                caseRow.id(), "alice", "eval-review-rubric-v1",
                ReviewVerdict.Verdict.INCORRECT, 1, List.of(), "更正", List.of(), NOW);
        assertThatThrownBy(() -> verdicts.insert(dup))
                .hasMessageContaining("review_verdict");

        assertThat(verdicts.findByAssignmentId(a.id())).isPresent();
        assertThat(verdicts.listByCase(runId, caseRow.id())).hasSize(1);
        assertThat(verdicts.listByCase(runId, caseRow.id()).get(0).labels())
                .containsExactly("tag-a");

        assertThatThrownBy(() -> controlJdbc.sql(
                "UPDATE review_verdict SET reason = 'x' WHERE assignment_id = '"
                        + a.id() + "'").update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> controlJdbc.sql("DELETE FROM review_verdict")
                .update()).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("授权矩阵：eval/publisher/notify 两表零权限；control_app 对"
            + " review_assignment 仅状态推进六列可 UPDATE（锚列 run_id 拒改）")
    void grantMatrixKeepsReviewFaceMinimal() {
        UUID runId = UUID.randomUUID();
        evalRuns.insertRunning(EvalRun.running(runId, metadata(), NOW));
        EvalCaseResult caseRow = caseOf(runId);
        evalRuns.insertCaseResult(caseRow);
        ReviewAssignment a = seedAssignment(runId, caseRow.id());

        // 状态推进列可改（列级授权面内）
        assertThat(controlJdbc.sql("UPDATE review_assignment SET revision = revision + 0"
                + " WHERE id = '" + a.id() + "'").update()).isEqualTo(1);
        // 锚列不在列级授权面 → 拒改
        assertThatThrownBy(() -> controlJdbc.sql("UPDATE review_assignment"
                + " SET run_id = '" + UUID.randomUUID() + "' WHERE id = '" + a.id() + "'")
                .update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> controlJdbc.sql("UPDATE review_assignment"
                + " SET created_by = 'x' WHERE id = '" + a.id() + "'")
                .update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> controlJdbc.sql("DELETE FROM review_assignment")
                .update()).isInstanceOf(Exception.class);

        for (var jdbc : List.of(evalJdbc, publisherJdbc, notifyJdbc)) {
            assertThatThrownBy(() -> jdbc.sql("SELECT count(*) FROM review_assignment")
                    .query(Long.class).single()).isInstanceOf(Exception.class);
            assertThatThrownBy(() -> jdbc.sql("SELECT count(*) FROM review_verdict")
                    .query(Long.class).single()).isInstanceOf(Exception.class);
        }
    }

    // ------------------------------------------------------------------ HOLDOUT 计数针孔

    @Test
    @DisplayName("HOLDOUT 计数针孔：RLS 下 control_app 不可见 HOLDOUT 案例行，"
            + "security definer 函数只出聚合计数；函数对 publisher 不可执行")
    void holdoutCountsPinhole() {
        UUID datasetId = UUID.randomUUID();
        // PRIVATE × HOLDOUT 组合（INV-AM5-1 触发器面内）+ HOLDOUT 案例行：
        // eval_app RLS with check 拒写 HOLDOUT → 由 admin（BYPASSRLS）落档
        adminJdbc.sql("""
                INSERT INTO dataset_version(id, source, name, version, source_uri, license,
                    access_class, content_digest, adapter_version, imported_at,
                    source_class, partition_class, scenario_family_digest)
                VALUES (:id, 'rca100', 'holdout-ds', 'v9', 's3://x', 'MIT', 'internal',
                    :digest, 'adapter-v1', now(), 'PRIVATE', 'HOLDOUT', :fam)
                """).param("id", datasetId)
                .param("digest", Digest.sha256Of("ds-" + datasetId).value())
                .param("fam", Digest.sha256Of("fam-" + datasetId).value()).update();
        for (int i = 0; i < 3; i++) {
            adminJdbc.sql("""
                    INSERT INTO case_version(id, dataset_version_id, case_key,
                        scenario_family_id, valid_from, content_digest, payload,
                        partition_class)
                    VALUES (:id, :ds, :key, :family, now(), :digest,
                        CAST('{"caseKey":"x"}' AS jsonb), 'HOLDOUT')
                    """).param("id", UUID.randomUUID()).param("ds", datasetId)
                    .param("key", "h-" + i).param("family", "fam-h")
                    .param("digest", Digest.sha256Of("case-h-" + i + datasetId).value())
                    .update();
        }

        // RLS：control_app 对 HOLDOUT case_version 恒 0 行（GT 原文不出）
        Long visible = controlJdbc.sql(
                "SELECT count(*) FROM case_version WHERE dataset_version_id = '"
                        + datasetId + "'").query(Long.class).single();
        assertThat(visible).isZero();

        // 针孔：聚合计数可见（HOLDOUT=3），只出 (版本,分区,计数) 三列
        Long holdoutCount = controlJdbc.sql("""
                SELECT case_count FROM case_version_partition_counts()
                WHERE dataset_version_id = :ds AND partition_class = 'HOLDOUT'
                """).param("ds", datasetId).query(Long.class).single();
        assertThat(holdoutCount).isEqualTo(3L);

        // 函数对非 control_app 角色不可执行
        assertThatThrownBy(() -> publisherJdbc.sql(
                "SELECT count(*) FROM case_version_partition_counts()")
                .query(Long.class).single()).isInstanceOf(Exception.class);
    }
}
