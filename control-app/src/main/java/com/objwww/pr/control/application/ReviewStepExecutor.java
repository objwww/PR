package com.objwww.pr.control.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.domain.model.ArtifactRecord;
import com.objwww.pr.control.domain.model.ArtifactType;
import com.objwww.pr.control.domain.model.PRRevision;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.model.RunStep;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.domain.repository.ArtifactRepository;
import com.objwww.pr.control.domain.repository.PRRevisionRepository;
import com.objwww.pr.control.domain.repository.ReviewRunRepository;
import com.objwww.pr.control.domain.review.FindingMapper;
import com.objwww.pr.control.domain.review.ReviewAgentLoop;
import com.objwww.pr.control.domain.review.ReviewBudget;
import com.objwww.pr.control.domain.review.ReviewContractVersions;
import com.objwww.pr.control.domain.review.ReviewFindingDraft;
import com.objwww.pr.control.domain.review.ReviewOutcome;
import com.objwww.pr.control.domain.snapshot.SafeTarExtractor;
import com.objwww.pr.control.domain.snapshot.SnapshotTree;
import com.objwww.pr.control.domain.service.CheckpointContract;
import com.objwww.pr.control.domain.service.CheckpointResumeService;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.ExecutionEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * REVIEW Step 执行器（M0 唯一 Step 类型，§3.1/§6.6）：从 CAS 装 input
 * （head 快照 tarball + base..head diff，digest 来自 revision/step，I12 不可变输入）
 * → 安全解包 → {@link ReviewAgentLoop} → findings JSON 落 CAS（FINDING_BODY）
 * → 产出 {@link StepOutcome.Succeeded}（output digest + ReviewOutcome，交 T2 收尾）。
 *
 * <p>心跳在三个粗粒度检查点探活（装 input 前 / 模型调用前 / 落产出前），失效即停手。
 * 已知失败（安全解包拒绝、模型超时/超预算/输出畸形、CAS 缺失）以异常上抛，
 * 由 WorkItemWorker 统一归类 retryable（EX-06/EX-10：安全步骤不降级）。
 */
