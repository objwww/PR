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
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.OperationId;
import com.objwww.pr.shared.RunState;
import com.objwww.pr.shared.StepState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-19 fail-closed 校验的 reason 分流（RM2-09）：登记行缺/CAS 文件缺 → CAS_MISSING_*；
 * 类型错/登记超限/sha 回读不符/JSON 畸形 → CHECKPOINT_CORRUPT；两者都是丢弃重跑。
 */
class CheckpointResumeServiceTest {

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
    private StepCheckpoint stage(byte[] findings, byte[] model) {
        Digest outDigest = digestOf(findings);
        Digest modelDigest = digestOf(model);
        Instant now = Instant.now();
        registry.put(outDigest, new ArtifactRecord(outDigest, ArtifactType.FINDING_BODY,
                findings.length, "mem/out", now));
        registry.put(modelDigest, new ArtifactRecord(modelDigest, ArtifactType.MODEL_RESPONSE,
                model.length, "mem/model", now));
        blobs.put(outDigest, findings);
        blobs.put(modelDigest, model);
        StepCheckpoint checkpoint = new StepCheckpoint(UUID.randomUUID(), step.getId(),
                StepCheckpoint.REVIEW_OUTCOME, outDigest, modelDigest, CONTRACT.digest(),
                CONTRACT.promptTemplateVersion(), CONTRACT.findingSchemaVersion(),
                CONTRACT.mapperContractVersion(), CONTRACT.contextBuilderVersion(),
                CONTRACT.modelIdentity(), 1, 1, now);
        checkpoints.upsertIfLeaseCurrent(checkpoint, UUID.randomUUID(), "owner");
        return checkpoint;
    }

    private StepCheckpoint stageHealthy() {
        return stage(FINDINGS_JSON, MODEL_RESPONSE);
    }

    private Optional<CheckpointResumeService.ResumeHit> resume() {
        return service.resume(run, step, UUID.randomUUID(), CONTRACT);
    }

    private String discardReason() {
        return events.all().stream()
                .filter(e -> e.eventType() == ExecutionEventType.CHECKPOINT_DISCARDED)
                .map(e -> (String) e.payload().get("reason"))
                .findFirst().orElse(null);
    }

    @Test
    void healthyCheckpointHitsAndRecordsReused() {
        StepCheckpoint checkpoint = stageHealthy();

        Optional<CheckpointResumeService.ResumeHit> hit = resume();

        assertThat(hit).isPresent();
        assertThat(hit.get().outputDigest()).isEqualTo(checkpoint.outputArtifactDigest());
        assertThat(hit.get().outcome().findings()).isEmpty();
        assertThat(discardReason()).isNull();
        assertThat(events.all()).anySatisfy(e ->
                assertThat(e.eventType()).isEqualTo(ExecutionEventType.CHECKPOINT_REUSED));
    }

    @Test
    void missingRegistrationRowDiscardsAsCasMissing() {
        StepCheckpoint checkpoint = stageHealthy();
        registry.remove(checkpoint.outputArtifactDigest());

        assertThat(resume()).isEmpty();
        assertThat(discardReason()).isEqualTo("CAS_MISSING_FINDINGS");
    }

    @Test
    void missingCasBlobDiscardsAsCasMissing() {
        StepCheckpoint checkpoint = stageHealthy();
        blobs.remove(checkpoint.outputArtifactDigest());

        assertThat(resume()).isEmpty();
        assertThat(discardReason()).isEqualTo("CAS_MISSING_FINDINGS");
    }

    @Test
    void missingModelResponseBlobDiscardsAsCasMissingModelResponse() {
        StepCheckpoint checkpoint = stageHealthy();
        blobs.remove(checkpoint.modelResponseDigest());

        assertThat(resume()).isEmpty();
        assertThat(discardReason()).isEqualTo("CAS_MISSING_MODEL_RESPONSE");
    }

    @Test
    void wrongArtifactTypeDiscardsAsCorrupt() {
        StepCheckpoint checkpoint = stageHealthy();
        ArtifactRecord original = registry.get(checkpoint.outputArtifactDigest());
        registry.put(checkpoint.outputArtifactDigest(), new ArtifactRecord(
                checkpoint.outputArtifactDigest(), ArtifactType.MODEL_RESPONSE,
                original.sizeBytes(), original.storagePath(), original.createdAt()));

        assertThat(resume()).isEmpty();
        assertThat(discardReason()).isEqualTo("CHECKPOINT_CORRUPT");
    }

    @Test
    void oversizedRegistrationDiscardsAsCorrupt() {
        StepCheckpoint checkpoint = stageHealthy();
        ArtifactRecord original = registry.get(checkpoint.outputArtifactDigest());
        registry.put(checkpoint.outputArtifactDigest(), new ArtifactRecord(
                checkpoint.outputArtifactDigest(), ArtifactType.FINDING_BODY,
                CheckpointResumeService.MAX_CHECKPOINT_BYTES + 1L,
                original.storagePath(), original.createdAt()));

        assertThat(resume()).isEmpty();
        assertThat(discardReason()).isEqualTo("CHECKPOINT_CORRUPT");
    }

    @Test
    void shaMismatchOnCasReadbackDiscardsAsCorrupt() {
        StepCheckpoint checkpoint = stageHealthy();
        byte[] tampered = "tampered-body".getBytes(StandardCharsets.UTF_8);
        ArtifactRecord original = registry.get(checkpoint.outputArtifactDigest());
        registry.put(checkpoint.outputArtifactDigest(), new ArtifactRecord(
                checkpoint.outputArtifactDigest(), ArtifactType.FINDING_BODY,
                tampered.length, original.storagePath(), original.createdAt()));
        blobs.put(checkpoint.outputArtifactDigest(), tampered);

        assertThat(resume()).isEmpty();
        assertThat(discardReason()).isEqualTo("CHECKPOINT_CORRUPT");
    }

    @Test
    void malformedFindingsJsonDiscardsAsCorrupt() {
        stage("not-json".getBytes(StandardCharsets.UTF_8), MODEL_RESPONSE);

        assertThat(resume()).isEmpty();
        assertThat(discardReason()).isEqualTo("CHECKPOINT_CORRUPT");
    }
}
