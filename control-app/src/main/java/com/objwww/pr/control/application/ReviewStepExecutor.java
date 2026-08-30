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
import com.objwww.pr.control.domain.review.ReviewAgentLoop;
import com.objwww.pr.control.domain.review.ReviewBudget;
import com.objwww.pr.control.domain.review.ReviewFindingDraft;
import com.objwww.pr.control.domain.review.ReviewOutcome;
import com.objwww.pr.control.domain.snapshot.SafeTarExtractor;
import com.objwww.pr.control.domain.snapshot.SnapshotTree;
import com.objwww.pr.shared.Digest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    private final ReviewRunRepository runRepository;
    private final PRRevisionRepository revisionRepository;
    private final ArtifactStore artifactStore;
    private final ArtifactRepository artifactRepository;
    private final SafeTarExtractor extractor;
    private final ReviewAgentLoop agentLoop;
    private final ReviewBudget budget;
    private final ObjectMapper objectMapper;

    public ReviewStepExecutor(ReviewRunRepository runRepository,
                              PRRevisionRepository revisionRepository,
                              ArtifactStore artifactStore, ArtifactRepository artifactRepository,
                              SafeTarExtractor extractor, ReviewAgentLoop agentLoop,
                              ReviewBudget budget, ObjectMapper objectMapper) {
        this.runRepository = Objects.requireNonNull(runRepository);
        this.revisionRepository = Objects.requireNonNull(revisionRepository);
        this.artifactStore = Objects.requireNonNull(artifactStore);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.extractor = Objects.requireNonNull(extractor);
        this.agentLoop = Objects.requireNonNull(agentLoop);
        this.budget = Objects.requireNonNull(budget);
        this.objectMapper = Objects.requireNonNull(objectMapper);
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
        artifactRepository.register(new ArtifactRecord(outputDigest, ArtifactType.FINDING_BODY,
                body.length, path, Instant.now()));

        // 4) 模型原始响应全文落 CAS（INC-19：模型真实输出的唯一事实源，排查 dropped 必查；
        //    表/事件只记 digest，§5.5 大对象纪律）
        byte[] modelBody = outcome.modelResponse().getBytes(StandardCharsets.UTF_8);
        Digest modelDigest = Digest.sha256Of(new String(modelBody, StandardCharsets.UTF_8));
        String modelPath = artifactStore.putIfAbsent(modelDigest, modelBody);
        artifactRepository.register(new ArtifactRecord(modelDigest, ArtifactType.MODEL_RESPONSE,
                modelBody.length, modelPath, Instant.now()));

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
                "selected_files", outcome.selectedFiles(),
                "truncated_files", outcome.truncatedFiles()));
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("findings 序列化失败", e);
        }
    }
}
