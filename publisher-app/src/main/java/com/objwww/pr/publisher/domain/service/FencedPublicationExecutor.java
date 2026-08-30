package com.objwww.pr.publisher.domain.service;

import com.objwww.pr.publisher.domain.handler.PublicationHandler;
import com.objwww.pr.publisher.domain.handler.ReconcileVerdict;
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

    public FencedPublicationExecutor(GitHubWriteAdapter github, PublicationStore store,
                                     PayloadReader payloadReader, List<PublicationHandler> handlerList,
                                     Duration reconcileRetryDelay, int probeMaxPages) {
        this.github = Objects.requireNonNull(github);
        this.store = Objects.requireNonNull(store);
        this.payloadReader = Objects.requireNonNull(payloadReader);
        this.gate = new PublicationGate();
        this.backoff = new RetryBackoff();
        this.reconcileRetryDelay = Objects.requireNonNull(reconcileRetryDelay);
        this.probeMaxPages = probeMaxPages;
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

        // ⑥ 事务外触网（Handler 只翻译，不触网）
        PublicationHandler handler = handlers.get(claimed.commandType());
        TypedWriteRequest request = handler.buildRequest(claimed, resolvedPayload);
        TypedOutcome outcome;
        try {
            outcome = handler.interpret(github.execute(request));
        } catch (GitHubTransportException e) {
            // 超时/连接断 = 响应丢失：不确定是状态不是异常（§4.3），禁盲目重发
            outcome = TypedOutcome.outcomeUnknown(e.getMessage());
        }

        // ⑦ T3-B 短事务落结果
        return settle(claimed, handler, outcome);
    }

    private PublishOutcome settle(ClaimedCommand command, PublicationHandler handler, TypedOutcome outcome) {
        Instant now = Instant.now();
        UUID id = command.operationId().value();
        long lease = command.leaseEpoch();
        try {
            switch (outcome.kind()) {
                case CONFIRMED -> {
                    store.confirm(id, lease, outcome.remoteId(), outcome.remoteUrl(),
                            handler.resourceType(), handler.resourceMarker(command),
                            event(command, ExecutionEventType.PUBLICATION_CONFIRMED, Map.of(
                                    "operation_id", command.operationId().toString(),
                                    "command_type", command.commandType().name(),
                                    "aggregate_sequence", command.aggregateSequence(),
                                    "remote_id", outcome.remoteId())));
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
                    store.markRetryWait(id, lease, backoff.nextAttemptAt(failedAttempts, now),
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
                return handler.interpretProbe(github.executeRead(probe), command);
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
            ReconcileVerdict verdict = handler.interpretProbe(response, command);
            if (verdict.kind() != ReconcileVerdict.Kind.NOT_FOUND) {
                return verdict; // FOUND / MANUAL_POLICY / UNKNOWN（响应形态异常不硬翻）
            }
            if (isShortPage(response, probe)) {
                return ReconcileVerdict.notFound(); // 窗口内穷尽 = 确认不存在
            }
        }
        return ReconcileVerdict.unknown(); // 超窗口未命中：查不到也不能确认
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

    private ExecutionEvent event(ClaimedCommand command, ExecutionEventType type, Map<String, Object> payload) {
        return new ExecutionEvent(UUID.randomUUID(), command.reviewRunId(), command.prRevisionId(),
                null, null, type, 1, null, command.reviewRunId(), PRODUCER, payload, Instant.now());
    }
}
