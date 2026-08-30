package com.objwww.pr.publisher.it;

import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.service.SequenceLease;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CT-01 序列原子分配：20 线程并发领同 PR 序号——无重复、无跳号；
 * uq(aggregate_key, aggregate_sequence) 是最后防线（本用例压的是 UPDATE...RETURNING 行锁路径）。
 */
class CT01SequenceAllocationIT extends PostgresITBase {

    private static final int THREADS = 20;

    private ItHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ItHarness(casDir, null);
    }

    @Test
    void concurrentAllocationIsGaplessAndUnique() throws Exception {
        // 建 subject（T1 直驱，digest 手工给定；CT-01 不涉及 CAS 内容）
        ReviewRun run = harness.runIntakeDirect(
                ItHarness.prEvent("ct01-d1", 1001L, "objwww/mall", 7,
                        "head" + "1".repeat(36), "opened"),
                Digest.sha256Of("ct01-diff"), Digest.sha256Of("ct01-snapshot"));
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(1001L, 7)
                .orElseThrow().getId();
        assertThat(run).isNotNull();

        // 20 线程同闸门并发领号（各自独立连接/自动提交，行锁串行化临界点）
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<SequenceLease>> futures = java.util.stream.IntStream.range(0, THREADS)
                    .mapToObj(i -> pool.submit(() -> {
                        gate.await();
                        return harness.sequenceAllocator.allocate(subjectId);
                    }))
                    .toList();
            gate.countDown();
            List<Long> sequences = new java.util.ArrayList<>(THREADS);
            for (Future<SequenceLease> f : futures) {
                sequences.add(f.get(30, TimeUnit.SECONDS).sequence());
            }

            // 无重复无跳号：恰为 1..20
            assertThat(sequences.stream().sorted().toList())
                    .isEqualTo(java.util.stream.LongStream.rangeClosed(1, THREADS).boxed().toList());

            // subject 计数器/游标终态：next=21、epoch=1（首次换届 0→1）、resolved=0
            assertThat(subjectCursor(subjectId)).containsExactly(1L, THREADS + 1L, 0L);
        } finally {
            pool.shutdownNow();
        }
    }
}
