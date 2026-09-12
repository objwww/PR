package com.objwww.pr.control.alert.domain.agent;

import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 上下文压缩摘要（R11/MA-04，V92 rca_context_summary 不可变档）：确定性裁剪
 * （ContextAssembler R1 界面）之后仍超软阈值时的 LLM 摘要产物。
 *
 * <p>身份 = (run, task, source_snapshot_digest)：同一冻结源至多一份已提交摘要
 * （uq 即 CAS 提交语义，并发同源双写一胜一拒；同源重放返回既有行）。
 * required_refs 由宿主生成（绑定 inputRefs ∪ 检查点终局引用），摘要模型不得删空；
 * omitted_refs = 引用值域全集 − 保留集（如实留痕，压缩前后可查——MC33 面的数据基础）。
 *
 * <p>summary_digest = summary_text 的 sha256（正文完整性锚）；event_seq 区间只覆盖
 * 冻结窗，后续增量为新摘要的新区间（禁止混入一半新证据却沿用旧 digest——MC16）。
 */
public record ContextSummary(
        UUID id,
        UUID runId,
        UUID taskId,
        int schemaVersion,
        String sourceSnapshotDigest,
        long eventSeqFrom,
        long eventSeqTo,
        String summaryPromptDigest,
        String model,
        int tokenBefore,
        int tokenAfter,
        List<String> requiredRefs,
        List<String> omittedRefs,
        String summaryText,
        String summaryDigest,
        String validationResult,
        String producer,
        Long configEpoch,
        Instant createdAt) {

    public ContextSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(sourceSnapshotDigest, "sourceSnapshotDigest");
        Objects.requireNonNull(summaryPromptDigest, "summaryPromptDigest");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(requiredRefs, "requiredRefs");
        Objects.requireNonNull(omittedRefs, "omittedRefs");
        Objects.requireNonNull(summaryText, "summaryText");
        Objects.requireNonNull(summaryDigest, "summaryDigest");
        Objects.requireNonNull(validationResult, "validationResult");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(createdAt, "createdAt");
        if (eventSeqFrom < 0 || eventSeqTo < eventSeqFrom) {
            throw new IllegalArgumentException("event_seq 区间非法: [" + eventSeqFrom
                    + "," + eventSeqTo + "]");
        }
        if (tokenBefore <= 0 || tokenAfter < 0) {
            throw new IllegalArgumentException("token 前后值非法: before=" + tokenBefore
                    + " after=" + tokenAfter);
        }
        requiredRefs = List.copyOf(requiredRefs);
        omittedRefs = List.copyOf(omittedRefs);
    }

    /** 构造工厂：summaryDigest 在此一次算定（正文 sha256） */
    public static ContextSummary of(UUID id, UUID runId, UUID taskId, int schemaVersion,
            String sourceSnapshotDigest, long eventSeqFrom, long eventSeqTo,
            String summaryPromptDigest, String model, int tokenBefore, int tokenAfter,
            List<String> requiredRefs, List<String> omittedRefs, String summaryText,
            String validationResult, String producer, Long configEpoch, Instant createdAt) {
        return new ContextSummary(id, runId, taskId, schemaVersion, sourceSnapshotDigest,
                eventSeqFrom, eventSeqTo, summaryPromptDigest, model, tokenBefore,
                tokenAfter, requiredRefs, omittedRefs, summaryText,
                Digest.sha256Of(summaryText).value(), validationResult, producer,
                configEpoch, createdAt);
    }

    /** 槽契约投影（页面/回放面共用的键序；MC33 可查面） */
    public Map<String, Object> meta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schema_version", schemaVersion);
        meta.put("source_snapshot_digest", sourceSnapshotDigest);
        meta.put("event_seq_from", eventSeqFrom);
        meta.put("event_seq_to", eventSeqTo);
        meta.put("model", model);
        meta.put("token_before", tokenBefore);
        meta.put("token_after", tokenAfter);
        meta.put("required_refs", requiredRefs);
        meta.put("omitted_refs", omittedRefs);
        meta.put("validation_result", validationResult);
        meta.put("producer", producer);
        meta.put("config_epoch", configEpoch);
        meta.put("created_at", createdAt.toString());
        return meta;
    }
}
