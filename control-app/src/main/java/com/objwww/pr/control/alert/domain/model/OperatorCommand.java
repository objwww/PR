package com.objwww.pr.control.alert.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 运维命令行（M5-14，V27 operator_command）：先持久化再生效（INV-AM5-7）——
 * 行是审计真相源，state 单向推进 PERSISTED→APPLIED/REJECTED_*；同
 * (run_id, command_type, idempotency_key) 重放返回原行（含原拒绝态）。
 *
 * <p>expectedRevision 锚 rca_run.last_event_seq（M4-10 计数器：状态事实推进必 +1、
 * 无洞单调——run 的天然修订号）；Hint 文本属 operator 输入，进上下文时以
 * {@link Type#HINT} + UNTRUSTED 身份被消费（§3.2），secret 不得入 payload
 * （提交面键名扫描 fail-closed，与 ConfigBundle 同口径）。
 */
public record OperatorCommand(
        UUID id,
        UUID runId,
        Type type,
        String idempotencyKey,
        long expectedRevision,
        Map<String, Object> payload,
        State state,
        String actor,
        Instant createdAt,
        Instant appliedAt) {

    public enum Type {
        CANCEL, HINT, FEEDBACK,

        /** EN-04 运行中热更新（§227 既有命令账本扩容复用；字段随 payload 携带） */
        CONFIG_SWITCH
    }

    public enum State {
        PERSISTED,

        /** EN-04：已过快败校验、等待 driver 安全点（非终态，可续走/可取消/可过期） */
        WAITING_SAFE_POINT,

        APPLIED, REJECTED_STALE, REJECTED_FORBIDDEN,

        /** EN-04：命令 deadline 前无安全点（§227；H14 可查询原因） */
        EXPIRED,

        /** EN-04：WAITING 行被运维撤回（H15；待命令不再应用） */
        CANCELLED;

        public boolean isTerminal() {
            return this != PERSISTED && this != WAITING_SAFE_POINT;
        }
    }

    public OperatorCommand {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(actor, "actor");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("idempotencyKey 必填且 ≤128 字符");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision 不能为负");
        }
        if (state == State.PERSISTED && appliedAt != null) {
            throw new IllegalArgumentException("PERSISTED 行不得带 appliedAt");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /** 终态推进投影（updateState 面；正文列零改动） */
    public OperatorCommand withState(State newState, Instant at) {
        return new OperatorCommand(id, runId, type, idempotencyKey, expectedRevision,
                payload, newState, actor, createdAt, at);
    }
}
