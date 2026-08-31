package com.objwww.pr.publisher.it;

import com.objwww.pr.control.domain.model.ReviewRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E-15（M1 方案 §11/L6，降为 IT 可证）：100 个 PRESENT 资源、LIMIT 50——
 * next_check_at 公平排序两轮全覆盖，不死循环前 50 行、不饿死尾部。
 *
 * <p>造数：真实 dispatch 建 subject/revision/run 后，admin 直插 100 条 CONFIRMED 命令 +
 * 100 行 PRESENT 资源（next_check_at 交错在过去，最久未查先查）。payload_hash 是不在
 * CAS 的假 digest——探针统一归 UNKNOWN（零触网），本用例只证公平扫描与覆盖，
 * 判定分支由 ST-15/EX-14/EX-17 覆盖。
 */
class E2E15FairDriftScanIT extends PostgresITBase {

    private static final int TOTAL = 100;
    private static final int BUDGET = 50;

    private ItHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ItHarness(casDir, "http://unused.invalid");
        ReviewRun run = harness.dispatchOpened(
                ItHarness.prEvent("e2e15-d1", 2115L, "objwww/mall", 29,
                        "head" + "2".repeat(36), "opened"),
                ItTarballs.singleFile("src/A.java", "class A {}\n"), "diff");

        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(2115L, 29)
                .orElseThrow().getId();
        UUID revisionId = run.getPrRevisionId();
        for (int i = 0; i < TOTAL; i++) {
            UUID opId = UUID.randomUUID();
            adminJdbc.sql("""
                            INSERT INTO outbox_command (
                                operation_id, pr_subject_id, review_run_id, pr_revision_id,
                                aggregate_key, aggregate_sequence, publication_epoch, fence_mode,
                                command_type, state, policy_version, payload_hash,
                                remote_identity_type, remote_id, created_at, updated_at, confirmed_at
                            ) VALUES (
                                :op, :subject, :run, :revision,
                                'pr:2115#29', :seq, 1, 'CURRENT_EPOCH',
                                'CREATE_CHECK', 'CONFIRMED', 'policy-v1', :payloadHash,
                                'EXTERNAL_ID', :rid, now(), now(), now()
                            )
                            """)
                    .param("op", opId)
                    .param("subject", subjectId)
                    .param("run", run.getId())
                    .param("revision", revisionId)
                    .param("seq", 1000 + i)
                    // 不在 CAS 的假 digest（char(64) 零填充）→ 探针归 UNKNOWN，零触网
                    .param("payloadHash", "%064d".formatted(i))
                    .param("rid", "drift-check-" + i)
                    .update();
            adminJdbc.sql("""
                            INSERT INTO publication_resource (
                                id, resource_type, created_by_operation_id, pr_subject_id,
                                remote_id, state, next_check_at, created_at, updated_at
                            ) VALUES (
                                :id, 'CHECK_RUN', :op, :subject,
                                :rid, 'PRESENT', now() - make_interval(secs => :age),
                                now(), now()
                            )
                            """)
                    .param("id", UUID.randomUUID())
                    .param("op", opId)
                    .param("subject", subjectId)
                    // i=0 最久未查（100 秒前），i=99 最近（1 秒前）
                    .param("age", TOTAL - i)
                    .param("rid", "drift-check-" + i)
                    .update();
        }
    }

    private int touchedUpTo(int exclusiveMaxIndex) {
        return adminJdbc.sql("""
                        SELECT count(*) FROM publication_resource
                         WHERE check_error_count = 1
                           AND remote_id IN (
                               SELECT 'drift-check-' || g FROM generate_series(0, :max - 1) g)
                        """)
                .param("max", exclusiveMaxIndex)
                .query(Long.class).single().intValue();
    }

    @Test
    void twoRoundsCoverAllWithoutStarvation() {
        // 第一轮：预算 50，恰好处理最久未查的 50 行（i=0..49），尾部未被触碰
        assertThat(harness.newDriftReconciler(BUDGET).runOnce()).isEqualTo(BUDGET);
        assertThat(touchedUpTo(50)).isEqualTo(50);
        assertThat(adminJdbc.sql("""
                        SELECT count(*) FROM publication_resource
                         WHERE check_error_count = 1
                           AND remote_id IN (
                               SELECT 'drift-check-' || g FROM generate_series(50, 99) g)
                        """)
                .query(Long.class).single()).isZero();

        // 第二轮：被处理行的 next_check_at 已推到未来（退避），剩余 50 行自然轮到
        assertThat(harness.newDriftReconciler(BUDGET).runOnce()).isEqualTo(BUDGET);
        assertThat(adminJdbc.sql(
                "SELECT count(*) FROM publication_resource WHERE check_error_count = 1")
                .query(Long.class).single()).isEqualTo(TOTAL);

        // 第三轮：无到期行（全部排到未来），零处理——不死循环
        assertThat(harness.newDriftReconciler(BUDGET).runOnce()).isZero();
    }
}
