package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.model.OperatorCommand;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.repository.OperatorCommandRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.statemachine.RcaRunStateMachine;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 运维命令服务（M5-14）：Cancel/Hint/Feedback——**先持久化再生效**（INV-AM5-7）。
 *
 * <p>两阶段语义（FUT-33 断线不改 Run 状态的结构面）：phase1 命令行 PERSISTED 落库
 * 独立提交；phase2 生效（校验 revision/状态 → 迁移 Run / 落账事件 → 终态推进）。
 * phase2 前崩溃：命令行存续，同幂等键 retry 续走 apply；生效已落、标记未落的
 * 窗口：重放走恢复面补标 APPLIED，不重放事件（幂等二次生效零容忍）。
 *
 * <p>裁决序：幂等重放（终态行原样返回，含原拒绝态）→ 修订锚（expected_revision
 * ≠ rca_run.last_event_seq → REJECTED_STALE，零副作用）→ 适用性（终态 Run 拒绝
 * 一切命令 → REJECTED_FORBIDDEN）。Hint 标 UNTRUSTED（§3.2）：文本只落命令行
 * payload（上下文消费面读表时以 UNTRUSTED 身份注入），不复制进事件流。
 */
public class CommandService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OperatorCommandRepository commands;
    private final RcaRunRepository runs;
    private final RcaEventAppender appender;
    private final Supplier<Instant> now;

    public CommandService(OperatorCommandRepository commands, RcaRunRepository runs,
                          RcaEventAppender appender, Supplier<Instant> now) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.appender = Objects.requireNonNull(appender, "appender");
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

    private Result apply(OperatorCommand cmd, boolean replayed) {
        RcaRun run = runs.findById(cmd.runId()).orElse(null);
        if (run == null) {
            return reject(cmd, OperatorCommand.State.REJECTED_FORBIDDEN, replayed);
        }
        long currentRevision = runs.currentRevision(cmd.runId()).orElse(-1);
        if (currentRevision != cmd.expectedRevision()) {
            return reject(cmd, OperatorCommand.State.REJECTED_STALE, replayed);
        }
        if (!run.state().isActive()) {
            // 恢复面：CANCEL 生效已落（run 已终态）而标记未落 → 补标，不重放事件
            if (cmd.type() == OperatorCommand.Type.CANCEL
                    && run.state() == RcaRunState.CANCELLED) {
                return markApplied(cmd, replayed);
            }
            return reject(cmd, OperatorCommand.State.REJECTED_FORBIDDEN, replayed);
        }
        switch (cmd.type()) {
            case CANCEL -> {
                RcaRunStateMachine.requireTransition(run.state(), RcaRunState.CANCELLED);
                // EX-A2（F12/P1-04）：修订条件写 = 取消线性化点——CAS 与状态迁移同语句
                // （锚 last_event_seq + 活跃态守卫），finishTask 先提交 SUCCEEDED/FAILED
                // 或并发命令已推进修订 → 0 行 = 失去资格，REJECTED_STALE 零事件零变更
                if (!runs.updateIfRevision(new RcaRun(run.id(), run.incidentId(), run.generation(),
                        run.trigger(), RcaRunState.CANCELLED, run.investigationHash(),
                        run.createdAt(), now.get(), run.startedAt(), now.get(), run.lastError()),
                        cmd.expectedRevision())) {
                    return reject(cmd, OperatorCommand.State.REJECTED_STALE, replayed);
                }
                appender.appendIndependent(cmd.runId(), new RcaEventAppender.EventDraft(
                        UUID.randomUUID(), "RUN_CANCELLED",
                        eventJson("cancelled by operator", "CANCELLED")));
            }
            // Hint/Feedback 不迁移 Run：生效 = 命令可被发现（事件面留痕）；文本留在
            // 命令行 payload，上下文组装面读表时以 UNTRUSTED 身份注入（C-19③）
            case HINT -> appender.appendIndependent(cmd.runId(), new RcaEventAppender.EventDraft(
                    UUID.randomUUID(), "OPERATOR_COMMAND_APPLIED",
                    eventJson("operator hint recorded", "UNTRUSTED")));
            case FEEDBACK -> appender.appendIndependent(cmd.runId(), new RcaEventAppender.EventDraft(
                    UUID.randomUUID(), "OPERATOR_COMMAND_APPLIED",
                    eventJson("operator feedback recorded", null)));
            case CONFIG_SWITCH -> throw new IllegalStateException(
                    "CONFIG_SWITCH 走 RunConfigSwitchService（EN-04：本类依赖面零膨胀）");
        }
        return markApplied(cmd, replayed);
    }

    private Result markApplied(OperatorCommand cmd, boolean replayed) {
        if (!commands.updateState(cmd.id(), OperatorCommand.State.APPLIED, now.get())) {
            // 并发下标记被他人抢推：以库内终态为准原样返回
            return commands.find(cmd.runId(), cmd.type(), cmd.idempotencyKey())
                    .map(row -> new Result(row.id(), row.state(), true))
                    .orElseGet(() -> new Result(cmd.id(), OperatorCommand.State.APPLIED, replayed));
        }
        return new Result(cmd.id(), OperatorCommand.State.APPLIED, replayed);
    }

    private Result reject(OperatorCommand cmd, OperatorCommand.State state, boolean replayed) {
        commands.updateState(cmd.id(), state, now.get());
        return new Result(cmd.id(), state, replayed);
    }

    /** 事件 payload 只含白名单摘要面（{"summary","state"}），命令文本不出此门 */
    private static String eventJson(String summary, String state) {
        Map<String, Object> payload = new LinkedHashMap<>();
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
