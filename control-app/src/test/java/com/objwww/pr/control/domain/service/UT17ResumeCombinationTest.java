package com.objwww.pr.control.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.domain.model.ArtifactRecord;
import com.objwww.pr.control.domain.model.ArtifactType;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.model.RunMode;
import com.objwww.pr.control.domain.model.RunStep;
import com.objwww.pr.control.domain.model.StepCheckpoint;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.domain.repository.ArtifactRepository;
import com.objwww.pr.control.domain.repository.StepCheckpointRepository;
import com.objwww.pr.control.support.InMemoryStores;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.Digests;
import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.OperationId;
import com.objwww.pr.shared.RunState;
import com.objwww.pr.shared.StepState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-17（回指 I18，方案 §11 L1）：复用判定 8 组合穷举——
 * contract 匹配与否 × findings digest 在否 × 原文（MODEL_RESPONSE）digest 在否。
 * 仅"checkpoint 行在 + 五分量契约相同 + 双 artifact 登记行/CAS blob 俱在"命中
 * （CHECKPOINT_REUSED）；其余 7 组各自给出精确 discard reason。
 * 校验次序也是断言面：契约优先于 blob 校验（契约变 → CONTRACT_CHANGED:*，
 * 无论 blob 在否）；findings 优先于原文（双缺 → CAS_MISSING_FINDINGS）。
 * reason 语义以代码现状（RM2-09 分流）为准：登记行缺/CAS 文件缺 → CAS_MISSING_*；
 * 内容破坏 → CHECKPOINT_CORRUPT，归 UT-19（见 CheckpointResumeServiceTest）。
 */
class UT17ResumeCombinationTest {

