package com.objwww.pr.publisher.it;

import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.interfaces.webhook.WebhookController;
import com.objwww.pr.shared.AttemptStatus;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.RunState;
import com.objwww.pr.shared.StepState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-10 T0 快照含恶意 tar 内容（B22/UT-09 链路侧）：安全解包拒绝，不进评审流程。
 *
 * <p>两个防线位置都演示：
 * <ol>
 *   <li>T0（InboxProcessor → IntakeService.dispatch → SnapshotService.prepare，M1 两段式后
 *       由 Processor 同步驱动）：拒绝发生在建 Run 之前——dispatch 上抛，Processor 按
 *       EX-11 语义退避重试，重试永远不会变合法（载荷不可变），耗尽转 DEAD_LETTER；
 *       全程零 Run/Step/事件，仅 webhook 原文的接收登记（T0 前的最小记录）；</li>
 *   <li>Step 执行期（ReviewStepExecutor 从 CAS 重装快照再解包）：拒绝 → Worker 归类
 *       SECURITY_REJECTION 不可重试 → Step FAILED + Run FAILED + SAFETY_REJECTED 落账。</li>
 * </ol>
 */
class EX10MaliciousTarballIT extends PostgresITBase {

    private static final String SECRET = "it-webhook-secret";
    private static final String HEAD_SHA = "bad" + "0".repeat(37);

    private ItHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ItHarness(casDir, null);
    }

    @Test
    void maliciousTarballRejectedAtT0BeforeAnyRun() {
        // 路径穿越样本："../pwned.txt"（SafeTarExtractor 拒绝任何含 ".." 段）
        byte[] malicious = ItTarballs.tarGz(out -> ItTarballs.file(out,
                ItTarballs.GH_PREFIX + "../pwned.txt", "pwned"));
        harness.sourcePort.registerSnapshot(HEAD_SHA, malicious)
                .registerDiff(ItHarness.BASE_SHA, HEAD_SHA, "diff");

        // M1 两段式入口：202 受理落 inbox，T0 在 Processor 段同步执行
        WebhookController controller = new WebhookController(SECRET, harness.inboxRepo);
        byte[] body = ItHarness.webhookBody(3011L, "objwww/mall", 41, HEAD_SHA, "opened");
        var response = controller.handle(body, ItHarness.sign(SECRET, body),
                "pull_request", "ex10-d1");
        assertThat(response.getStatusCode().value()).isEqualTo(202);

        StubPrMetadataPort metadata = new StubPrMetadataPort()
                .remote("open", false, false, HEAD_SHA, ItHarness.BASE_SHA,
                        java.time.Instant.parse("2025-06-01T12:00:00Z"));

        // 首次处理：T0 安全拒绝上抛 → RETRY_WAIT；不进评审流程
        harness.newInboxProcessor(metadata).runOnce();
        assertThat(harness.inboxRepo.findByDeliveryId("ex10-d1").orElseThrow().getState().name())
                .isEqualTo("RETRY_WAIT");
        assertZeroReviewRows();

        // 耗尽重试预算（载荷不可变，重试永不成功）→ DEAD_LETTER；仍零评审行
        for (int i = 0; i < 4; i++) {
            adminJdbc.sql("UPDATE webhook_inbox SET next_retry_at = now() - interval '1 second'"
                    + " WHERE delivery_id = 'ex10-d1'").update();
            harness.newInboxProcessor(metadata).runOnce();
        }
        assertThat(harness.inboxRepo.findByDeliveryId("ex10-d1").orElseThrow().getState().name())
                .isEqualTo("DEAD_LETTER");
        assertZeroReviewRows();
    }

    private void assertZeroReviewRows() {
        // 不进评审流程：零 Run/Step/事件；仅 webhook 原文的接收登记（T0 前的最小记录）
        assertThat(count("pr_subject")).isZero();
        assertThat(count("review_run")).isZero();
        assertThat(count("run_step")).isZero();
        assertThat(count("execution_event")).isZero();
        assertThat(count("artifact")).isEqualTo(1); // WEBHOOK_PAYLOAD
    }

    @Test
    void maliciousTarballRejectedAtStepExecutionFailsSafely() {
        // 绕过 T0 直接建 Run（digest 手工给定），恶意 tar 塞进 CAS 冒充快照内容
        Digest snapshotDigest = Digest.sha256Of("ex10-malicious-snapshot");
        Digest diffDigest = Digest.sha256Of("ex10-diff");
        byte[] malicious = ItTarballs.tarGz(out -> ItTarballs.file(out,
                ItTarballs.GH_PREFIX + "../pwned.txt", "pwned"));
        harness.casStore.putIfAbsent(snapshotDigest, malicious);
        harness.casStore.putIfAbsent(diffDigest, "diff".getBytes());

        ReviewRun run = harness.runIntakeDirect(
                ItHarness.prEvent("ex10-d2", 3012L, "objwww/mall", 42, HEAD_SHA, "opened"),
                diffDigest, snapshotDigest);

        // Worker 真实消费：装 input → 安全解包拒绝 → 归类 SECURITY_REJECTION（不可重试）
        harness.newWorker("worker-1").runOnce();

        var step = harness.stepRepo.findByRunId(run.getId()).get(0);
        assertThat(step.getState()).isEqualTo(StepState.FAILED);
        assertThat(harness.attemptRepo.findByStepId(step.getId()).get(0).getStatus())
                .isEqualTo(AttemptStatus.FAILED_TERMINAL);
        assertThat(harness.runRepo.findById(run.getId()).orElseThrow().getState())
                .isEqualTo(RunState.FAILED);
        assertThat(harness.eventsOf(run.getId()).stream()
                .anyMatch(e -> e.eventType() == ExecutionEventType.SAFETY_REJECTED)).isTrue();
        assertThat(count("outbox_command")).isZero(); // 安全拒绝不产发布意图
        assertThat(harness.modelClient.requests()).isEmpty(); // 模型从未被调用
    }
}
