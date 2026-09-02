package com.objwww.pr.control.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.domain.ai.TokenUsage;
import com.objwww.pr.control.domain.model.ArtifactRecord;
import com.objwww.pr.control.domain.model.ArtifactType;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.model.RunStep;
import com.objwww.pr.control.domain.model.StepCheckpoint;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.domain.repository.ArtifactRepository;
import com.objwww.pr.control.domain.repository.StepCheckpointRepository;
import com.objwww.pr.control.domain.review.ReviewFindingDraft;
import com.objwww.pr.control.domain.review.ReviewOutcome;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.Digests;
import com.objwww.pr.shared.ExecutionEventType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** checkpoint 三条件恢复：行存在、双 CAS/登记/schema 完整、五分量契约相同。 */
public final class CheckpointResumeService {

    /** CONTRACT_CHANGED 不落枚举：分量 reason 由 {@link #contractChange} 按列细分（EX-22）。 */
    public enum DiscardReason {
        CAS_MISSING_FINDINGS,
        CAS_MISSING_MODEL_RESPONSE,
        CHECKPOINT_CORRUPT
    }

    public record ResumeHit(Digest outputDigest, ReviewOutcome outcome) {
    }

    static final int MAX_CHECKPOINT_BYTES = 4 * 1024 * 1024;
    private static final String PRODUCER = "control-app";

    private final StepCheckpointRepository checkpoints;
    private final ArtifactRepository artifacts;
    private final ArtifactStore cas;
    private final ExecutionLedger ledger;
    private final ObjectMapper mapper;

    public CheckpointResumeService(StepCheckpointRepository checkpoints,
                                   ArtifactRepository artifacts, ArtifactStore cas,
                                   ExecutionLedger ledger, ObjectMapper mapper) {
        this.checkpoints = Objects.requireNonNull(checkpoints);
        this.artifacts = Objects.requireNonNull(artifacts);
        this.cas = Objects.requireNonNull(cas);
        this.ledger = Objects.requireNonNull(ledger);
        this.mapper = Objects.requireNonNull(mapper);
    }

    /**
     * M3（§4.7/I30）：resume 不再接收"当前路由"契约——恢复发生在模型调用前，
     * 当前实际路由身份未知（G1 阻断项 2 裁定）。改为按 checkpoint 保存的
     * model_identity 反查当前配置路由的契约身份：
     * resolver 抛异常（身份无法解析）→ CHECKPOINT_CORRUPT；
     * 返回 null（路由已被移除/禁用）→ ROUTE_REMOVED；
     * 否则以解析出的契约走既有五分量比较与 CAS 校验。
     */
    public Optional<ResumeHit> resume(ReviewRun run, RunStep step, UUID attemptId,
                                      Function<String, CheckpointContract> contractResolver) {
        Optional<StepCheckpoint> found = checkpoints.find(step.getId(), StepCheckpoint.REVIEW_OUTCOME);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        StepCheckpoint checkpoint = found.get();
        CheckpointContract contract;
        try {
            contract = contractResolver.apply(checkpoint.modelIdentity());
        } catch (RuntimeException e) {
            discard(run, step, attemptId, checkpoint, DiscardReason.CHECKPOINT_CORRUPT.name());
            return Optional.empty();
        }
        if (contract == null) {
            discard(run, step, attemptId, checkpoint, "ROUTE_REMOVED");
            return Optional.empty();
        }
        String contractChange = contractChange(checkpoint, contract);
        if (contractChange != null) {
            discard(run, step, attemptId, checkpoint, contractChange);
            return Optional.empty();
        }

        BlobCheck output = verifiedBlob(checkpoint.outputArtifactDigest(), ArtifactType.FINDING_BODY);
        if (output.failure() != null) {
            discard(run, step, attemptId, checkpoint, output.failure().name());
            return Optional.empty();
        }
        BlobCheck model = verifiedBlob(checkpoint.modelResponseDigest(), ArtifactType.MODEL_RESPONSE);
        if (model.failure() != null) {
            discard(run, step, attemptId, checkpoint, model.failure().name());
            return Optional.empty();
        }
        try {
            ReviewOutcome outcome = parseOutcome(output.bytes(),
                    new String(model.bytes(), StandardCharsets.UTF_8), checkpoint.modelIdentity());
            ledger.append(ledger.newEvent(run.getId(), run.getPrRevisionId(), step.getId(), attemptId,
                    ExecutionEventType.CHECKPOINT_REUSED, null, run.getId(), PRODUCER, Map.of(
                            "checkpoint_id", checkpoint.id().toString(),
                            "output_artifact_digest", checkpoint.outputArtifactDigest().value())));
            return Optional.of(new ResumeHit(checkpoint.outputArtifactDigest(), outcome));
        } catch (RuntimeException e) {
            discard(run, step, attemptId, checkpoint, DiscardReason.CHECKPOINT_CORRUPT.name());
            return Optional.empty();
        }
    }

