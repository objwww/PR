package com.objwww.pr.control.alert.application.agent;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 角色轮次回环守卫（PA-A4，B v2 L5-3 五模式之 monologue 维；设计基线
 * 《方案Bv2严格执行-全量纳入设计-v2.md》§3.2 三层互补律）。
 *
 * <p>检测面 = 角色 runner 的<b>模型轮</b>：连续未发起任何工具调用的轮次计数
 * （monologue，连续独白）。阈值双档（B v2 表）：warn 2 / stop 3——warn 仅标记
 * （观测面），stop 由调用方升级确定性兜底（deterministicFinal，PAUSE/ESCALATE 同族，
 * 不烧模型）。工具调用（含失败——发起即非独白）与委派批获批后的子 Agent 工具活动
 * 都会重置计数；步数耗尽兜底与 Hard Budget 仍是绝对上限（三层互补：DoomLoopGuard=
 * 工具签名重复、RoleLoopGuard=轮次独白、Budget=绝对上限）。
 *
 * <p>进程内状态（run 恢复归零可接受——与 DoomLoopGuard 同律，粘滞面由检查点/
 * 步数上限兜底）；配置化 + 版本化。
 */
public class RoleLoopGuard {

    /** 轮次回环级别：NONE 正常 / WARN 预警区（观测标记）/ STOP 触发硬停 */
    public enum Level { NONE, WARN, STOP }

    /** 独白阈值策略（配置化+版本化，随审计事件落 version） */
    public record Policy(int monologueWarn, int monologueStop, String version) {
        public Policy {
            Objects.requireNonNull(version, "version");
            if (monologueWarn < 1 || monologueStop < monologueWarn) {
                throw new IllegalArgumentException("独白阈值非法（须 1 ≤ warn ≤ stop）: "
                        + monologueWarn + "/" + monologueStop);
            }
        }
    }

    public static final Policy PERMISSIVE_POLICY = new Policy(Integer.MAX_VALUE,
            Integer.MAX_VALUE, "permissive");

    private final Policy policy;
    private final Map<UUID, Integer> monologueRoundsByTask = new ConcurrentHashMap<>();

    public RoleLoopGuard(Policy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** 无回环语义环境（单元测试假件/兼容装配）：阈值取上限恒不触发 */
    public static RoleLoopGuard permissive() {
        return new RoleLoopGuard(PERMISSIVE_POLICY);
    }

    /**
     * 一次模型轮结算：toolUsed = 本轮是否发起了工具调用（发起即非独白，含失败）。
     * 返回本轮结束后的回环级别；STOP 时调用方应升级确定性兜底并终止本轮驱动。
     */
    public Level recordRound(UUID taskId, boolean toolUsed) {
        Objects.requireNonNull(taskId, "taskId");
        if (toolUsed) {
            monologueRoundsByTask.remove(taskId);
            return Level.NONE;
        }
        int rounds = monologueRoundsByTask.merge(taskId, 1, Integer::sum);
        if (rounds >= policy.monologueStop()) {
            return Level.STOP;
        }
        if (rounds >= policy.monologueWarn()) {
            return Level.WARN;
        }
        return Level.NONE;
    }

    /** 观测面：当前连续独白轮数 */
    public int monologueRounds(UUID taskId) {
        return monologueRoundsByTask.getOrDefault(taskId, 0);
    }

    public Policy policy() {
        return policy;
    }
}
