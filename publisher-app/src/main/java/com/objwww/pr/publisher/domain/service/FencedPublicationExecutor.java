package com.objwww.pr.publisher.domain.service;

import com.objwww.pr.publisher.domain.handler.PublicationHandler;
import com.objwww.pr.publisher.domain.handler.ReconcileVerdict;
import com.objwww.pr.publisher.domain.handler.ProbeResult;
import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.domain.port.PayloadReader;
import com.objwww.pr.publisher.domain.port.PayloadUnavailableException;
import com.objwww.pr.publisher.domain.port.PublicationStore;
import com.objwww.pr.publisher.domain.port.StaleLeaseException;
import com.objwww.pr.publisher.infrastructure.github.GitHubTransportException;
import com.objwww.pr.publisher.infrastructure.github.GitHubWriteAdapter;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.GitHubOperation;
import com.objwww.pr.shared.RetryDirective;
import com.objwww.pr.shared.TypedOutcome;
import com.objwww.pr.shared.TypedReadRequest;
import com.objwww.pr.shared.TypedResponse;
import com.objwww.pr.shared.TypedWriteRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * T3 发布的唯一执行者（§6.1）：<b>全代码库唯一允许引用 {@link GitHubWriteAdapter} 的类</b>
 * （I4/AFT-07，B27 结构封死；Handler 只产类型化请求对象，触网一律经此处）。
 *
 * <p>步骤（E1/E2/E3 + 评审修正 #5）：
 * <ol>
 *   <li>T3-A：{@link PublicationStore#prepare} 单事务内完成 schema 校验 → 依赖终态 →
 *       跳号检测 → epoch fence → →IN_FLIGHT（决策逻辑在 {@link PublicationGate}，纯函数）；</li>
 *   <li>事务外：Handler 翻译 TypedWriteRequest → 经 GitHubWriteAdapter 触网
 *       （不持 DB 锁跨外部调用）；</li>
 *   <li>T3-B：按 {@link TypedOutcome} 归类落账（CONFIRMED 同事务推进游标 +
 *       publication_resource；响应丢失 → RECONCILING 禁盲目重发；5xx 退避；
 *       422 head 不匹配 → SUPERSEDED；401/403 → FAILED_TERMINAL + 告警）。</li>
 * </ol>
 * reconcile 探测同样只经本类触网（scanner 调 {@link #reconcile}，不直接碰 adapter）。
 */
public class FencedPublicationExecutor {

    private static final String PRODUCER = "publisher-app";

    private final GitHubWriteAdapter github; // I4：唯一引用点，勿在别处注入
    private final PublicationStore store;
    private final PayloadReader payloadReader;
    private final Map<CommandType, PublicationHandler> handlers;
    private final PublicationGate gate;
    private final RetryBackoff backoff;
    private final Duration reconcileRetryDelay;
    private final int probeMaxPages;
    /** 本部署绑定的 GitHub App installation（写前本地预检，SEC 加固） */
    private final long expectedInstallationId;

    public FencedPublicationExecutor(GitHubWriteAdapter github, PublicationStore store,
                                     PayloadReader payloadReader, List<PublicationHandler> handlerList,
                                     Duration reconcileRetryDelay, int probeMaxPages,
                                     long expectedInstallationId) {
        this.github = Objects.requireNonNull(github);
        this.store = Objects.requireNonNull(store);
        this.payloadReader = Objects.requireNonNull(payloadReader);
        this.gate = new PublicationGate();
        this.backoff = new RetryBackoff();
        this.reconcileRetryDelay = Objects.requireNonNull(reconcileRetryDelay);
        this.probeMaxPages = probeMaxPages;
        this.expectedInstallationId = expectedInstallationId;
        this.handlers = new EnumMap<>(CommandType.class);
        for (PublicationHandler handler : handlerList) {
            this.handlers.put(handler.commandType(), handler);
        }
    }

    // ---------- T3 主路径 ----------

    public PublishOutcome execute(ClaimedCommand claimed) {
        // payload 读取（CAS，事务外）；不可读 = 数据不全，fail-closed 按 schema 拒绝（E5）
        Map<String, Object> payload;
        try {
            payload = payloadReader.read(claimed.payloadHash());
        } catch (PayloadUnavailableException e) {
            payload = null;
        }
        Map<String, Object> resolvedPayload = payload;

        // ①–⑤ T3-A 单事务（E1）
        T3ADecision decision;
        try {
            decision = store.prepare(claimed.operationId().value(), claimed.leaseEpoch(),
                    ctx -> resolvedPayload == null
                            ? T3ADecision.rejectSafety("PAYLOAD_UNAVAILABLE", Map.of(
                                    "operation_id", claimed.operationId().toString()))
                            : gate.evaluate(ctx.command(), resolvedPayload, ctx.dependencies(), ctx.cursor()));
        } catch (StaleLeaseException e) {
            return PublishOutcome.DEFERRED; // 僵尸 worker（B-2）
        }
        switch (decision.action()) {
            case PROCEED -> {
                // 已 →IN_FLIGHT 并提交，继续触网
            }
            case MARK_SUPERSEDED -> {
                return PublishOutcome.SUPERSEDED;
            }
            case MARK_FAILED_TERMINAL -> {
                return PublishOutcome.FAILED_TERMINAL;
            }
            case DEFER, RECORD_GAP -> {
                return PublishOutcome.DEFERRED;
            }
            default -> throw new IllegalStateException("未知决策: " + decision.action());
        }

        // ⑤.5 installation 本地预检（SEC 加固）：命令 payload 携带的 installation_id 必须等于
        // 本部署配置的 installation——此前"命令 repo/installation 与凭证不匹配"只靠 mint 收窄 +
        // GitHub 403 兜底；不匹配 = 确定性拒绝（FAILED_TERMINAL + SAFETY_REJECTED），零触网
        if (!installationIdMatches(resolvedPayload)) {
            try {
                store.markFailedTerminal(claimed.operationId().value(), claimed.leaseEpoch(),
                        "INSTALLATION_MISMATCH", event(claimed, ExecutionEventType.SAFETY_REJECTED, Map.of(
                                "operation_id", claimed.operationId().toString(),
                                "reason", "installation_mismatch")));
            } catch (StaleLeaseException e) {
                return PublishOutcome.DEFERRED; // 租约已被收回（B-2）
            }
            return PublishOutcome.FAILED_TERMINAL;
        }

        UUID repairResourceId;
        try {
            repairResourceId = repairResourceId(resolvedPayload);
        } catch (IllegalArgumentException e) {
            store.markFailedTerminal(claimed.operationId().value(), claimed.leaseEpoch(),
                    "INVALID_REPAIR_RESOURCE", event(claimed, ExecutionEventType.SAFETY_REJECTED,
                            Map.of("operation_id", claimed.operationId().toString(),
                                    "reason", "invalid_repair_resource")));
            return PublishOutcome.FAILED_TERMINAL;
        }

        // repair probe-first：使用旧资源原命令的幂等身份；FOUND 零写，UNKNOWN 禁重发。
        if (repairResourceId != null) {
            var origin = store.findRepairOrigin(repairResourceId);
            if (origin.isEmpty()) {
                store.markFailedTerminal(claimed.operationId().value(), claimed.leaseEpoch(),
                        "REPAIR_ORIGIN_MISSING", null);
                return PublishOutcome.FAILED_TERMINAL;
            }
            ReconcileVerdict verdict = reconcile(origin.get());
            if (verdict.kind() == ReconcileVerdict.Kind.FOUND) {
                store.confirmRepairNoop(claimed.operationId().value(), claimed.leaseEpoch(),
                        repairResourceId, verdict.remoteId(), verdict.remoteUrl(),
                        event(claimed, ExecutionEventType.REPAIR_REPAIRED, Map.of(
                                "repair_operation_id", claimed.operationId().toString(),
                                "resource_id", repairResourceId.toString(), "via", "probe_first")));
                return PublishOutcome.CONFIRMED;
            }
            if (verdict.kind() == ReconcileVerdict.Kind.UNKNOWN
                    || verdict.kind() == ReconcileVerdict.Kind.PERMISSION_DENIED) {
                store.markReconciling(claimed.operationId().value(), claimed.leaseEpoch(),
                        Instant.now().plus(reconcileRetryDelay),
                        event(claimed, ExecutionEventType.PUBLICATION_OUTCOME_UNKNOWN, Map.of(
                                "operation_id", claimed.operationId().toString(),
                                "detail", "repair_probe_unknown")));
                return PublishOutcome.RECONCILING;
            }
        }

        // ⑥ 事务外触网（Handler 只翻译，不触网）
        PublicationHandler handler = handlers.get(claimed.commandType());
        TypedWriteRequest request = handler.buildRequest(claimed, resolvedPayload);
        TypedOutcome outcome;
        RetryDirective retryDirective = new RetryDirective.NotRateLimited();
        try {
            TypedResponse response = github.execute(request);
            retryDirective = RetryDirective.from(response, Instant.now());
            outcome = retryDirective.isRateLimited()
                    ? TypedOutcome.serverRetryable("github_rate_limited_status=" + response.status())
                    : handler.interpret(response);
        } catch (GitHubTransportException e) {
            // 超时/连接断 = 响应丢失：不确定是状态不是异常（§4.3），禁盲目重发
            outcome = TypedOutcome.outcomeUnknown(e.getMessage());
        }

        // ⑦ T3-B 短事务落结果
        return settle(claimed, handler, outcome, retryDirective, repairResourceId);
    }

    private PublishOutcome settle(ClaimedCommand command, PublicationHandler handler, TypedOutcome outcome,
                                  RetryDirective retryDirective, UUID repairResourceId) {
        Instant now = Instant.now();
        UUID id = command.operationId().value();
        long lease = command.leaseEpoch();
        try {
            switch (outcome.kind()) {
                case CONFIRMED -> {
                    ExecutionEvent confirmed = event(command,
                            repairResourceId == null ? ExecutionEventType.PUBLICATION_CONFIRMED
                                    : ExecutionEventType.REPAIR_REPAIRED,
                            Map.of("operation_id", command.operationId().toString(),
                                    "command_type", command.commandType().name(),
                                    "aggregate_sequence", command.aggregateSequence(),
                                    "remote_id", outcome.remoteId()));
                    if (repairResourceId == null) {
                        store.confirm(id, lease, outcome.remoteId(), outcome.remoteUrl(),
                                handler.resourceType(), handler.resourceMarker(command), confirmed);
                    } else {
                        store.confirmRepairReplacement(id, lease, repairResourceId,
                                outcome.remoteId(), outcome.remoteUrl(), handler.resourceType(),
                                handler.resourceMarker(command), confirmed);
                    }
                    return PublishOutcome.CONFIRMED;
                }
                case OUTCOME_UNKNOWN -> {
                    store.markReconciling(id, lease, now.plus(reconcileRetryDelay),
                            event(command, ExecutionEventType.PUBLICATION_OUTCOME_UNKNOWN, Map.of(
                                    "operation_id", command.operationId().toString(),
                                    "detail", String.valueOf(outcome.errorDetail()))));
                    return PublishOutcome.RECONCILING;
                }
                case SERVER_RETRYABLE -> {
                    int failedAttempts = command.attemptCount() + 1;
                    if (failedAttempts >= command.maxAttempts()) {
                        // EX-01：退避达上限熔断 MANUAL（不无限打转；不推进游标，阻塞后续）
                        store.markManual(id, lease, "RETRY_BUDGET_EXHAUSTED");
                        return PublishOutcome.MANUAL;
                    }
                    store.markRetryWait(id, lease,
                            backoff.nextAttemptAt(failedAttempts, now, retryDirective),
                            outcome.errorCode());
                    return PublishOutcome.RETRY_WAIT;
                }
                case STALE_HEAD_SUPERSEDED -> {
                    store.markSuperseded(id, lease, outcome.errorCode()); // STALE_HEAD（EX-02）
                    return PublishOutcome.SUPERSEDED;
                }
                case FAILED_TERMINAL -> {
                    store.markFailedTerminal(id, lease, outcome.errorCode(), null);
                    return PublishOutcome.FAILED_TERMINAL;
                }
                case AUTH_FAILED -> {
                    store.markFailedTerminal(id, lease, outcome.errorCode(),
                            event(command, ExecutionEventType.SAFETY_REJECTED, Map.of(
                                    "operation_id", command.operationId().toString(),
                                    "reason", "github_auth_failed",
                                    "detail", String.valueOf(outcome.errorDetail()))));
                    return PublishOutcome.FAILED_TERMINAL;
                }
                case MANUAL -> {
                    store.markManual(id, lease, outcome.errorCode());
                    return PublishOutcome.MANUAL;
                }
                default -> throw new IllegalStateException("未知 outcome: " + outcome.kind());
            }
        } catch (StaleLeaseException e) {
            return PublishOutcome.DEFERRED; // 租约已被收回，落账权在他人（B-2）
        }
    }

    // ---------- reconcile 探测（OutboxRecoveryScanner 调用；触网仍只经本类，I4） ----------

    public ReconcileVerdict reconcile(ClaimedCommand command) {
        Map<String, Object> payload;
        try {
            payload = payloadReader.read(command.payloadHash());
        } catch (PayloadUnavailableException e) {
            return ReconcileVerdict.unknown();
        }
        PublicationHandler handler = handlers.get(command.commandType());
        TypedReadRequest probe = handler.buildProbe(command, payload);

        if (probe.operation() == GitHubOperation.GET_CHECK_RUN) {
            // 单资源探针：无翻页
            try {
                TypedResponse response = github.executeRead(probe);
                return classifyProbeResponse(handler.interpretProbe(response, command), response, command);
            } catch (GitHubTransportException e) {
                return ReconcileVerdict.unknown();
            }
        }

        // 列表型探针：翻页预算封顶（EX-04，不无限翻页）
        for (int page = 1; page <= probeMaxPages; page++) {
            TypedResponse response;
            try {
                response = github.executeRead(probe.withPage(page));
            } catch (GitHubTransportException e) {
                return ReconcileVerdict.unknown();
            }
            ReconcileVerdict verdict = classifyProbeResponse(
                    handler.interpretProbe(response, command), response, command);
            if (verdict.kind() != ReconcileVerdict.Kind.NOT_FOUND) {
                return verdict; // FOUND / MANUAL_POLICY / UNKNOWN（响应形态异常不硬翻）
            }
            if (isShortPage(response, probe)) {
                return ReconcileVerdict.notFound(); // 窗口内穷尽 = 确认不存在
            }
        }
        return ReconcileVerdict.unknown(); // 超窗口未命中：查不到也不能确认
    }

    private ReconcileVerdict classifyProbeResponse(ProbeResult result, TypedResponse response,
                                                    ClaimedCommand command) {
        RetryDirective directive = RetryDirective.from(response, Instant.now());
        if (response.status() == 403 && directive instanceof RetryDirective.NotRateLimited) {
            return ReconcileVerdict.permissionDenied();
        }
        if (result instanceof ProbeResult.FoundNoContent found) {
            return ReconcileVerdict.found(found.remoteId(), found.remoteUrl());
        }
        if (result instanceof ProbeResult.FoundWithContent found) {
            return ReconcileVerdict.foundWithContent(
                    found.remoteId(), found.remoteUrl(), found.contentDigest());
        }
        if (result instanceof ProbeResult.NotFound) {
            return command.commandType() == CommandType.UPDATE_CHECK
                    ? ReconcileVerdict.manualPolicy() : ReconcileVerdict.notFound();
        }
        return ReconcileVerdict.unknown(directive);
    }

    /**
     * DriftReconciler 的 sanity 读（M1-T08，方案 §4.6）：执行 repo 级探针，200 = 通过
     * （token/权限/仓库可达）；其余状态码与传输失败一律不通过——sanity 的职责是
     * "能不能信任刚才那个 404"，任何不确定都按不通过处理（E2E-18：权限异常绝不冒充不存在）。
     * 触网仍只经本类（I4）。
     */
    public boolean sanityRead(TypedReadRequest sanityProbe) {
        try {
            return github.executeRead(sanityProbe).status() == 200;
        } catch (GitHubTransportException e) {
            return false;
        }
    }

    private boolean isShortPage(TypedResponse response, TypedReadRequest probe) {
        int perPage = ((Number) probe.parameters().getOrDefault("per_page", 100)).intValue();
        if (response.arrayBody() != null) {
            return response.arrayBody().size() < perPage;
        }
        Object checkRuns = response.objectBody() == null ? null : response.objectBody().get("check_runs");
        if (checkRuns instanceof List<?> list) {
            return list.size() < perPage;
        }
        return true; // 响应无列表 = 视为穷尽（异常形态已由 interpretProbe 归 UNKNOWN）
    }

    /** fail-closed（E5）：缺字段/非数字一律按不匹配拒绝 */
    private boolean installationIdMatches(Map<String, Object> payload) {
        Object value = payload.get("installation_id");
        return value instanceof Number n && n.longValue() == expectedInstallationId;
    }

    private static UUID repairResourceId(Map<String, Object> payload) {
        Object value = payload.get("repair_of_resource_id");
        if (value == null) return null;
        try {
            return UUID.fromString(value.toString());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("repair_of_resource_id 非 UUID", e);
        }
    }

    private ExecutionEvent event(ClaimedCommand command, ExecutionEventType type, Map<String, Object> payload) {
        return new ExecutionEvent(UUID.randomUUID(), command.reviewRunId(), command.prRevisionId(),
                null, null, type, 1, null, command.reviewRunId(), PRODUCER, payload, Instant.now());
    }
}
