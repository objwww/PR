package com.objwww.pr.publisher.domain.service;

import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.domain.model.DependencyRow;
import com.objwww.pr.publisher.domain.model.SubjectCursor;
import com.objwww.pr.shared.DependencyVerdict;
import com.objwww.pr.shared.DependencyVerdictEvaluator;
import com.objwww.pr.shared.FenceVerdict;
import com.objwww.pr.shared.OutboxStateMachine;
import com.objwww.pr.shared.RevisionFence;

import java.util.List;
import java.util.Map;

/**
 * T3-A 第①–④步的纯判定（v2.2 E1/E2/E3 + F9）：schema 白名单 → 依赖终态归类 →
 * 跳号检测 → epoch fence。无副作用，全部输入由 PublicationStore 在事务行锁下供给，
 * 判定结果由其在同一事务内应用。
 */
public final class PublicationGate {

    private final DependencyVerdictEvaluator dependencyEvaluator = new DependencyVerdictEvaluator();
    private final RevisionFence revisionFence = new RevisionFence();

    public T3ADecision evaluate(ClaimedCommand command, Map<String, Object> payload,
                                List<DependencyRow> dependencies, SubjectCursor cursor) {
        // ① schema/白名单（EX-09）
        List<String> violations = CommandPayloadValidator.violations(command, payload);
        if (!violations.isEmpty()) {
            return T3ADecision.rejectSafety("SCHEMA_REJECTED", Map.of(
                    "operation_id", command.operationId().toString(),
                    "command_type", command.commandType().name(),
                    "violations", violations));
        }

        // ② depends_on 终态判定（E3 归类表）
        for (DependencyRow dep : dependencies) {
            if (!OutboxStateMachine.isTerminal(dep.prerequisiteState())) {
                return T3ADecision.defer(); // 前置未到终态：等下一轮领取
            }
            DependencyVerdict verdict = dependencyEvaluator.evaluate(dep.prerequisiteState(), dep.mode());
            switch (verdict) {
                case PROCEED -> {
                }
                case CASCADE_SUPERSEDE -> {
                    return T3ADecision.supersede("DEPENDENCY_SUPERSEDED");
                }
                case SUPERSEDE_SELF -> {
                    return T3ADecision.supersede("DEPENDENCY_FAILED");
                }
                case WAIT_MANUAL -> {
                    return T3ADecision.defer(); // 前置 MANUAL：阻塞不推进（保序 > 可用性）
                }
            }
        }

        // ③ 跳号检测（E2）：seq 必须恰为已解决游标 +1
        long expected = cursor.lastResolvedSequence() + 1;
        if (command.aggregateSequence() != expected) {
            return T3ADecision.gap(Map.of(
                    "operation_id", command.operationId().toString(),
                    "aggregate_key", command.aggregateKey(),
                    "aggregate_sequence", command.aggregateSequence(),
                    "expected_sequence", expected));
        }

        // ④ epoch fence（F9；OWNED_GENERATION 在 fence 内放行）
        FenceVerdict fence = revisionFence.check(
                command.fenceMode(), command.publicationEpoch(), cursor.publicationEpoch());
        return switch (fence) {
            case ALLOW -> T3ADecision.proceed();
            case REJECT_SUPERSEDE -> T3ADecision.supersede("STALE_EPOCH");
            case RETRYABLE -> T3ADecision.defer(); // epoch 超前 = 读取陈旧，可重试（EX-05）
        };
    }
}
