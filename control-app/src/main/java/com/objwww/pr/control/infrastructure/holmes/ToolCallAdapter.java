package com.objwww.pr.control.infrastructure.holmes;

import com.objwww.pr.control.alert.domain.model.RcaToolCall;
import com.objwww.pr.control.alert.domain.model.ToolCallStatus;
import com.objwww.pr.shared.Digest;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * ToolCall Adapter（M3-06）：Holmes 线缆形态 {@link RawToolCall} → 内部契约 {@link RcaToolCall}。
 *
 * <p>状态枚举映射（大小写不敏感）：success→SUCCESS / error→ERROR / no_data→NO_DATA /
 * approval_required→APPROVAL_REQUIRED；未知值落 null（账本诚实，不猜测）。
 * 栅栏列（runId/observedGeneration/schemaVersion/payloadDigest）由收尾事务上下文统一填充。
 */
public final class ToolCallAdapter {

    private ToolCallAdapter() {
    }

    public static List<RcaToolCall> toDomain(List<HolmesResponseParser.RawToolCall> raw,
                                             UUID investigationResultId, UUID runId,
                                             int observedGeneration, int schemaVersion,
                                             Digest payloadDigest) {
        return raw.stream()
                .map(call -> toDomain(call, investigationResultId, runId,
                        observedGeneration, schemaVersion, payloadDigest))
                .toList();
    }

    public static RcaToolCall toDomain(HolmesResponseParser.RawToolCall call,
                                       UUID investigationResultId, UUID runId,
                                       int observedGeneration, int schemaVersion,
                                       Digest payloadDigest) {
        return new RcaToolCall(investigationResultId, call.callId(), call.sequenceNo(),
                call.toolName(), mapStatus(call.status()), call.paramsDigest(), call.resultDigest(),
                null, null, runId, observedGeneration, schemaVersion, payloadDigest);
    }

    static ToolCallStatus mapStatus(String wireStatus) {
        if (wireStatus == null || wireStatus.isBlank()) {
            return null;
        }
        return switch (wireStatus.toLowerCase(Locale.ROOT)) {
            case "success" -> ToolCallStatus.SUCCESS;
            case "error" -> ToolCallStatus.ERROR;
            case "no_data" -> ToolCallStatus.NO_DATA;
            case "approval_required" -> ToolCallStatus.APPROVAL_REQUIRED;
            default -> null;
        };
    }
}
