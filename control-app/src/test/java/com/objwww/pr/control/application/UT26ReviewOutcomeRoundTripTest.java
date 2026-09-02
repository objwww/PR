package com.objwww.pr.control.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.domain.ai.MockModelGateway;
import com.objwww.pr.control.domain.ai.ModelResult;
import com.objwww.pr.control.domain.ai.ModelRouteIdentity;
import com.objwww.pr.control.domain.ai.TokenUsage;
import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.model.RunStep;
import com.objwww.pr.control.domain.model.WorkItem;
import com.objwww.pr.control.domain.review.FindingMapper;
import com.objwww.pr.control.domain.review.ReviewAgentLoop;
import com.objwww.pr.control.domain.review.ReviewBudget;
import com.objwww.pr.control.domain.review.ReviewOutcome;
import com.objwww.pr.control.domain.service.CheckpointResumeService;
import com.objwww.pr.shared.snapshot.SafeTarExtractor;
import com.objwww.pr.control.domain.tool.PolicyEngine;
import com.objwww.pr.control.domain.tool.ToolRegistry;
import com.objwww.pr.control.support.OrchestratorFixture;
import com.objwww.pr.control.support.TestTarballs;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.ExecutionEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-26（回指 I19，方案 §11 L1）：ReviewOutcome CAS 往返重建一致性——
 * 首次执行产出 outcome A（findings + stats + token_usage + modelResponse 全字段），
 * findings JSON 与模型原文落 CAS；续跑命中 checkpoint，从 CAS 读出重建 outcome B，
 * 必须逐字段等于 A（重建 == 原 outcome，续跑路径与首跑路径对 T2 完全等价）。
 *
 * <p>现有覆盖核实：ReviewStepExecutorTest.secondAttemptReusesCheckpointWithoutCallingModel
 * 只断言 output digest 相等与零模型调用，未断重建结果的字段级一致性——本类补全。
 */
class UT26ReviewOutcomeRoundTripTest {

    private static final Digest SNAPSHOT_DIGEST = Digest.sha256Of("snap-ut26");
    private static final Digest DIFF_DIGEST = Digest.sha256Of("diff-ut26");
    private static final String MODEL_OUTPUT = """
            [{"file":"a/Foo.java","line":90,"existing_code":"int a = 1;","rule":"magic-number","severity":"MINOR","message":"魔法数·üñï"},
            {"file":"a/Foo.java","line":50,"existing_code":"int x = 0/1;","rule":"div-zero","severity":"MAJOR","message":"除零"}]""";
    private static final TokenUsage USAGE = new TokenUsage(123, 45, 168);

    private OrchestratorFixture fx;
    private MockModelGateway modelClient;
    private ReviewStepExecutor executor;
    private RunStep step;
    private WorkItem item;

    @BeforeEach
    void setUp() {
        fx = new OrchestratorFixture();
        modelClient = new MockModelGateway();
        ReviewRun run = fx.orchestrator.runIntake(new IntakeCommand(987L, 12345L, "org/repo", 7,
                PrSubjectState.OPEN, false, false, "head1", "main", "base1", null,
                DIFF_DIGEST, SNAPSHOT_DIGEST,
                "m0-policy-v1", "m0-prompt-v1", "m0-toolset-v1", "d-1", null));
        step = fx.steps.findByRunId(run.getId()).get(0);
        item = fx.workItems.findByStepId(step.getId()).orElseThrow();
        Instant now = Instant.now();
        item.leaseTo("ut26-worker", now.plusSeconds(600), now);
        ObjectMapper mapper = new ObjectMapper();
        var resume = new CheckpointResumeService(fx.checkpoints, fx.artifacts, fx.cas, fx.ledger, mapper);
        var writer = new CheckpointWriter(fx.artifacts, fx.checkpoints, fx.ledger);
        executor = new ReviewStepExecutor(fx.runs, fx.revisions, fx.cas, fx.artifacts,
                new SafeTarExtractor(10000, 100 * 1024 * 1024, 1024L * 1024 * 1024),
                new ReviewAgentLoop(modelClient, new FindingMapper(),
                        new PolicyEngine(new ToolRegistry())),
                ReviewBudget.DEFAULT, mapper, resume, writer, fx.ledger,
                requestedModel -> java.util.Optional.of(new ModelRouteIdentity(
                        "mock-provider", requestedModel, "v1")));
        byte[] tarball = TestTarballs.tarGz(out -> TestTarballs.file(out,
                TestTarballs.GH_PREFIX + "a/Foo.java", "int a = 1;\nint x = 0/1;\n"));
        fx.cas.putIfAbsent(SNAPSHOT_DIGEST, tarball);
        fx.cas.putIfAbsent(DIFF_DIGEST, "diff --git a/Foo.java".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void resumedOutcomeIsFieldByFieldIdenticalToOriginal() {
        modelClient.enqueue(new ModelResult(MODEL_OUTPUT, USAGE, "mock-model"));

        StepOutcome first = executor.execute(new StepExecutionContext(item, step, java.util.UUID.randomUUID()), () -> true);
        StepOutcome second = executor.execute(new StepExecutionContext(item, step, java.util.UUID.randomUUID()), () -> true);

        // 第二跑命中 checkpoint：零模型调用 + REUSED 事件
        assertThat(modelClient.requests()).hasSize(1);
        assertThat(fx.events.all()).anySatisfy(e ->
                assertThat(e.eventType()).isEqualTo(ExecutionEventType.CHECKPOINT_REUSED));

        StepOutcome.Succeeded s1 = (StepOutcome.Succeeded) first;
        StepOutcome.Succeeded s2 = (StepOutcome.Succeeded) second;
        assertThat(s2.outputArtifactDigest()).isEqualTo(s1.outputArtifactDigest());

        // 核心断言：CAS 往返重建 outcome 逐字段 == 原 outcome（record 深相等：
        // findings 全列含 fingerprint、stats 五计数、token_usage、modelResponse）
        ReviewOutcome original = s1.reviewOutcome();
        ReviewOutcome rebuilt = s2.reviewOutcome();
        assertThat(rebuilt).isEqualTo(original);

        // 防"空真"（两边都是空 outcome 导致恒等通过）：首跑产出必须非平凡
        assertThat(original.findings()).hasSize(2);
        assertThat(original.findings().get(0).lineStart()).isEqualTo(1); // 行号工程映射已生效
        assertThat(original.findings().get(0).message()).isEqualTo("魔法数·üñï"); // Unicode 逐字节保留
        assertThat(original.findings().get(1).lineStart()).isEqualTo(2);
        assertThat(original.tokenUsage()).isEqualTo(USAGE);
        assertThat(original.candidateFiles()).isEqualTo(1);
        assertThat(original.selectedFiles()).isEqualTo(1);
        assertThat(original.truncatedFiles()).isZero();
        assertThat(original.droppedFindings()).isZero();
        assertThat(original.malformedFindings()).isZero();
        assertThat(original.modelResponse()).isEqualTo(MODEL_OUTPUT);
    }
}
