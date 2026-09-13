package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.RcaRunState;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * WC-3 取消传播的执行控制面（方案 v2 §5.1/§5.2）：Run 终态/租约失效是<b>持久取消
 * 事实</b>，本类只做两件事——把事实转成类型化停止信号 {@link StoppedException}，
 * 并在模型等待/工具等待/单工具入口处周期检查（进程内通知只是加速，持久事实 +
 * 周期 DB 检查才是收敛骨架；其他实例靠同一持久事实收敛，不引消息中间件）。
 *
 * <p>停止分类与 {@code REMOTE_UNAVAILABLE} 分开：前几类结束当前执行身份——
 * <b>不触发模型 fallback、不触发工具重试</b>；账本悬挂 PENDING 由恢复扫描按
 * UNKNOWN 诚实归档（远端是否已收到不可知，预算占用/对账策略不变）。
 *
 * <p>身份一律 Host 提供（runId/taskId/attemptId/leaseEpoch/deadline）——LLM 或
 * 工具参数不得自报控制身份。
 */
public final class ExecutionControl {

    /** 停止分类（封闭常量；计划三分类 + 终态兜底，实际 state 进 detail） */
    public static final String STOP_RUN_CANCELLED = "RUN_CANCELLED";
    public static final String STOP_RUN_DEADLINE_EXCEEDED = "RUN_DEADLINE_EXCEEDED";
    public static final String STOP_LEASE_LOST = "LEASE_LOST";
    /** 其余终态（SUCCEEDED/FAILED/SUPERSEDED/PARTIAL）：本执行身份已无意义 */
    public static final String STOP_RUN_TERMINAL = "RUN_TERMINAL";

    private ExecutionControl() {
    }

    /**
     * 类型化停止：执行边界（模型等待/工具等待/单工具入口/worker 心跳）检测到持久
     * 停止事实时上抛，穿透各层网关/预算门（它们对未知 RuntimeException 原样重抛）
     * 到 worker 收尾面，按 {@link #kind()} 记终态失败——绝不折成可重试 EXECUTOR_ERROR。
     */
    public static final class StoppedException extends RuntimeException {
        private final String kind;

        public StoppedException(String kind, String detail) {
            super(kind + ": " + detail);
            this.kind = Objects.requireNonNull(kind, "kind");
        }

        /** 停止分类（本类 STOP_* 常量之一） */
        public String kind() {
            return kind;
        }
    }

    /**
     * BooleanSupplier 适配（平台模型等待面契约：返回 false=失租，异常=类型化停止）：
     * 探针先行，活着返回 true。BoundedLlmRoleRunner/ContextCompactionService 的
     * ModelAction.leaseHeartbeat 共用——模型退避/限流等待由此接通取消传播。
     */
    public static java.util.function.BooleanSupplier aliveHeartbeat(Runnable controlSignal) {
        Objects.requireNonNull(controlSignal, "controlSignal");
        return () -> {
            controlSignal.run();
            return true;
        };
    }

    /**
     * Run 状态 → 停止分类（null = 活跃不停）：CANCELLED→RUN_CANCELLED；
     * EXPIRED→RUN_DEADLINE_EXCEEDED；其余终态→RUN_TERMINAL（执行身份已被
     * 收尾/取代，迟到结果由提交围栏只审计）。
     */
    public static String stopKindOf(RcaRunState state) {
        if (state == null || state.isActive()) {
            return null;
        }
        if (state == RcaRunState.CANCELLED) {
            return STOP_RUN_CANCELLED;
        }
        if (state == RcaRunState.EXPIRED) {
            return STOP_RUN_DEADLINE_EXCEEDED;
        }
        return STOP_RUN_TERMINAL;
    }

    /**
     * 控制信号（§5.2 "在这些位置检查才算接通"的共用件）：租约心跳先行（worker 心跳
     * 失租按 LEASE_LOST 契约抛 IllegalStateException，此处转类型化停止），随后探测
     * Run 现行状态——终态抛类型化停止。模型退避/限流等待、单工具入口、sweep 步进
     * 共用本信号；DB 不可读时异常穿透（fail-stop，不按缓存的 active 无限放行）。
     */
    public static Runnable controlSignal(Runnable leaseHeartbeat,
            Function<UUID, RcaRunState> runStateReader, UUID runId) {
        Objects.requireNonNull(leaseHeartbeat, "leaseHeartbeat");
        Objects.requireNonNull(runStateReader, "runStateReader");
        Objects.requireNonNull(runId, "runId");
        return () -> {
            try {
                leaseHeartbeat.run();
            } catch (StoppedException e) {
                throw e;
            } catch (IllegalStateException e) {
                // worker 心跳失租契约（消息前缀 LEASE_LOST）→ 类型化停止，
                // 不折成可重试执行错误
                throw new StoppedException(STOP_LEASE_LOST, e.getMessage());
            }
            String kind = stopKindOf(runStateReader.apply(runId));
            if (kind != null) {
                throw new StoppedException(kind,
                        "run " + runId + " 已终态，停止后续动作（迟到结果由提交围栏只审计）");
            }
        };
    }
}
