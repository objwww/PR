package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.ai.MockModelGateway;
import com.objwww.pr.control.domain.ai.ModelCallFailedException;
import com.objwww.pr.control.domain.ai.ModelRouteIdentity;
import com.objwww.pr.control.domain.model.ArtifactType;
import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.model.RunStep;
import com.objwww.pr.control.domain.model.WorkItem;
import com.objwww.pr.control.domain.review.FindingMapper;
import com.objwww.pr.control.domain.review.ModelOutputParseException;
import com.objwww.pr.control.domain.review.ReviewAgentLoop;
import com.objwww.pr.control.domain.review.ReviewBudget;
import com.objwww.pr.control.domain.service.CheckpointResumeService;
import com.objwww.pr.control.domain.snapshot.SafeTarExtractor;
import com.objwww.pr.control.domain.snapshot.SecurityRejectionException;
import com.objwww.pr.control.domain.tool.PolicyEngine;
import com.objwww.pr.control.domain.tool.ToolRegistry;
import com.objwww.pr.control.support.OrchestratorFixture;
import com.objwww.pr.control.support.TestTarballs;
import com.objwww.pr.shared.Digest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ReviewStepExecutor（T10 REVIEW 执行器）：装 input（CAS + 安全解包）→ ReviewAgentLoop
 * → findings JSON 落 CAS + artifact 登记 → StepOutcome。异常上抛由 Worker 归类（见 WorkItemWorkerTest）。
 */
class ReviewStepExecutorTest {

