package com.objwww.pr.control.it;

import com.objwww.pr.control.ops.application.CaseDraft;
import com.objwww.pr.control.ops.application.CaseRevisionConflictException;
import com.objwww.pr.control.ops.application.OperatorCaseService;
import com.objwww.pr.control.ops.domain.model.CaseStatus;
import com.objwww.pr.control.ops.domain.model.OperatorCase;
import com.objwww.pr.control.ops.domain.repository.OperatorCaseRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresOperatorCaseRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * OperatorCase 真 PG 集成（M5-11；落码方案 §M5-11④ IT 面；命名 *IT 由 failsafe
 * verify 执行，本机无 docker 自动跳过，195 真跑补证据）：
 * <ul>
 *   <li>幂等合并：同 (tenant,fingerprint) 连续三次 → 单行 revision 3（uq 键 + 聚合语义）；</li>
 *   <li>并发认领 CAS：同 expected-revision 双 claim 恰一人成功，败者冲突零副作用；</li>
 *   <li>SLA 升级恰一次：同 idempotency-key 重放 → 审计行/revision 只 bump 一次。</li>
 * </ul>
 */
class PostgresOperatorCaseIT extends PostgresITBase {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    private OperatorCaseRepository repository;
    private OperatorCaseService service;

    @BeforeEach
    void setUp() {
        JdbcClient jdbc = JdbcClient.create(controlDataSource());
        repository = new PostgresOperatorCaseRepository(jdbc);
        service = new OperatorCaseService(repository, () -> NOW);
        // AM5 表不在基座 TRUNCATE 清单（AM5 表惯例自清）；notify_outbox.case_id 引用本表，
        // 基座清场先行（超类 @BeforeEach）后此处再清 operator_case 即无 FK 残留
        adminJdbc.sql("DELETE FROM operator_case").update();
    }

    // ------------------------------------------------------------------ 幂等合并（连续三次聚集）

    @Test
    void threeOccurrencesAggregateIntoSingleRow() {
        UUID first = service.openOrMerge(draft("fp-1", "k1")).caseId();
        service.openOrMerge(draft("fp-1", "k2"));
        OperatorCaseService.MergeOutcome third = service.openOrMerge(draft("fp-1", "k3"));

        assertThat(third.caseId()).isEqualTo(first);
        assertThat(count("operator_case")).as("同 fingerprint 三次发生只有一行").isEqualTo(1);

        OperatorCase c = repository.findById(first).orElseThrow();
        assertThat(c.revision()).isEqualTo(3);
        assertThat(c.activities()).hasSize(3);
        assertThat(c.audits()).hasSize(3);
        assertThat(c.status()).isEqualTo(CaseStatus.OPEN);
        // jsonb 往返：审计行形状 = P4 mock audits[]（{at,actor,action,revision,key}）
        assertThat(c.audits().get(0).action()).isEqualTo("CASE_CREATED");
        assertThat(c.audits().get(2).action()).isEqualTo("CASE_MERGED");
        assertThat(c.audits().get(2).key()).isEqualTo("k3");
    }

    // ------------------------------------------------------------------ 并发认领 CAS 恰一人成功

    @Test
    void concurrentClaimExactlyOneSucceeds() throws Exception {
        UUID caseId = service.openOrMerge(draft("fp-1", "k1")).caseId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> a = pool.submit(() -> claimAfterLatch(start, caseId, "worker-a"));
            Future<?> b = pool.submit(() -> claimAfterLatch(start, caseId, "worker-b"));
            start.countDown();
            // 恰一人无异常成功（另一人 CaseRevisionConflictException 被吞）
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);

            long successCount = adminJdbc.sql("""
                            SELECT count(*) FROM operator_case
                             WHERE id = :id AND status = 'ACKED' AND revision = 2
                            """).param("id", caseId).query(Long.class).single();
            assertThat(successCount).as("恰一人认领成功落 revision=2").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private void claimAfterLatch(CountDownLatch start, UUID caseId, String worker) {
        try {
            start.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        try {
            service.claim(caseId, 1, worker, "claim-" + worker);
        } catch (CaseRevisionConflictException expected) {
            // 败者面：零副作用（恰一人成功由下方断言收口）
        }
    }

    // ------------------------------------------------------------------ SLA 升级恰一次

    @Test
    void escalationReplayBumpsExactlyOnce() {
        UUID caseId = service.openOrMerge(draft("fp-1", "k1")).caseId();

        service.escalate(caseId, "system", "k-esc");
        service.escalate(caseId, "system", "k-esc");

        assertThatNoException().isThrownBy(() -> service.escalate(caseId, "system", "k-esc"));
        OperatorCase c = repository.findById(caseId).orElseThrow();
        assertThat(c.revision()).as("同 key 重放只 bump 一次").isEqualTo(2);
        assertThat(c.audits().stream().filter(a -> a.action().equals("SLA_ESCALATED"))).hasSize(1);

        // 拒绝面：结案后升级为非法迁移（RESOLVED 吸收态）
        service.resolve(caseId, 2, "WONT_FIX", "误报", "operator-a", "k-resolve");
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> service.escalate(caseId, "system", "k-esc-2"));

        // jsonb 往返：resolution 结构化结案原因完整落行
        OperatorCase closed = repository.findById(caseId).orElseThrow();
        assertThat(closed.status()).isEqualTo(CaseStatus.RESOLVED);
        assertThat(closed.resolution().code()).isEqualTo("WONT_FIX");
        assertThat(closed.resolution().note()).isEqualTo("误报");
    }

    // ------------------------------------------------------------------ 种子

    private CaseDraft draft(String fingerprint, String key) {
        return new CaseDraft("tenant-1", fingerprint, "Claim 冲突", "P0", "CLAIM_CONFLICT",
                UUID.randomUUID(), "root-cause", "payment-failure",
                Digest.sha256Of("snapshot-" + fingerprint), 13,
                List.of("evidence#81"), NOW.plusSeconds(600), NOW.plusSeconds(3600),
                key);
    }
}