public class ReviewStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReviewStepExecutor.class);
    private static final String PRODUCER = "control-app";

    private final ReviewRunRepository runRepository;
    private final PRRevisionRepository revisionRepository;
    private final ArtifactStore artifactStore;
    private final ArtifactRepository artifactRepository;
    private final SafeTarExtractor extractor;
    private final ReviewAgentLoop agentLoop;
    private final ReviewBudget budget;
    private final ObjectMapper objectMapper;
    private final CheckpointResumeService resumeService;
    private final CheckpointWriter checkpointWriter;
    private final ExecutionLedger ledger;
    private final String modelIdentity;

    public ReviewStepExecutor(ReviewRunRepository runRepository,
                              PRRevisionRepository revisionRepository,
                              ArtifactStore artifactStore, ArtifactRepository artifactRepository,
                              SafeTarExtractor extractor, ReviewAgentLoop agentLoop,
                              ReviewBudget budget, ObjectMapper objectMapper,
                              CheckpointResumeService resumeService,
                              CheckpointWriter checkpointWriter,
                              ExecutionLedger ledger, String modelIdentity) {
        this.runRepository = Objects.requireNonNull(runRepository);
        this.revisionRepository = Objects.requireNonNull(revisionRepository);
        this.artifactStore = Objects.requireNonNull(artifactStore);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.extractor = Objects.requireNonNull(extractor);
        this.agentLoop = Objects.requireNonNull(agentLoop);
        this.budget = Objects.requireNonNull(budget);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.resumeService = Objects.requireNonNull(resumeService);
        this.checkpointWriter = Objects.requireNonNull(checkpointWriter);
        this.ledger = Objects.requireNonNull(ledger);
        this.modelIdentity = Objects.requireNonNull(modelIdentity);
    }

    @Override
    public String workType() {
        return ReviewOrchestrator.WORK_TYPE_REVIEW;
    }

    @Override
    public StepOutcome execute(StepExecutionContext context, LeaseHeartbeat heartbeat) {
        checkAlive(heartbeat);
        RunStep step = context.step();
        ReviewRun run = runRepository.findById(step.getReviewRunId())
                .orElseThrow(() -> new IllegalStateException("review_run 不存在: " + step.getReviewRunId()));
        PRRevision revision = revisionRepository.findById(run.getPrRevisionId())
                .orElseThrow(() -> new IllegalStateException("pr_revision 不存在: " + run.getPrRevisionId()));

        CheckpointContract contract = new CheckpointContract(
                run.getPromptVersion() + "/" + ReviewAgentLoop.PROMPT_TEMPLATE_VERSION,
                ReviewContractVersions.FINDING_SCHEMA_VERSION,
                FindingMapper.CONTRACT_VERSION,
                ReviewAgentLoop.CONTEXT_BUILDER_VERSION,
                modelIdentity);
        var resumed = resumeService.resume(run, step, context.attemptId(), contract);
        if (resumed.isPresent()) {
            return new StepOutcome.Succeeded(resumed.get().outputDigest(), resumed.get().outcome());
        }

        // 1) 装 input：CAS 按 digest 读回（缺失 = 不可变输入被破坏，上抛归类）
        byte[] tarball = artifactStore.get(revision.getSourceSnapshotDigest())
                .orElseThrow(() -> new IllegalStateException(
                        "CAS 缺快照: " + revision.getSourceSnapshotDigest().value()));
        byte[] diffBytes = artifactStore.get(step.getInputArtifactDigest())
                .orElseThrow(() -> new IllegalStateException(
                        "CAS 缺 diff: " + step.getInputArtifactDigest().value()));
        SnapshotTree snapshot = extractor.extract(tarball); // 安全解包拒绝上抛（EX-10）
        String diffText = new String(diffBytes, StandardCharsets.UTF_8);

        // 2) 模型评审（超时/超预算/乱输出异常上抛，Worker 归类；安全步骤不降级）
        checkAlive(heartbeat);
        ReviewOutcome outcome = agentLoop.review(snapshot, revision.getHeadSha(), diffText, budget);

        // 3) findings JSON 落 CAS（大对象只进 CAS，库里存 digest，v2.2 §5）
        checkAlive(heartbeat);
        byte[] body = toJson(outcome).getBytes(StandardCharsets.UTF_8);
        Digest outputDigest = Digest.sha256Of(new String(body, StandardCharsets.UTF_8));
        String path = artifactStore.putIfAbsent(outputDigest, body);
        Instant storedAt = Instant.now();
        ArtifactRecord outputRecord = new ArtifactRecord(outputDigest, ArtifactType.FINDING_BODY,
                body.length, path, storedAt);

        // 4) 模型原始响应全文落 CAS（INC-19：模型真实输出的唯一事实源，排查 dropped 必查；
        //    表/事件只记 digest，§5.5 大对象纪律）
        byte[] modelBody = outcome.modelResponse().getBytes(StandardCharsets.UTF_8);
        Digest modelDigest = Digest.sha256Of(new String(modelBody, StandardCharsets.UTF_8));
        String modelPath = artifactStore.putIfAbsent(modelDigest, modelBody);
        ArtifactRecord modelRecord = new ArtifactRecord(modelDigest, ArtifactType.MODEL_RESPONSE,
                modelBody.length, modelPath, storedAt);

        var checkpoint = new com.objwww.pr.control.domain.model.StepCheckpoint(
                UUID.randomUUID(), step.getId(),
                com.objwww.pr.control.domain.model.StepCheckpoint.REVIEW_OUTCOME,
                outputDigest, modelDigest, contract.digest(),
                contract.promptTemplateVersion(), contract.findingSchemaVersion(),
                contract.mapperContractVersion(), contract.contextBuilderVersion(),
                contract.modelIdentity(), context.workItem().getLeaseEpoch(),
                context.workItem().getAttemptCount(), storedAt);
        var event = ledger.newEvent(run.getId(), run.getPrRevisionId(), step.getId(), context.attemptId(),
                ExecutionEventType.CHECKPOINT_STORED, null, run.getId(), PRODUCER, Map.of(
                        "checkpoint_id", checkpoint.id().toString(),
                        "output_artifact_digest", outputDigest.value(),
                        "model_response_digest", modelDigest.value(),
                        "contract_digest", contract.digest().value(),
                        "attempt_no", checkpoint.attemptNo(),
                        "lease_epoch", checkpoint.leaseEpoch()));
        String leaseOwner = context.workItem().getLeaseOwner();
        boolean stored = leaseOwner != null && checkpointWriter.store(outputRecord, modelRecord,
                checkpoint, context.workItem().getId(), leaseOwner, event);
        if (!stored) {
            log.warn("checkpoint 晚到写被租约栅栏拒绝 step={} leaseEpoch={}",
                    step.getId(), context.workItem().getLeaseEpoch());
        }

        return new StepOutcome.Succeeded(outputDigest, outcome);
    }

    private static void checkAlive(LeaseHeartbeat heartbeat) {
        if (!heartbeat.isAlive()) {
            throw new LeaseLostException("租约已失效（心跳 0 行），停止执行");
        }
    }

    /** findings + 统计的确定性 JSON（与 T2 落 review_finding 同源） */
    private String toJson(ReviewOutcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        for (ReviewFindingDraft draft : outcome.findings()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("file", draft.filePath());
            f.put("line_start", draft.lineStart());
            f.put("line_end", draft.lineEnd());
            f.put("rule", draft.ruleId());
            f.put("severity", draft.severity());
            f.put("message", draft.message());
            f.put("fingerprint", draft.fingerprint().value());
            findings.add(f);
        }
        body.put("findings", findings);
        body.put("stats", Map.of(
                "findings", outcome.findings().size(),
                "dropped", outcome.droppedFindings(),
                "malformed", outcome.malformedFindings(),
                "candidate_files", outcome.candidateFiles(),
                "selected_files", outcome.selectedFiles(),
                "truncated_files", outcome.truncatedFiles()));
        body.put("token_usage", Map.of(
                "prompt_tokens", outcome.tokenUsage().promptTokens(),
                "completion_tokens", outcome.tokenUsage().completionTokens(),
                "total_tokens", outcome.tokenUsage().totalTokens()));
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("findings 序列化失败", e);
        }
    }
}
