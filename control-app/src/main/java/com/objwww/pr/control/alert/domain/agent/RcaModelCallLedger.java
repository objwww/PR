package com.objwww.pr.control.alert.domain.agent;

import java.util.List;
import java.util.UUID;

/**
 * RCA 模型调用账本端口（R7a-1，V48 rca_model_call）。
 * PENDING 先行落账取得发送资格——<b>open 失败 = 零触网</b>（§二.3，D5 同律）；
 * 终态 CAS（PENDING → SUCCESS/FAILED/UNKNOWN，单向单次）；usage 缺失不猜零
 * （SUCCESS + usageMissing，费用未决走对账，不伪称失败也不免费结算）；
 * UNKNOWN = 是否已执行不确定 → 保守占预算，恢复对账不盲重发。
 * 实现方每方法自含短事务。
 */
public interface RcaModelCallLedger {

    /** PENDING 先行（键冲突=同动作物理请求重复，显式抛 DataIntegrity 异常族） */
    void open(OpenRow row);

    /** PENDING → SUCCESS（CAS；非 PENDING 返回 false；usage 缺失时 cost 不填不猜零） */
    boolean succeed(UUID id, UsageOutcome usage);

    /** PENDING → FAILED + 脱敏原因码（CAS；非 PENDING 返回 false） */
    boolean fail(UUID id, String errorCode);

    /** PENDING → UNKNOWN（终态写失败/进程死/传输不确定；保守占预算的账本锚） */
    boolean markUnknown(UUID id);

    /** 恢复对账读：某 run 未结算（PENDING/UNKNOWN）行——不盲重发的判定输入（EX-A3 同律） */
    List<UnsettledRow> findUnsettledByRun(UUID runId);

    /** PENDING 落账行（身份五元组+role 身份+预算/快照锚；prompt 只落 digest） */
    record OpenRow(UUID id, UUID runId, UUID taskId, UUID attemptId, long actionSeq,
            int physicalSeq, int roundId, String roleId, String roleVersion,
            String roleDigest, String promptDigest, UUID budgetReservationId,
            String inputSnapshotDigest, Long configEpoch, String releaseDigest,
            long leaseEpoch) {
    }

    /** 成功终态载荷（usageMissing=true 时 costMicros/pricing 必为 null——不猜零） */
    record UsageOutcome(long promptTokens, long completionTokens, long totalTokens,
            boolean usageMissing, Long costMicros, String pricingVersion, String currency,
            String providerRequestId, String routeId, String requestedModel, long latencyMs,
            UUID gatewayInvocationId) {
    }

    /** 恢复对账投影：未结算行的最小面 */
    record UnsettledRow(UUID id, UUID taskId, long actionSeq, int physicalSeq,
            String state, String errorCode) {
    }
}
