package com.objwww.pr.control.eval.domain.model;

import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 逐案行为评测输入（ME-T04/D04 第 1/2 条；纯数据，L0 零框架依赖）：
 * 只读观测投影——事件（工具账本）/证据/引用解析 + 报告包 + golden 检查点。
 * UI 截断摘要不进本输入：正文语料只取 rca_evidence.payload 全量 canonical 字节
 * （超长截断必须置 {@link EvidenceContent#contentTruncated}，由检查诚实落
 * NOT_ASSESSED，不冒充完整）。
 *
 * <p>最小轨迹字段（{@link TraceToolCall}）：事件 ID 与顺序（invocationId/callSeq）、
 * task、工具名+schema 版本（toolName/toolVersion）、参数摘要（actionDigest）、
 * 引用 ID（resultRef）、状态/错误码（state/reasonCode）。轮次/预算/终止原因属
 * run 级观测，由既有 eval_case_result/rca_run 面承载，不在本投影重复。
 *
 * <p>null 语义：evidence = null → 无选定报告（无结论可复核）；rcaRunId = null →
 * trace 缺失（已注入无 run）；contentText = null → 正文不可得（digest-only/
 * 读失败）——依赖正文的检查一律 NOT_ASSESSED，不猜通过（EV-05）。
 */
public record BehaviorInput(EvidencePackageV2 evidence,
                            List<String> expectedCheckpoints,
                            Integer textCheckpointsCovered,
                            Integer textCheckpointsTotal,
                            UUID rcaRunId,
                            Instant runStartedAt,
                            Instant runFinishedAt,
                            List<TraceToolCall> toolCalls,
                            List<ResolvedCitation> citations,
                            List<EvidenceContent> runEvidence,
                            boolean evidenceReadAvailable) {

    public BehaviorInput {
        Objects.requireNonNull(expectedCheckpoints, "expectedCheckpoints 不得为 null");
        expectedCheckpoints = List.copyOf(expectedCheckpoints);
        Objects.requireNonNull(toolCalls, "toolCalls 不得为 null");
        toolCalls = List.copyOf(toolCalls);
        Objects.requireNonNull(citations, "citations 不得为 null");
        citations = List.copyOf(citations);
        Objects.requireNonNull(runEvidence, "runEvidence 不得为 null");
        runEvidence = List.copyOf(runEvidence);
    }

    /** 工具账本最小轨迹行（rca_tool_invocation 投影；按 callSeq 升序入场） */
    public record TraceToolCall(UUID invocationId, UUID taskId, String toolName,
                                String toolVersion, long callSeq, String actionDigest,
                                String state, String reasonCode, String resultRef) {

        public TraceToolCall {
            Objects.requireNonNull(invocationId, "invocationId 不得为 null");
            Objects.requireNonNull(toolName, "toolName 不得为 null");
        }
    }

    /**
     * 报告引用解析行：ref = claim 引用原文；evidenceId/evidenceRunId = null →
     * 未解析（dangling 或读面不可用——由 evidenceReadAvailable 区分）；归属/时窗
     * 检查按 evidenceRunId 与 time 界判定，跨 run/跨租户引用不进入可用证据集合。
     */
    public record ResolvedCitation(String ref, int claimIndex, UUID evidenceId,
                                   UUID evidenceRunId, Instant timeStart, Instant timeEnd,
                                   String contentText, String payloadDigest) {

        public ResolvedCitation {
            Objects.requireNonNull(ref, "ref 不得为 null");
        }
    }

    /** run 级证据语料行（证据覆盖检查语料；contentText null = 正文不可得如实） */
    public record EvidenceContent(UUID evidenceId, String contentText,
                                  String payloadDigest, boolean contentTruncated) {

        public EvidenceContent {
            Objects.requireNonNull(evidenceId, "evidenceId 不得为 null");
        }
    }
}
