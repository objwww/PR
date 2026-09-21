package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.domain.mutation.RcaOperation;

import java.util.Objects;

/**
 * Action Runner 端口（B v2 §1：Worker 提议意图，Runner 执行 mutation——执行权与
 * 调查权物理分离）。Phase B 只有 dry-run 实现；PD-D1 起真执行面随 scoped unlock
 * （mutation_unlock_registry 三元放行）引入，BA-191 落地首个真执行器
 * （service.rollback × flagd 旗标资源，按 action_id 路由）。
 *
 * <p>Runner 契约：网络 timeout ≠ failed——返回 {@code TIMEOUT_UNKNOWN} 时
 * Operation 进 UNKNOWN（锁保持 BUSY，reconcile 确认真实世界状态），不猜 FAILED。
 * {@code FAILED} 仅用于<b>确定性判败</b>（副作用未发生或已证伪：参数面缺失、
 * 所有权冲突、生效而证据落账失败）——Operation 进 FAILED_CONFIRMED 终态
 * （锁按释放矩阵放行），中文原因随事件留痕，不假装成功、不自动重试。
 */
public interface ActionRunner {

    enum Outcome { EXECUTED, TIMEOUT_UNKNOWN, FAILED }

    /** 执行结局 + 详情（FAILED 的中文判败原因 / UNKNOWN 的未知缘由；EXECUTED 可空） */
    record Result(Outcome outcome, String detail) {

        public Result {
            Objects.requireNonNull(outcome, "outcome");
        }

        public static Result of(Outcome outcome) {
            return new Result(outcome, null);
        }
    }

    /**
     * dry-run 执行：仅验证派发参数完整性（digest/resource/params 在档），不触任何
     * 外部系统；返回模拟结局（SUCCEED 配置 → EXECUTED；CHAOS_TIMEOUT →
     * TIMEOUT_UNKNOWN，用于 UNKNOWN→RECONCILING 链演示）。
     */
    Outcome run(RcaOperation operation);

    /** BA-191：携带详情的执行面（旧实现经本默认适配，detail=null 零行为变化） */
    default Result runDetailed(RcaOperation operation) {
        return Result.of(run(operation));
    }
}
