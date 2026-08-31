package com.objwww.pr.control.it;

import com.objwww.pr.control.domain.model.PRRevision;
import com.objwww.pr.control.domain.model.PRSubject;
import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.model.ReviewFinding;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.model.RunMode;
import com.objwww.pr.control.infrastructure.persistence.PostgresPRRevisionRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresPRSubjectRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReviewFindingRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReviewRunRepository;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.FindingState;
import com.objwww.pr.shared.RevisionFingerprint;
import com.objwww.pr.shared.RunState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgresReviewFindingRepository 组件测试（评审对账缺口）：
 * 同 (review_run_id, fingerprint) 二次插入 → ON CONFLICT DO NOTHING，表内恰好 1 行
 * 且保留首行——T2 重放/重试重复登记 finding 不产生重复行的直接断言（v2.2 §5，
 * 此前只有 EX07 经整笔 T2 间接覆盖）。
 *
 * <p>命名说明：本类是 IT（需 Testcontainers PG 16），按任务约定以 *Test 命名；
 * 无 docker 环境由 PostgresITBase 的 disabledWithoutDocker 自动跳过，
 * 本机 mvn test 不受影响，留待 195（docker 环境）执行。
 */
class PostgresReviewFindingRepositoryTest extends PostgresITBase {

    private PostgresReviewFindingRepository repo;
    private UUID runId;
    private UUID revisionId;

    @BeforeEach
    void setUp() {
        repo = new PostgresReviewFindingRepository(controlJdbc);
        seedRunGraph();
    }

    /** 经 control 角色真实仓储种 subject→revision→run 最小图（review_finding 的 FK 前提） */
    private void seedRunGraph() {
        Instant now = Instant.now();
        UUID subjectId = UUID.randomUUID();
        new PostgresPRSubjectRepository(controlJdbc).save(new PRSubject(
                subjectId, 987L, 12345L, "org/repo", 7,
                PrSubjectState.OPEN, false, false,
                null, "m1-policy-v1", 0, 1, 0, null, now, 0, 0, now, now));
        revisionId = UUID.randomUUID();
        new PostgresPRRevisionRepository(controlJdbc).insert(new PRRevision(
                revisionId, subjectId, "headsha1", "main", "basesha1", null,
                Digest.sha256Of("diff-1"), null,
                new RevisionFingerprint(Digest.sha256Of("fp-rev-1").value()), now, now));
        runId = UUID.randomUUID();
        new PostgresReviewRunRepository(controlJdbc).save(new ReviewRun(
                runId, revisionId, null, runId,
                Digest.sha256Of("run-key-1"), "trigger-1", RunMode.NORMAL,
                "m1-policy-v1", "m1-prompt-v1", "m1-toolset-v1",
                null, RunState.CREATED, false,
                null, null, null, 0, now, now, null));
    }

    /** 同 (run_id, fingerprint) 插两次 → 恰好 1 行且是首行（DO NOTHING 不覆盖） */
    @Test
    void duplicateFingerprintInsertIsNoOpAndKeepsFirstRow() {
        Digest fp = Digest.sha256Of("finding-fp-1");
        Instant now = Instant.now();
        ReviewFinding first = new ReviewFinding(UUID.randomUUID(), runId, revisionId,
                fp, "rule-1", "MAJOR", "src/A.java", 3, 3, null, FindingState.PENDING, now);
        repo.insert(first);

        // T2 重放语义：同 (run_id, fingerprint) 换个 id 再插 → 空操作
        repo.insert(new ReviewFinding(UUID.randomUUID(), runId, revisionId,
                fp, "rule-1", "MAJOR", "src/A.java", 3, 3, null, FindingState.PENDING, now));

        assertThat(count("review_finding")).isEqualTo(1);
        List<ReviewFinding> found = repo.findByRunId(runId);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getId()).isEqualTo(first.getId()); // 保留首行，不被二次插入覆盖

        // 对照组：不同 fingerprint 正常落第二行（唯一约束粒度是 (run, fingerprint) 而非 run）
        repo.insert(new ReviewFinding(UUID.randomUUID(), runId, revisionId,
                Digest.sha256Of("finding-fp-2"), "rule-2", "MINOR", "src/B.java",
                1, 1, null, FindingState.PENDING, now));
        assertThat(count("review_finding")).isEqualTo(2);
    }
}
