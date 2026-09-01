package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.application.OutboxWriter;
import com.objwww.pr.control.application.RepairDispatchService;
import com.objwww.pr.control.application.RepairPlanner;
import com.objwww.pr.control.domain.model.RepairCandidate;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.domain.repository.RepairRequestRepository;
import com.objwww.pr.control.domain.repository.ReviewRunRepository;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.control.domain.service.RepairCommandFactory;
import com.objwww.pr.control.infrastructure.cas.LocalCasArtifactStore;
import com.objwww.pr.control.infrastructure.persistence.PostgresArtifactRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresExecutionEventRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresOutboxCommandRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRepairRequestRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReviewRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresSequenceAllocator;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CT-27（docs/M2-技术方案.md §11 L2 表，回指 §4.3 RepairPlanner 公平扫描 / EX-29）。
 *
 * <p>场景：100 个 repair_request（70 好单 + 10 坏 payload + 10 CAS 缺失 + 10 持续瞬时失败，
 * 按 created_at 均匀穿插），RepairPlanner 以 LIMIT=10 多轮扫描真库。
 *
 * <p>断言：全部被扫描处理、无尾部饿死（每轮处理的恰是 findReady 当前最老的 10 个，
 * 10 轮后扫描面清空）；坏 payload/CAS 缺失立即 FAILED_TERMINAL 且不热循环（追加轮
 * handled=0、REPAIR_FAILED 事件不翻倍）；持续失败到预算耗尽（attempt 4→5）进终态 + 事件。
 *
 * <p>取证：repair_request.state / attempt_count / next_attempt_at / last_error；
 * execution_event.event_type + payload.reason；review_run.run_mode='REPAIR' 计数；
 * outbox_command 中 repair 命令计数。
 */
class CT27RepairScanFairnessIT extends PostgresITBase {

    private static final int TOTAL = 100;
    private static final int LIMIT = 10;

    @TempDir
    Path casDir;

