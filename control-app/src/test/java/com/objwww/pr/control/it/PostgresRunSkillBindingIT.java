package com.objwww.pr.control.it;

import com.objwww.pr.control.infrastructure.persistence.PostgresSkillRunBindingRepository;
import com.objwww.pr.control.release.domain.model.SkillRunBinding;
import com.objwww.pr.control.release.domain.repository.SkillRunBindingRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CL-05 Skill 持久绑定真 PG 屏障（告警-Agent闭环修复 v1 §4.1/§10"Skill 唯一绑定
 * 迁移兼容"）：主键 (run,role,epoch) insert-if-absent 恰一胜者、NONE 必持久化、
 * 行落库后无改写面、CHECK 约束拒绝形状漂移。并发以屏障+条件写裁决，不以 sleep
 * 推测时序。
 */
class PostgresRunSkillBindingIT extends PostgresITBase {

    private static final Instant NOW = Instant.parse("2026-09-13T02:00:00Z");
    private static final String RELEASE = Digest.sha256Of("release-cl05").value();

    private SkillRunBindingRepository bindings;

    @BeforeEach
    void setUp() {
        bindings = new PostgresSkillRunBindingRepository(
                JdbcClient.create(controlDataSource()));
    }

    private SkillRunBinding selected(UUID runId, long epoch, String digest) {
        return new SkillRunBinding(runId, "primary", epoch, SkillRunBinding.SELECTED,
                digest, RELEASE, "skill-selector.v1", null, NOW);
    }

    @Test
    @DisplayName("insert-if-absent：首次落行；同键重放恒败；NONE 持久化可回读")
    void insertIfAbsentAndNonePersistence() {
        UUID runId = UUID.randomUUID();
        String digest = Digest.sha256Of("skill-a").value();
        assertThat(bindings.insertIfAbsent(selected(runId, 0, digest))).isTrue();
        assertThat(bindings.insertIfAbsent(selected(runId, 0,
                Digest.sha256Of("skill-b").value())))
                .as("同 (run,role,epoch) 撞主键恒败（单写者）").isFalse();
        assertThat(bindings.find(runId, "primary", 0)).hasValueSatisfying(row -> {
            assertThat(row.assetDigest()).isEqualTo(digest);
            assertThat(row.selected()).isTrue();
        });

        SkillRunBinding none = new SkillRunBinding(UUID.randomUUID(), "primary", 0,
                SkillRunBinding.NONE, null, RELEASE, "skill-selector.v1", null, NOW);
        assertThat(bindings.insertIfAbsent(none)).isTrue();
        assertThat(bindings.find(none.runId(), "primary", 0))
                .hasValueSatisfying(row -> assertThat(row.selected()).isFalse());
    }

    @Test
    @DisplayName("真并发双首次：恰一 insert 成功，双方回读同一胜者事实")
    void concurrentFirstSelectExactlyOneWinner() throws Exception {
        UUID runId = UUID.randomUUID();
        String digestA = Digest.sha256Of("winner").value();
        String digestB = Digest.sha256Of("loser").value();
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successes = new AtomicInteger();
        List<Callable<SkillRunBinding>> racers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String digest = i == 0 ? digestA : digestB;
            racers.add(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                boolean inserted = bindings.insertIfAbsent(selected(runId, 3, digest));
                if (inserted) {
                    successes.incrementAndGet();
                }
                return bindings.find(runId, "primary", 3).orElseThrow();
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<SkillRunBinding>> results = pool.invokeAll(racers);
            assertThat(successes.get()).as("恰一 insert 胜者").isEqualTo(1);
            // 真并发下两 racer 谁先落行不定——裁决面是"双方回读同一事实"而非指定胜者
            String winner = results.get(0).get().assetDigest();
            assertThat(winner).as("胜者必为两 racer 之一").isIn(digestA, digestB);
            assertThat(results.get(1).get().assetDigest())
                    .as("败者回读胜者事实").isEqualTo(winner);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("DB CHECK 屏障：NONE 带 digest / SELECTED 缺 digest / 非法状态一律拒绝")
    void checkConstraintsRejectShapeDrift() {
        UUID runId = UUID.randomUUID();
        assertThat(jdbcInsert(runId, "NONE", Digest.sha256Of("x").value())).isFalse();
        assertThat(jdbcInsert(runId, "SELECTED", null)).isFalse();
        assertThat(jdbcInsert(runId, "MAYBE", null)).isFalse();
        assertThat(bindings.find(runId, "primary", 0)).isEmpty();
    }

    /** 绕过域类型直写（CHECK 面裁决用）；false = 数据库拒绝 */
    private boolean jdbcInsert(UUID runId, String status, String digest) {
        try {
            JdbcClient.create(controlDataSource()).sql("""
                    insert into rca_run_skill_binding (run_id, role_id, config_epoch,
                        selection_status, asset_digest, release_digest, selector_version,
                        source_command_id, created_at)
                    values (:run_id, 'primary', 0, :status, :digest, :release,
                        'skill-selector.v1', null, :created_at)
                    """)
                    .param("run_id", runId)
                    .param("status", status)
                    .param("digest", digest)
                    .param("release", RELEASE)
                    .param("created_at", java.sql.Timestamp.from(NOW))
                    .update();
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return false;
        }
    }
}
