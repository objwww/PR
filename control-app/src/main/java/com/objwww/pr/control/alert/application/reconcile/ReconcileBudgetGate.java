package com.objwww.pr.control.alert.application.reconcile;

/**
 * 六类 Reconciler 预算统一件（AM4 M4-37）：所有 Reconciler 类别共用的自循环
 * 准入门（kind 仅作类别标签进事件，六类同规则）。每轮自循环前求值，三态分流
 * （评审裁定）：
 * <ol>
 *   <li><b>预算耗尽 → 终态</b>：attempts / 墙钟 / 积压年龄任一硬维越限即
 *       TERMINAL + 确定性事件（调用方停止自循环并落事件账本）；积压年龄的窗口
 *       起点由调用方挂定传入（挂窗口起点，组件不重算、不漂移）；</li>
 *   <li><b>速率/临时失败 → 退避</b>：指数退避 base*2^attempts、封顶 cap，
 *       返回下次允许时刻；</li>
 *   <li><b>降级续跑白名单化</b>：源降级时仅当本轮工作声明为白名单只读才放行
 *       PROCEED_DEGRADED（带事件记录降级原因）；非白名单退避；且降级永不突破
 *       attempts/墙钟/积压年龄硬维（硬维判定先于降级），不得绕过权限/代际/证据
 *       约束（本门只管预算维，权限/代际/证据由既有闸执行）。</li>
 * </ol>
 * 纯函数无 I/O：事件由调用方落账本，状态由调用方持久化。
 *
 * @author wanghua
 * @date 2026-09-05
 */
public final class ReconcileBudgetGate {

    /** 自循环分流结局 */
    public enum Routing {PROCEED, PROCEED_DEGRADED, BACKOFF, TERMINAL}

    /** 上一轮失败性质（NONE=无失败；RATE_OR_TEMPORARY=速率/临时，触发退避） */
    public enum Failure {NONE, RATE_OR_TEMPORARY}

    /**
     * 门配置（六类各自实例化；全部必须为正）
     *
     * @param maxAttempts         最大自循环次数
     * @param wallClockMillis     墙钟上限（自 startedAt 起）
     * @param maxBacklogAgeMillis 积压年龄上限（自调用方挂定的窗口起点起）
     * @param backoffBaseMillis   退避基值
     * @param backoffCapMillis    退避封顶
     */
    public record GateConfig(long maxAttempts, long wallClockMillis,
            long maxBacklogAgeMillis, long backoffBaseMillis, long backoffCapMillis) {

        public GateConfig {
            if (maxAttempts < 1 || wallClockMillis < 1 || maxBacklogAgeMillis < 1
                    || backoffBaseMillis < 1 || backoffCapMillis < 1) {
                throw new IllegalArgumentException("门配置全部必须为正: " + maxAttempts + "/"
                        + wallClockMillis + "/" + maxBacklogAgeMillis + "/"
                        + backoffBaseMillis + "/" + backoffCapMillis);
            }
            if (backoffCapMillis < backoffBaseMillis) {
                throw new IllegalArgumentException("退避封顶不得小于基值: "
                        + backoffCapMillis + " < " + backoffBaseMillis);
            }
        }
    }

    /** 门状态（调用方持久化；backlogWindowStart = 挂定的积压窗口起点，不漂移） */
    public record GateState(long attemptsUsed, long startedAtMillis,
            long backlogWindowStartMillis) {
    }

    /** 确定性事件（TERMINAL 必产；降级路径必产——记录降级原因） */
    public record GateEvent(String reconcilerKind, String reason, long occurredAtMillis) {
    }

    /** 求值结局：分流路由 + 下次允许时刻（仅 BACKOFF）+ 事件（可空） */
    public record GateOutcome(Routing routing, long nextRetryAtMillis, GateEvent event) {
    }

    /**
     * 一轮自循环前求值。判定序 = 硬维（attempts → 墙钟 → 积压年龄）→ 临时失败
     * 退避 → 降级分流 → 健康放行。
     */
    public GateOutcome evaluate(String reconcilerKind, GateConfig config, GateState state,
            Failure failure, boolean sourceDegraded, boolean nextWorkReadOnlyWhitelisted,
            long nowMillis) {
        if (state.attemptsUsed() >= config.maxAttempts()) {
            return terminal(reconcilerKind, "ATTEMPTS_EXHAUSTED: 自循环次数耗尽 "
                    + state.attemptsUsed() + "/" + config.maxAttempts(), nowMillis);
        }
        if (nowMillis - state.startedAtMillis() >= config.wallClockMillis()) {
            return terminal(reconcilerKind, "WALL_CLOCK_EXHAUSTED: 墙钟耗尽", nowMillis);
        }
        if (nowMillis - state.backlogWindowStartMillis() >= config.maxBacklogAgeMillis()) {
            return terminal(reconcilerKind,
                    "BACKLOG_AGE_EXHAUSTED: 积压年龄超限（窗口起点挂定不漂移）", nowMillis);
        }
        if (failure == Failure.RATE_OR_TEMPORARY) {
            long backoff = backoffMillis(config, state.attemptsUsed());
            return new GateOutcome(Routing.BACKOFF, nowMillis + backoff, null);
        }
        if (sourceDegraded) {
            if (nextWorkReadOnlyWhitelisted) {
                return new GateOutcome(Routing.PROCEED_DEGRADED, 0, new GateEvent(
                        reconcilerKind,
                        "DEGRADED_CONTINUE: 源降级，白名单只读工作降级续跑（剩余预算内，"
                                + "记录降级原因）", nowMillis));
            }
            return new GateOutcome(Routing.BACKOFF, nowMillis
                    + backoffMillis(config, state.attemptsUsed()), new GateEvent(
                    reconcilerKind,
                    "DEGRADED_DEFERRED: 源降级且本轮工作不在只读白名单，退避等恢复",
                    nowMillis));
        }
        return new GateOutcome(Routing.PROCEED, 0, null);
    }

    /** 指数退避 base*2^attempts，封顶 cap（位移封顶防溢出） */
    private static long backoffMillis(GateConfig config, long attemptsUsed) {
        long shifted = attemptsUsed >= 62 ? Long.MAX_VALUE
                : config.backoffBaseMillis() * (1L << attemptsUsed);
        if (shifted < 0) {
            return config.backoffCapMillis(); // 溢出为负即封顶
        }
        return Math.min(shifted, config.backoffCapMillis());
    }

    private static GateOutcome terminal(String kind, String reason, long nowMillis) {
        return new GateOutcome(Routing.TERMINAL, 0, new GateEvent(kind, reason, nowMillis));
    }
}
