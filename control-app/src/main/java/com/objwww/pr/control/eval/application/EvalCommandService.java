package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.model.EvalLaunchPlan;
import com.objwww.pr.control.eval.domain.model.EvalRunCommand;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import com.objwww.pr.control.eval.domain.repository.EvalRunCommandRepository;
import com.objwww.pr.control.eval.domain.statemachine.EvalRunLifecycle;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * EV-04 评测发起/取消命令服务（/api/eval 写面应用层；control_app 身份——
 * V81 只授 eval_run_command select,insert，本服务无 eval_run 写面，HTTP 线程
 * 不跑批：命令先持久化，执行归 eval worker）。
 *
 * <p>冻结语义：
 * <ul>
 *   <li><b>EU09 幂等</b>：同 (LAUNCH, idempotencyKey) 撞 uq → 读既有行比
 *       payload_hash——同计划返回原 run（replayed），异计划 409；</li>
 *   <li><b>取消受理</b>：仅 RUNNING 合法（{@link EvalRunLifecycle#cancelLegal}），
 *       终态 409 零副作用；受理只表示"取消中"——推进归 worker 案例边界检查点，
 *       读面 cancel_requested_at 立即可见；同键重放返回原命令，重复取消（异键）
 *       同语义再落一行（命令账本即审计）；</li>
 *   <li><b>actor</b> 唯一来源 = 认证主体（AuthenticatedActor），请求体不自报。</li>
 * </ul>
 */
public class EvalCommandService {

    public enum LaunchStatus {ACCEPTED, REPLAYED, CONFLICT}

    public record LaunchResult(LaunchStatus status, UUID runId, UUID commandId) {
    }

    public enum CancelStatus {ACCEPTED, REPLAYED, CONFLICT_TERMINAL, CONFLICT_KEY}

    public record CancelResult(CancelStatus status, UUID runId, UUID commandId) {
    }

    private final EvalRunCommandRepository commands;
    private final EvalQueryReader reader;
    private final ObjectMapper mapper;

    public EvalCommandService(EvalRunCommandRepository commands, EvalQueryReader reader,
                              ObjectMapper mapper) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    // ------------------------------------------------------------------ 发起

    /** 幂等发起：新命令落库 → ACCEPTED；同键同计划 → REPLAYED（原 run）；异计划 → CONFLICT */
    public LaunchResult launch(EvalLaunchPlan plan, String idempotencyKey, String actor) {
        validateKey(idempotencyKey);
        UUID runId = UUID.randomUUID();
        EvalRunCommand command = EvalRunCommand.pending(UUID.randomUUID(),
                EvalRunCommand.Type.LAUNCH, runId, idempotencyKey,
                planJson(plan), plan.payloadHash().value(), actor, Instant.now());
        try {
            commands.insert(command);
            return new LaunchResult(LaunchStatus.ACCEPTED, runId, command.id());
        } catch (DuplicateKeyException e) {
            // 并发/重试同键：读胜者行比 payload_hash（EU09 判定锚）
            EvalRunCommand existing = commands
                    .findByKey(EvalRunCommand.Type.LAUNCH, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "幂等键撞 uq 但读不到既有行: " + idempotencyKey, e));
            if (existing.payloadHash().equals(command.payloadHash())) {
                return new LaunchResult(LaunchStatus.REPLAYED, existing.evalRunId(),
                        existing.id());
            }
            return new LaunchResult(LaunchStatus.CONFLICT, existing.evalRunId(),
                    existing.id());
        }
    }

    // ------------------------------------------------------------------ 取消

    /**
     * 取消受理：run 不存在 → empty（404 面）；同键重放（含跨 run 键盗用 409）→
     * REPLAYED/CONFLICT_KEY；终态 → CONFLICT_TERMINAL（409 面）；合法 → 落 CANCEL
     * 命令 ACCEPTED（"取消中"，推进归 worker 检查点）。
     */
    public Optional<CancelResult> cancel(UUID runId, String idempotencyKey,
                                         String reason, String actor) {
        validateKey(idempotencyKey);
        if (reader.findRun(runId).isEmpty()) {
            return Optional.empty();
        }
        Optional<EvalRunCommand> existing =
                commands.findByKey(EvalRunCommand.Type.CANCEL, idempotencyKey);
        if (existing.isPresent()) {
            EvalRunCommand prior = existing.get();
            if (!prior.evalRunId().equals(runId)) {
                // 同键指向别的 run：键盗用/串台，显式 409（不静默并入）
                return Optional.of(new CancelResult(CancelStatus.CONFLICT_KEY, runId,
                        prior.id()));
            }
            return Optional.of(new CancelResult(CancelStatus.REPLAYED, runId, prior.id()));
        }
        EvalRun.EvalRunState state = runState(runId);
        if (!EvalRunLifecycle.cancelLegal(state)) {
            return Optional.of(new CancelResult(CancelStatus.CONFLICT_TERMINAL, runId, null));
        }
        EvalRunCommand command = EvalRunCommand.pending(UUID.randomUUID(),
                EvalRunCommand.Type.CANCEL, runId, idempotencyKey,
                cancelJson(reason), cancelHash(runId, reason), actor, Instant.now());
        try {
            commands.insert(command);
        } catch (DuplicateKeyException e) {
            // 并发同键双提交（读检查与插入之间撞 uq）：读胜者行重放
            EvalRunCommand winner = commands
                    .findByKey(EvalRunCommand.Type.CANCEL, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "幂等键撞 uq 但读不到既有行: " + idempotencyKey, e));
            return Optional.of(new CancelResult(CancelStatus.REPLAYED, runId, winner.id()));
        }
        return Optional.of(new CancelResult(CancelStatus.ACCEPTED, runId, command.id()));
    }

    // ------------------------------------------------------------------ 内部

    private EvalRun.EvalRunState runState(UUID runId) {
        String state = reader.findRun(runId)
                .orElseThrow(() -> new IllegalStateException("run 在受理窗口内消失: " + runId))
                .state();
        return EvalRun.EvalRunState.valueOf(state);
    }

    private static void validateKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("idempotencyKey 必填且 ≤128 字符");
        }
    }

    /** launch_plan jsonb 快照（固定字段序；契约字段名 = EvalLaunchPlan 分量名） */
    private String planJson(EvalLaunchPlan plan) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("displayName", plan.displayName());
        map.put("mode", plan.mode());
        map.put("datasetVersion", plan.datasetVersion());
        map.put("model", plan.model());
        map.put("promptVersion", plan.promptVersion());
        map.put("budgetMaxTokens", plan.budgetMaxTokens());
        map.put("maxConcurrency", plan.maxConcurrency());
        map.put("deadlineSeconds", plan.deadlineSeconds());
        map.put("roundsPerScenario", plan.roundsPerScenario());
        try {
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("launch_plan 序列化失败", e);
        }
    }

    private String cancelJson(String reason) {
        try {
            return mapper.writeValueAsString(
                    Map.of("reason", reason == null ? "" : reason));
        } catch (Exception e) {
            throw new IllegalStateException("cancel payload 序列化失败", e);
        }
    }

    private static String cancelHash(UUID runId, String reason) {
        return com.objwww.pr.shared.Digest.sha256Of(
                "eval-cancel/v1|runId=" + runId + "|reason=" + reason).value();
    }
}
