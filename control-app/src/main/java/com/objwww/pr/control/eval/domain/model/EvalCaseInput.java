package com.objwww.pr.control.eval.domain.model;

import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.alert.domain.model.ToolCallStatus;
import com.objwww.pr.control.eval.domain.GoldenCase;

import java.util.List;
import java.util.Objects;

/**
 * M5-06 六维评测输入（纯函数面）：一个已终态 Case 的全部评分素材——golden 期望、
 * 已解析证据包、tool_call 观测序列（注册面/状态/参数摘要）、run 级成本观测。
 * 不携带任何框架类型（L0：am5DatasetDomainZeroFrameworkDependency 面）。
 *
 * <p>{@link ToolCallObservation} 列表按调用顺序排列，下标即 SixDimResult 各维
 * traceRefs 的 {@code tool_call:{i}} 引用位；{@code paramsDigest} 参与过程维
 * 重复等价调用判定（tool_name+params_digest 同键再现）。
 */
public record EvalCaseInput(GoldenCase golden,
                            EvidencePackageV2 evidence,
                            List<ToolCallObservation> toolCalls,
                            List<SafetyRejection> safetyRejections,
                            long latencyMs,
                            Usage usage,
                            boolean redteamCase) {

    public EvalCaseInput {
        Objects.requireNonNull(golden, "golden 不得为 null");
        Objects.requireNonNull(evidence, "evidence 不得为 null");
        Objects.requireNonNull(toolCalls, "toolCalls 不得为 null");
        toolCalls = List.copyOf(toolCalls);
        Objects.requireNonNull(safetyRejections, "safetyRejections 不得为 null");
        safetyRejections = List.copyOf(safetyRejections);
        Objects.requireNonNull(usage, "usage 不得为 null");
    }

    /** 单次工具调用观测（四字段全必填——账本诚实面，缺参调用不进观测序列） */
    public record ToolCallObservation(String toolName,
                                      boolean registered,
                                      ToolCallStatus status,
                                      String paramsDigest) {

        public ToolCallObservation {
            Objects.requireNonNull(toolName, "toolName 不得为 null");
            Objects.requireNonNull(status, "status 不得为 null");
            Objects.requireNonNull(paramsDigest, "paramsDigest 不得为 null");
        }
    }

    /**
     * 安全门拒绝记录（M5-07；消费 AM4 ToolPolicy/ToolGateway 拦截面落档，非重判）：
     * face = 五面分类码；ref = 证据引用位（claim:{i}/tool_call:{i}）；
     * reason = 机器码英文，不得携带原始 payload（§7.9.3 红线）。
     */
    public record SafetyRejection(SafetyFace face,
                                  String ref,
                                  String reason) {

        public SafetyRejection {
            Objects.requireNonNull(face, "face 不得为 null");
            Objects.requireNonNull(ref, "ref 不得为 null");
            Objects.requireNonNull(reason, "reason 不得为 null");
        }
    }

    /**
     * usage 台账观测（LiteLLM 对账面）。token 为 null = 台账缺失（missing=true）；
     * 成本维对缺失诚实记 0，不猜测补齐。
     */
    public record Usage(Long promptTokens,
                        Long completionTokens,
                        Long totalTokens,
                        boolean missing) {

        public static Usage absent() {
            return new Usage(null, null, null, true);
        }
    }
}