    private final Set<String> flakyDigests = new HashSet<>();
    private PostgresRepairRequestRepository requests;
    private RepairPlanner planner;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper();
        ArtifactStore cas = new LocalCasArtifactStore(casDir);
        requests = new PostgresRepairRequestRepository(controlJdbc);
        OutboxWriter outbox = new OutboxWriter(new PostgresOutboxCommandRepository(controlJdbc),
                new PostgresSequenceAllocator(controlJdbc), cas,
                new PostgresArtifactRepository(controlJdbc));
        RepairDispatchService dispatcher = new TxDispatchService(requests,
                new PostgresReviewRunRepository(controlJdbc), outbox,
                new ExecutionLedger(new PostgresExecutionEventRepository(controlJdbc, om)));
        planner = new RepairPlanner(requests, new FlakyStore(cas),
                new RepairCommandFactory(om), dispatcher, LIMIT, 0);
    }

    @Test
    void hundredRequestsAllScannedWithFailuresConvergingToTerminal() {
        seedHundredRequests();

        int rounds = 0;
        int handledTotal = 0;
        while (true) {
            // 本轮应处理的恰是扫描面当前最老的一批（ORDER BY created_at LIMIT 10）
            List<UUID> batch = requests.findReady(LIMIT).stream()
                    .map(RepairCandidate::requestId).toList();
            if (batch.isEmpty()) {
                break;
            }
            handledTotal += planner.runOnce();
            rounds++;
            assertThat(rounds).as("防死循环护栏").isLessThanOrEqualTo(TOTAL / LIMIT + 5);
            // 本轮批全部离开扫描面 → 无跳过、无尾部饿死
            assertThat(readyIds()).doesNotContain(batch.toArray(UUID[]::new));
        }

        assertThat(rounds).isEqualTo(TOTAL / LIMIT);
        assertThat(handledTotal).isEqualTo(TOTAL);
        assertThat(requests.findReady(1000)).isEmpty();

        // 70 好单：DISPATCHED + REPAIR Run + repair 命令 + REPAIR_DISPATCHED 事件
        assertThat(countWhere("repair_request", "state='DISPATCHED'")).isEqualTo(70);
        assertThat(countWhere("review_run", "run_mode='REPAIR'")).isEqualTo(70);
        assertThat(adminJdbc.sql("""
                SELECT count(*) FROM outbox_command o JOIN review_run r ON r.id = o.review_run_id
                 WHERE r.run_mode = 'REPAIR'
                """).query(Long.class).single()).isEqualTo(70);
        assertThat(countWhere("execution_event", "event_type='REPAIR_DISPATCHED'")).isEqualTo(70);

        // 30 失败单全部 FAILED_TERMINAL + 恰一条 REPAIR_FAILED 事件，reason 三分类各 10
        assertThat(countWhere("repair_request", "state='FAILED_TERMINAL'")).isEqualTo(30);
        assertThat(countWhere("execution_event", "event_type='REPAIR_FAILED'")).isEqualTo(30);
        assertThat(countWhere("repair_request", "last_error='BAD_DESIRED_PAYLOAD'")).isEqualTo(10);
        assertThat(countWhere("repair_request", "last_error='DESIRED_PAYLOAD_MISSING'")).isEqualTo(10);
        assertThat(countWhere("repair_request", "last_error='PLANNER_TRANSIENT'")).isEqualTo(10);
        // 坏 payload/CAS 缺失是确定性失败：不烧重试预算（attempt_count 不变）
        assertThat(countWhere("repair_request",
                "last_error IN ('BAD_DESIRED_PAYLOAD','DESIRED_PAYLOAD_MISSING') AND attempt_count=0"))
                .isEqualTo(20);
        // 预算耗尽：attempt 4/5 的瞬时失败经 RepairDispatchService.fail 走 markRetryWait
        // 预算翻转分支（评审裁定：终态 attempt_count 打满 5/5、退避清空）——终态 + 事件达成
        assertThat(countWhere("repair_request",
                "last_error='PLANNER_TRANSIENT' AND attempt_count=5 AND next_attempt_at IS NULL"))
                .isEqualTo(10);

        // 不热循环：追加轮零处理，事件不翻倍
        assertThat(planner.runOnce()).isZero();
        assertThat(countWhere("execution_event", "event_type='REPAIR_FAILED'")).isEqualTo(30);
    }

    /** i%10==7 坏 payload，==8 CAS 缺失，==9 持续瞬时失败（attempt 4/5），其余好单；均匀穿插。 */
    private void seedHundredRequests() {
        ArtifactStore cas = new LocalCasArtifactStore(casDir);
        for (int i = 0; i < TOTAL; i++) {
            RepairSeed seed = seedRepairScope("ct27-" + i);
            String content;
            switch (i % 10) {
                case 7 -> content = "not-json-" + i;               // 坏 payload：CAS 里就是非 JSON
                case 8 -> content = "gone-" + i;                   // CAS 缺失：不落任何内容
                default -> content = "{\"name\":\"ai-review\",\"i\":" + i + "}";
            }
            Digest digest = Digest.sha256Of(content);
            UUID op = seedConfirmedCommand(seed, "CREATE_CHECK", content);
            if (i % 10 != 8) {
                cas.putIfAbsent(digest, content.getBytes(StandardCharsets.UTF_8));
            }
            if (i % 10 == 9) {
                flakyDigests.add(digest.value());
            }
            UUID resource = seedResource(seed, op, "CHECK_RUN", "MISSING", "ct27-r" + i);
            boolean budgetTail = i % 10 == 9;
            seedRepairRequest(resource, "CHECK_RUN", "AUTO",
                    budgetTail ? "RETRY_WAIT" : "PENDING",
                    budgetTail ? 4 : 0, 5, TOTAL - i);
        }
    }

    private List<UUID> readyIds() {
        return requests.findReady(1000).stream().map(RepairCandidate::requestId).toList();
    }

    private long countWhere(String table, String where) {
        return adminJdbc.sql("SELECT count(*) FROM " + table + " WHERE " + where)
                .query(Long.class).single();
    }

    /** 指定 digest 的 CAS 读抛瞬时故障（模拟 FS 抖动），驱动 PLANNER_TRANSIENT 路径。 */
    private final class FlakyStore implements ArtifactStore {
        private final ArtifactStore delegate;

        FlakyStore(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public String putIfAbsent(Digest digest, byte[] content) {
            return delegate.putIfAbsent(digest, content);
        }

        @Override
        public boolean exists(Digest digest) {
            return delegate.exists(digest);
        }

        @Override
        public Optional<byte[]> get(Digest digest) {
            if (flakyDigests.contains(digest.value())) {
                throw new RuntimeException("fs down");
            }
            return delegate.get(digest);
        }
    }

    /** IT 无 Spring 容器：以 TransactionTemplate 复现 @Transactional 的 per-request 短事务语义。 */
    private static final class TxDispatchService extends RepairDispatchService {
        TxDispatchService(RepairRequestRepository requests, ReviewRunRepository runs,
                          OutboxWriter outbox, ExecutionLedger ledger) {
            super(requests, runs, outbox, ledger);
        }

        @Override
        public boolean dispatch(UUID requestId, RepairCommandFactory.Prepared prepared) {
            return Boolean.TRUE.equals(controlTx.execute(tx -> super.dispatch(requestId, prepared)));
        }

        @Override
        public void fail(RepairCandidate candidate, boolean retryable, String error) {
            controlTx.executeWithoutResult(tx -> super.fail(candidate, retryable, error));
        }

        @Override
        public boolean projectRunOutcome(UUID requestId) {
            return Boolean.TRUE.equals(controlTx.execute(tx -> super.projectRunOutcome(requestId)));
        }
    }
}
