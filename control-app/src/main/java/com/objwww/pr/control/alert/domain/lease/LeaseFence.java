package com.objwww.pr.control.alert.domain.lease;

import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 提交权栅栏（EX-A2 F11，P1-04 成文沉淀；R7 ActionGuard 复用件）。
 *
 * <p>{@code acquire} = {@code requireCurrentLease} 条件 UPDATE（state=LEASED ∧
 * owner ∧ epoch）：命中的行锁持有到<b>当前事务提交</b>——同一事务内后续的
 * "结果业务准入、任务状态、事件落库"全部处于 owner/epoch/run 状态保护之下
 * （锁定所有权行后校验并写入，或等价条件写）。影响行数 0（返回 empty）=
 * 失去提交权：旧 worker 晚到 / 租约已回收 / 已他人重领——调用方<b>一行不写</b>；
 * 远端迟到响应只能作审计/对账资料保存，<b>不得进入有效证据快照</b>。
 *
 * <p>用法（收尾单事务内第一步）：
 * <pre>{@code
 * Optional<LeaseFence> fence = LeaseFence.acquire(tasks, task.id(), owner, task.leaseEpoch());
 * if (fence.isEmpty()) return LEASE_REJECTED;   // 0 行栅栏，零落档
 * // … 同事务继续：attempt 终态、结果/报告落档、task 终态、run 收尾
 * }</pre>
 */
public final class LeaseFence {

    private final UUID taskId;
    private final String owner;
    private final long leaseEpoch;

    private LeaseFence(UUID taskId, String owner, long leaseEpoch) {
        this.taskId = taskId;
        this.owner = owner;
        this.leaseEpoch = leaseEpoch;
    }

    /** 提交权栅栏：empty = 影响行数 0 = 失去提交权（调用方零落档） */
    public static Optional<LeaseFence> acquire(RcaTaskRepository tasks, UUID taskId,
                                               String owner, long leaseEpoch) {
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(owner, "owner");
        if (!tasks.requireCurrentLease(taskId, owner, leaseEpoch)) {
            return Optional.empty();
        }
        return Optional.of(new LeaseFence(taskId, owner, leaseEpoch));
    }

    public UUID taskId() {
        return taskId;
    }

    public String owner() {
        return owner;
    }

    public long leaseEpoch() {
        return leaseEpoch;
    }
}