    private static final CheckpointContract CONTRACT = new CheckpointContract(
            "prompt-v1", "schema-v1", "mapper-v1", "context-v1", "model-v1");
    private static final byte[] FINDINGS_JSON = """
            {"findings":[],"stats":{"findings":0,"dropped":0,"malformed":0,\
            "candidate_files":0,"selected_files":0,"truncated_files":0},\
            "token_usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] MODEL_RESPONSE = "[]".getBytes(StandardCharsets.UTF_8);

    private final Map<Digest, ArtifactRecord> registry = new HashMap<>();
    private final Map<Digest, byte[]> blobs = new HashMap<>();
    private final InMemoryStores.Events events = new InMemoryStores.Events();

    private ReviewRun run;
    private RunStep step;
    private StepCheckpointRepository checkpoints;
    private CheckpointResumeService service;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        run = new ReviewRun(UUID.randomUUID(), UUID.randomUUID(), null, null,
                Digest.sha256Of("run-key"), "trigger", RunMode.NORMAL,
                "policy-v1", "prompt-v1", "toolset-v1", null, RunState.REVIEWING, false,
                null, null, null, 0L, now, now, null);
        step = new RunStep(UUID.randomUUID(), run.getId(), null, "REVIEW", OperationId.random(),
                "REVIEW", "REVIEW", StepState.RUNNING, 1, null, null, 3, 600, 0L, now, now, null);
        checkpoints = new StepCheckpointRepository() {
            private StepCheckpoint staged;

            @Override
            public Optional<StepCheckpoint> find(UUID stepId, String checkpointKey) {
                return Optional.ofNullable(staged);
            }

            @Override
            public boolean upsertIfLeaseCurrent(StepCheckpoint checkpoint, UUID workItemId,
                                                String leaseOwner) {
                staged = checkpoint; // 直接落行；租约栅栏语义归 SqlGuardTest/CT-26
                return true;
            }
        };
        ArtifactRepository artifacts = new ArtifactRepository() {
            @Override
            public void register(ArtifactRecord record) {
                registry.put(record.digest(), record);
            }

            @Override
            public Optional<ArtifactRecord> findByDigest(Digest digest) {
                return Optional.ofNullable(registry.get(digest));
            }
        };
        ArtifactStore cas = new ArtifactStore() {
            @Override
            public String putIfAbsent(Digest digest, byte[] content) {
                blobs.putIfAbsent(digest, content);
                return "mem/" + digest.value();
            }

            @Override
            public boolean exists(Digest digest) {
                return blobs.containsKey(digest);
            }

            @Override
            public Optional<byte[]> get(Digest digest) {
                return Optional.ofNullable(blobs.get(digest));
            }
        };
        service = new CheckpointResumeService(checkpoints, artifacts, cas,
                new ExecutionLedger(events), new ObjectMapper());
    }

    private static Digest digestOf(byte[] content) {
        return new Digest(Digests.sha256Hex(content));
    }

    /** 双 artifact 登记 + CAS 落内容 + checkpoint 行，全部内部一致。 */
    private StepCheckpoint stageHealthy() {
        Digest outDigest = digestOf(FINDINGS_JSON);
        Digest modelDigest = digestOf(MODEL_RESPONSE);
        Instant now = Instant.now();
        registry.put(outDigest, new ArtifactRecord(outDigest, ArtifactType.FINDING_BODY,
                FINDINGS_JSON.length, "mem/out", now));
        registry.put(modelDigest, new ArtifactRecord(modelDigest, ArtifactType.MODEL_RESPONSE,
                MODEL_RESPONSE.length, "mem/model", now));
        blobs.put(outDigest, FINDINGS_JSON);
        blobs.put(modelDigest, MODEL_RESPONSE);
        StepCheckpoint checkpoint = new StepCheckpoint(UUID.randomUUID(), step.getId(),
                StepCheckpoint.REVIEW_OUTCOME, outDigest, modelDigest, CONTRACT.digest(),
                CONTRACT.promptTemplateVersion(), CONTRACT.findingSchemaVersion(),
                CONTRACT.mapperContractVersion(), CONTRACT.contextBuilderVersion(),
                CONTRACT.modelIdentity(), 1, 1, now);
        checkpoints.upsertIfLeaseCurrent(checkpoint, UUID.randomUUID(), "owner");
        return checkpoint;
    }

    private List<ExecutionEvent> discardEvents() {
        return events.all().stream()
                .filter(e -> e.eventType() == ExecutionEventType.CHECKPOINT_DISCARDED)
                .toList();
    }

    static Stream<Arguments> combinations() {
        return Stream.of(
                // contractMatch, findingsDigestPresent, modelDigestPresent, expectedDiscardReason
                Arguments.of(true, true, true, null),
                Arguments.of(true, true, false, "CAS_MISSING_MODEL_RESPONSE"),
                Arguments.of(true, false, true, "CAS_MISSING_FINDINGS"),
                Arguments.of(true, false, false, "CAS_MISSING_FINDINGS"),
                Arguments.of(false, true, true, "CONTRACT_CHANGED:model_identity"),
                Arguments.of(false, true, false, "CONTRACT_CHANGED:model_identity"),
                Arguments.of(false, false, true, "CONTRACT_CHANGED:model_identity"),
                Arguments.of(false, false, false, "CONTRACT_CHANGED:model_identity"));
    }

    @ParameterizedTest(name = "契约匹配={0} findings在={1} 原文在={2} → reason={3}")
    @MethodSource("combinations")
    void resumeDecisionMatrix(boolean contractMatch, boolean findingsPresent,
                              boolean modelPresent, String expectedReason) {
        StepCheckpoint checkpoint = stageHealthy();
        if (!findingsPresent) {
            registry.remove(checkpoint.outputArtifactDigest());
            blobs.remove(checkpoint.outputArtifactDigest());
        }
        if (!modelPresent) {
            registry.remove(checkpoint.modelResponseDigest());
            blobs.remove(checkpoint.modelResponseDigest());
        }
        CheckpointContract current = contractMatch ? CONTRACT : new CheckpointContract(
                "prompt-v1", "schema-v1", "mapper-v1", "context-v1", "model-v2");

        Optional<CheckpointResumeService.ResumeHit> hit =
                service.resume(run, step, UUID.randomUUID(), current);

        if (expectedReason == null) {
            assertThat(hit).isPresent();
            assertThat(hit.get().outputDigest()).isEqualTo(checkpoint.outputArtifactDigest());
            assertThat(discardEvents()).isEmpty();
            assertThat(events.all()).anySatisfy(e ->
                    assertThat(e.eventType()).isEqualTo(ExecutionEventType.CHECKPOINT_REUSED));
        } else {
            assertThat(hit).isEmpty();
            assertThat(discardEvents()).singleElement().satisfies(e ->
                    assertThat(e.payload().get("reason")).isEqualTo(expectedReason));
            assertThat(events.all()).noneMatch(
                    e -> e.eventType() == ExecutionEventType.CHECKPOINT_REUSED);
        }
    }
}
