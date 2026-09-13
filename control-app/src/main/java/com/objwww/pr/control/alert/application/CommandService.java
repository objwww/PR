package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.model.OperatorCommand;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.repository.OperatorCommandRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.statemachine.RcaRunStateMachine;
import com.objwww.pr.control.infrastructure.observability.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 运维命令服务（M5-14）：Cancel/Hint/Feedback——**先持久化再生效**（INV-AM5-7）。
 *
 * <p>两阶段语义：phase1 命令行 PERSISTED 落库独立提交；phase2 应用事务
 * （WC-2，方案 v2 §4.2）：Run 行锁 → CAS 终止 → RUN_CANCELLED/OPERATOR_COMMAND_
 * APPLIED 事件（joinTx，同事务）→ 命令标记 advanceState——<b>三个写同事务原子
 * 提交</b>，崩溃后 PERSISTED 行整体可重放，不再产生"CAS 落地缺事件/事件落地缺
 * 标记"的半应用窗口。
 *
 * <p>锁序：run 行先行（与 RunConfigSwitchService 的 run→command 同序；事件 append
 * 的 run 行锁同事务重入）——命令标记靠 from 锚 CAS 兜底并发，不持 command 锁跨
 * run 等待。
 *
 * <p>裁决序：幂等重放（终态行原样返回，含原拒绝态）→ 适用性恢复面（Run 已
 * CANCELLED 且本 CANCEL 为唯一候选行 = 身份证明 → 补标 APPLIED；证明不了不冒认，
 * 多候选记恢复异常交对账）→ 修订锚（expected_revision ≠ rca_run.last_event_seq →
 * REJECTED_STALE，零副作用）。Hint 标 UNTRUSTED（§3.2）：文本只落命令行 payload
 * （上下文消费面读表时以 UNTRUSTED 身份注入），不复制进事件流；事件 payload 携带
 * commandId（审计幂等按命令身份，遗留事件无该字段）。
 */