    static String contractChange(StepCheckpoint checkpoint, CheckpointContract current) {
        if (checkpoint.checkpointContractDigest().equals(current.digest())) return null;
        if (!checkpoint.promptTemplateVersion().equals(current.promptTemplateVersion()))
            return "CONTRACT_CHANGED:prompt";
        if (!checkpoint.findingSchemaVersion().equals(current.findingSchemaVersion()))
            return "CONTRACT_CHANGED:schema";
        if (!checkpoint.mapperContractVersion().equals(current.mapperContractVersion()))
            return "CONTRACT_CHANGED:mapper";
        if (!checkpoint.contextBuilderVersion().equals(current.contextBuilderVersion()))
            return "CONTRACT_CHANGED:context";
        if (!checkpoint.modelIdentity().equals(current.modelIdentity()))
            return "CONTRACT_CHANGED:model_identity";
        return "CONTRACT_CHANGED:digest";
    }

    /**
     * fail-closed 校验（I18/UT-19）：登记行缺/CAS 文件缺 → CAS_MISSING_*（环境性缺失）；
     * 类型错误/登记超限/体量不符/sha 回读不符 → CHECKPOINT_CORRUPT（内容被破坏）。
     */
    private BlobCheck verifiedBlob(Digest digest, ArtifactType expectedType) {
        DiscardReason missing = expectedType == ArtifactType.FINDING_BODY
                ? DiscardReason.CAS_MISSING_FINDINGS : DiscardReason.CAS_MISSING_MODEL_RESPONSE;
        Optional<ArtifactRecord> record = artifacts.findByDigest(digest);
        if (record.isEmpty()) {
            return BlobCheck.failed(missing);
        }
        if (record.get().artifactType() != expectedType
                || record.get().sizeBytes() > MAX_CHECKPOINT_BYTES) {
            return BlobCheck.failed(DiscardReason.CHECKPOINT_CORRUPT);
        }
        Optional<byte[]> bytes = cas.get(digest);
        if (bytes.isEmpty()) {
            return BlobCheck.failed(missing);
        }
        if (bytes.get().length > MAX_CHECKPOINT_BYTES
                || bytes.get().length != record.get().sizeBytes()
                || !Digests.sha256Hex(bytes.get()).equals(digest.value())) {
            return BlobCheck.failed(DiscardReason.CHECKPOINT_CORRUPT);
        }
        return BlobCheck.ok(bytes.get());
    }

    /** CAS 校验封闭结果：failure 非空即失败，携带精确 discard reason。 */
    private record BlobCheck(byte[] bytes, DiscardReason failure) {
        static BlobCheck ok(byte[] bytes) {
            return new BlobCheck(bytes, null);
        }

        static BlobCheck failed(DiscardReason failure) {
            return new BlobCheck(null, Objects.requireNonNull(failure));
        }
    }

    private ReviewOutcome parseOutcome(byte[] json, String modelResponse, String modelIdentity) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject() || !root.path("findings").isArray()
                    || !root.path("stats").isObject() || !root.path("token_usage").isObject()) {
                throw new IllegalArgumentException("checkpoint findings schema 不完整");
            }
            List<ReviewFindingDraft> findings = new ArrayList<>();
            for (JsonNode f : root.path("findings")) {
                findings.add(new ReviewFindingDraft(requiredText(f, "file"),
                        requiredInt(f, "line_start"), requiredInt(f, "line_end"),
                        requiredText(f, "rule"), requiredText(f, "severity"),
                        requiredText(f, "message"), new Digest(requiredText(f, "fingerprint"))));
            }
            JsonNode stats = root.path("stats");
            JsonNode usage = root.path("token_usage");
            if (requiredInt(stats, "findings") != findings.size()) {
                throw new IllegalArgumentException("checkpoint findings 计数不一致");
            }
            return new ReviewOutcome(findings, requiredInt(stats, "dropped"),
                    requiredInt(stats, "malformed"), requiredInt(stats, "candidate_files"),
                    requiredInt(stats, "selected_files"), requiredInt(stats, "truncated_files"),
                    new TokenUsage(requiredLong(usage, "prompt_tokens"),
                            requiredLong(usage, "completion_tokens"),
                            requiredLong(usage, "total_tokens")), modelResponse,
                    com.objwww.pr.control.domain.ai.ModelRouteIdentity.fromCanonicalString(modelIdentity));
        } catch (Exception e) {
            throw new IllegalArgumentException("checkpoint findings 无法恢复", e);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("缺文本字段: " + field);
        }
        return value.asText();
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("缺整数字段: " + field);
        }
        return value.intValue();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("缺长整数字段: " + field);
        }
        return value.longValue();
    }

    private void discard(ReviewRun run, RunStep step, UUID attemptId,
                         StepCheckpoint checkpoint, String reason) {
        ledger.append(ledger.newEvent(run.getId(), run.getPrRevisionId(), step.getId(), attemptId,
                ExecutionEventType.CHECKPOINT_DISCARDED, null, run.getId(), PRODUCER, Map.of(
                        "checkpoint_id", checkpoint.id().toString(), "reason", reason)));
    }
}