    private static final Digest SNAPSHOT_DIGEST = Digest.sha256Of("snap");
    private static final Digest DIFF_DIGEST = Digest.sha256Of("diff");

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
        item.leaseTo("test-worker", now.plusSeconds(600), now);
        executor = executorWith(new ReviewAgentLoop(modelClient, new FindingMapper(),
                new PolicyEngine(new ToolRegistry())), "v1");
    }

    private ReviewStepExecutor executorWith(ReviewAgentLoop loop, String contractVersion) {
        ObjectMapper mapper = new ObjectMapper();
        var resume = new CheckpointResumeService(fx.checkpoints, fx.artifacts, fx.cas, fx.ledger, mapper);
        var writer = new CheckpointWriter(fx.artifacts, fx.checkpoints, fx.ledger);
        return new ReviewStepExecutor(fx.runs, fx.revisions, fx.cas, fx.artifacts,
                new SafeTarExtractor(), loop, ReviewBudget.DEFAULT, mapper,
                resume, writer, fx.ledger,
                requestedModel -> java.util.Optional.of(new ModelRouteIdentity(
                        "mock-provider", requestedModel, contractVersion)));
    }

    private void stageInput() {
        byte[] tarball = TestTarballs.tarGz(out ->
                TestTarballs.file(out, TestTarballs.GH_PREFIX + "a/Foo.java", "int x = 0/1;\n"));
        fx.cas.putIfAbsent(SNAPSHOT_DIGEST, tarball);
        fx.cas.putIfAbsent(DIFF_DIGEST, "diff --git a/Foo.java".getBytes(StandardCharsets.UTF_8));
    }

    private StepExecutionContext context() {
        return new StepExecutionContext(item, step, java.util.UUID.randomUUID());
    }

    @Test
    void loadsInputRunsLoopAndStoresOutput() {
        stageInput();
        modelClient.enqueueContent("""
                [{"file":"a/Foo.java","line":50,"existing_code":"int x = 0/1;","rule":"div-zero","severity":"MAJOR","message":"除零"}]
                """);

        StepOutcome outcome = executor.execute(context(), () -> true);

        StepOutcome.Succeeded succeeded = (StepOutcome.Succeeded) outcome;
        // 行号工程映射：模型报 50，按 existing_code 重定位到 1
        assertThat(succeeded.reviewOutcome().findings()).hasSize(1);
        assertThat(succeeded.reviewOutcome().findings().get(0).lineStart()).isEqualTo(1);
        // 产出 JSON 落 CAS + FINDING_BODY 登记（大对象只进 CAS，库里存 digest）
        assertThat(fx.cas.exists(succeeded.outputArtifactDigest())).isTrue();
        assertThat(fx.artifacts.all()).anySatisfy(a -> {
            assertThat(a.artifactType()).isEqualTo(ArtifactType.FINDING_BODY);
            assertThat(a.digest()).isEqualTo(succeeded.outputArtifactDigest());
        });
    }

    @Test
    void modelResponseStoredInCasAndRegistered() {
        // INC-19：模型原始响应全文落 CAS + MODEL_RESPONSE 登记（诊断模型真实输出的事实源）
        stageInput();
        String modelOutput = "[{\"file\":\"a/Foo.java\",\"line\":50,\"existing_code\":\"int x = 0/1;\","
                + "\"rule\":\"div-zero\",\"severity\":\"MAJOR\",\"message\":\"除零\"}]";
        modelClient.enqueueContent(modelOutput);

        StepOutcome outcome = executor.execute(context(), () -> true);

        assertThat(outcome).isInstanceOf(StepOutcome.Succeeded.class);
        Digest modelDigest = Digest.sha256Of(modelOutput);
        assertThat(fx.cas.exists(modelDigest)).isTrue();
        assertThat(new String(fx.cas.get(modelDigest).orElseThrow(), StandardCharsets.UTF_8))
                .isEqualTo(modelOutput);
        assertThat(fx.artifacts.all()).anySatisfy(a -> {
            assertThat(a.artifactType()).isEqualTo(ArtifactType.MODEL_RESPONSE);
            assertThat(a.digest()).isEqualTo(modelDigest);
        });
    }

    @Test
    void secondAttemptReusesCheckpointWithoutCallingModel() {
        stageInput();
        modelClient.enqueueContent("[]");

        StepOutcome first = executor.execute(context(), () -> true);
        int calls = modelClient.requests().size();
        StepOutcome second = executor.execute(context(), () -> true);

        assertThat(modelClient.requests()).hasSize(calls);
        assertThat(((StepOutcome.Succeeded) second).outputArtifactDigest())
                .isEqualTo(((StepOutcome.Succeeded) first).outputArtifactDigest());
        assertThat(fx.events.all()).anySatisfy(e ->
                assertThat(e.eventType()).isEqualTo(com.objwww.pr.shared.ExecutionEventType.CHECKPOINT_REUSED));
    }

    @Test
    void contractChangeDiscardsAndCallsModelAgain() {
        stageInput();
        modelClient.enqueueContent("[]");
        executor.execute(context(), () -> true);

        modelClient.enqueueContent("[]");
        ReviewStepExecutor changed = executorWith(new ReviewAgentLoop(modelClient,
                new FindingMapper(), new PolicyEngine(new ToolRegistry())),
                "v2");
        changed.execute(context(), () -> true);

        assertThat(modelClient.requests()).hasSize(2);
        assertThat(fx.events.all()).anySatisfy(e -> {
            assertThat(e.eventType()).isEqualTo(
                    com.objwww.pr.shared.ExecutionEventType.CHECKPOINT_DISCARDED);
            assertThat(e.payload().get("reason")).isEqualTo("CONTRACT_CHANGED:model_identity");
        });
    }

    @Test
    void missingCheckpointBlobFailsClosedAndCallsModelAgain() {
        stageInput();
        modelClient.enqueueContent("[]");
        StepOutcome first = executor.execute(context(), () -> true);
        fx.cas.remove(((StepOutcome.Succeeded) first).outputArtifactDigest());

        modelClient.enqueueContent("[]");
        executor.execute(context(), () -> true);

        assertThat(modelClient.requests()).hasSize(2);
        assertThat(fx.events.all()).anySatisfy(e -> {
            assertThat(e.eventType()).isEqualTo(
                    com.objwww.pr.shared.ExecutionEventType.CHECKPOINT_DISCARDED);
            assertThat(e.payload().get("reason")).isEqualTo("CAS_MISSING_FINDINGS");
        });
    }

    @Test
    void missingSnapshotBlobFails() {
        fx.cas.putIfAbsent(DIFF_DIGEST, "d".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> executor.execute(context(), () -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAS 缺快照");
    }

    @Test
    void missingDiffBlobFails() {
        fx.cas.putIfAbsent(SNAPSHOT_DIGEST, TestTarballs.tarGz(out ->
                TestTarballs.file(out, TestTarballs.GH_PREFIX + "a/Foo.java", "x")));

        assertThatThrownBy(() -> executor.execute(context(), () -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAS 缺 diff");
    }

    @Test
    void maliciousTarballRejectedBySafeExtractor() {
        // EX-10 防线在执行器装 input 路径同样生效（安全解包拒绝，不降级进评审）
        fx.cas.putIfAbsent(SNAPSHOT_DIGEST, TestTarballs.tarGz(out ->
                TestTarballs.file(out, TestTarballs.GH_PREFIX + "../../etc/passwd", "root:x:0:0")));
        fx.cas.putIfAbsent(DIFF_DIGEST, "d".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> executor.execute(context(), () -> true))
                .isInstanceOf(SecurityRejectionException.class);
    }

    @Test
    void modelGarbagePropagatesAsParseFailure() {
        stageInput();
        modelClient.enqueueContent("我觉得这个 PR 写得挺好的。（非 JSON）");

        assertThatThrownBy(() -> executor.execute(context(), () -> true))
                .isInstanceOf(ModelOutputParseException.class);
    }

    @Test
    void modelTimeoutPropagates() {
        stageInput();
        ReviewStepExecutor timeoutExecutor = executorWith(new ReviewAgentLoop((req, ctx) -> {
                    throw new ModelCallFailedException("TIMEOUT", "模型超时", true);
                }, new FindingMapper(), new PolicyEngine(new ToolRegistry())),
                "v1");

        assertThatThrownBy(() -> timeoutExecutor.execute(context(), () -> true))
                .isInstanceOf(ModelCallFailedException.class);
    }

    @Test
    void deadLeaseStopsBeforeModelCall() {
        stageInput();
        modelClient.enqueueContent("[]");

        assertThatThrownBy(() -> executor.execute(context(), () -> false))
                .isInstanceOf(LeaseLostException.class);
        assertThat(modelClient.requests()).isEmpty(); // 检查点在模型调用前拦停
    }
}