public class CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OperatorCommandRepository commands;
    private final RcaRunRepository runs;
    private final RcaEventAppender appender;
    private final TransactionOperations tx;
    private final Supplier<Instant> now;

    public CommandService(OperatorCommandRepository commands, RcaRunRepository runs,
                          RcaEventAppender appender, TransactionOperations tx,
                          Supplier<Instant> now) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.appender = Objects.requireNonNull(appender, "appender");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.now = Objects.requireNonNull(now, "now");
    }

    /** 提交结果：state=终态（APPLIED/REJECTED_*）；replayed=true = 返回的是既有命令行 */
    public record Result(UUID commandId, OperatorCommand.State state, boolean replayed) {
    }

    public Result submit(UUID runId, OperatorCommand.Type type, String idempotencyKey,
                         long expectedRevision, Map<String, Object> payload, String actor) {
        Optional<OperatorCommand> existing = commands.find(runId, type, idempotencyKey);
        if (existing.isPresent()) {
            return settle(existing.get());
        }
        OperatorCommand fresh = new OperatorCommand(UUID.randomUUID(), runId, type,
                idempotencyKey, expectedRevision, payload,
                OperatorCommand.State.PERSISTED, actor, now.get(), null);
        try {
            commands.insert(fresh);
        } catch (DuplicateKeyException e) {
            // 并发同键败者：读胜者行原样结算（恰一行落库）
            OperatorCommand winner = commands.find(runId, type, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "uq 冲突后命令行不可见: " + runId + "/" + type + "/" + idempotencyKey));
            return settle(winner);
        }
        return apply(fresh, false);
    }

    /** 结算既有行：终态原样返回；PERSISTED 行续走 apply（崩溃重放/并发败者同路） */
    private Result settle(OperatorCommand row) {
        if (row.state().isTerminal()) {
            return new Result(row.id(), row.state(), true);
        }
        return apply(row, true);
    }

    /** WC-2 应用事务：Run CAS + 事件（joinTx）+ 命令标记原子提交 */
    private Result apply(OperatorCommand cmd, boolean replayed) {
        return tx.execute(status -> doApply(cmd, replayed));
    }

    private Result doApply(OperatorCommand cmd, boolean replayed) {
        // ① 锁 Run 行（run→command 锁序，与 RunConfigSwitchService 一致）
        RcaRun run = runs.findByIdForUpdate(cmd.runId()).orElse(null);
        if (run == null) {
            return reject(cmd, OperatorCommand.State.REJECTED_FORBIDDEN, replayed);
        }
        long currentRevision = runs.currentRevision(cmd.runId()).orElse(-1);
        // ② 适用性优先于修订锚（WC-F4 修正：崩溃应用者的修订已随事件推进，
        //    先查恢复面才不会被误拒）。恢复面仅 CANCEL 且身份可证明：
        //    Run 已 CANCELLED + 本命令是该 run 唯一 CANCEL 候选行（旧事件的
        //    commandId 缺失窗口由唯一性不变量覆盖；多候选不冒认，交对账）
        if (!run.state().isActive()) {
            if (cmd.type() == OperatorCommand.Type.CANCEL
                    && run.state() == RcaRunState.CANCELLED
                    && soleCancelCandidate(cmd)) {
                return advanceApplied(cmd, replayed);
            }
            return reject(cmd, currentRevision != cmd.expectedRevision()
                    ? OperatorCommand.State.REJECTED_STALE
                    : OperatorCommand.State.REJECTED_FORBIDDEN, replayed);
        }
        // ③ 修订锚（expectedRevision ≠ last_event_seq → 零副作用拒绝）
        if (currentRevision != cmd.expectedRevision()) {
            return reject(cmd, OperatorCommand.State.REJECTED_STALE, replayed);
        }
        switch (cmd.type()) {
            case CANCEL -> {
                RcaRunStateMachine.requireTransition(run.state(), RcaRunState.CANCELLED);
                // EX-A2（F12/P1-04）：修订条件写 = 取消线性化点——CAS 与状态迁移同语句
                // （锚 last_event_seq + 活跃态守卫），finishTask 先提交 SUCCEEDED/FAILED
                // 或并发命令已推进修订 → 0 行 = 失去资格，REJECTED_STALE 零事件零变更
                if (!runs.updateIfRevision(new RcaRun(run.id(), run.incidentId(), run.generation(),
                        run.trigger(), RcaRunState.CANCELLED, run.investigationHash(),
                        run.createdAt(), now.get(), run.startedAt(), now.get(), run.lastError(),
                        // SR §3.1：取消是状态迁移非身份改写——purpose 三列照抄
                        run.purpose(), run.purposeSource(), run.completionKind()),
                        cmd.expectedRevision())) {
                    return reject(cmd, OperatorCommand.State.REJECTED_STALE, replayed);
                }
                appender.append(cmd.runId(), new RcaEventAppender.EventDraft(
                        UUID.randomUUID(), "RUN_CANCELLED",
                        eventJson(cmd.id(), "cancelled by operator", "CANCELLED")));
            }
            // Hint/Feedback 不迁移 Run：生效 = 命令可被发现（事件面留痕）；文本留在
            // 命令行 payload，上下文组装面读表时以 UNTRUSTED 身份注入（C-19③）
            case HINT -> appender.append(cmd.runId(), new RcaEventAppender.EventDraft(
                    UUID.randomUUID(), "OPERATOR_COMMAND_APPLIED",
                    eventJson(cmd.id(), "operator hint recorded", "UNTRUSTED")));
            case FEEDBACK -> appender.append(cmd.runId(), new RcaEventAppender.EventDraft(
                    UUID.randomUUID(), "OPERATOR_COMMAND_APPLIED",
                    eventJson(cmd.id(), "operator feedback recorded", null)));
            case CONFIG_SWITCH -> throw new IllegalStateException(
                    "CONFIG_SWITCH 走 RunConfigSwitchService（EN-04：本类依赖面零膨胀）");
        }
        return advanceApplied(cmd, replayed);
    }

    /**
     * 遗留半应用行的唯一候选裁决：该 run 的 CANCEL 命令行恰一条且即本命令且仍
     * PERSISTED（应用前落地的取消只可能出自它）。多候选 = 身份不可证明。
     */
    private boolean soleCancelCandidate(OperatorCommand cmd) {
        List<OperatorCommand> cancels = commands.findByRunAndType(cmd.runId(),
                OperatorCommand.Type.CANCEL);
        if (cancels.size() == 1 && cancels.get(0).id().equals(cmd.id())
                && cancels.get(0).state() == OperatorCommand.State.PERSISTED) {
            return true;
        }
        if (cancels.size() > 1) {
            StructuredLog.event(log, "command_recovery_ambiguous",
                    Map.of("run_id", cmd.runId().toString(),
                            "command_id", cmd.id().toString(),
                            "cancel_candidates", cancels.size()));
        }
        return false;
    }

    /** 应用标记（from 锚 CAS）：败者 = 并发已推进，以库内终态为准原样返回 */
    private Result advanceApplied(OperatorCommand cmd, boolean replayed) {
        if (!commands.advanceState(cmd.id(), OperatorCommand.State.PERSISTED,
                OperatorCommand.State.APPLIED, now.get())) {
            return commands.find(cmd.runId(), cmd.type(), cmd.idempotencyKey())
                    .map(row -> new Result(row.id(), row.state(), true))
                    .orElseGet(() -> new Result(cmd.id(), OperatorCommand.State.APPLIED, replayed));
        }
        return new Result(cmd.id(), OperatorCommand.State.APPLIED, replayed);
    }

    private Result reject(OperatorCommand cmd, OperatorCommand.State state, boolean replayed) {
        if (!commands.advanceState(cmd.id(), OperatorCommand.State.PERSISTED, state, now.get())) {
            return commands.find(cmd.runId(), cmd.type(), cmd.idempotencyKey())
                    .map(row -> new Result(row.id(), row.state(), true))
                    .orElseGet(() -> new Result(cmd.id(), state, replayed));
        }
        return new Result(cmd.id(), state, replayed);
    }

    /** 事件 payload 只含白名单摘要面（{"commandId","summary","state"}），命令文本不出此门 */
    private static String eventJson(UUID commandId, String summary, String state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandId", commandId.toString());
        payload.put("summary", summary);
        if (state != null) {
            payload.put("state", state);
        }
        try {
            return JSON.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("命令事件序列化失败", e);
        }
    }
}
