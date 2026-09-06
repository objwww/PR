package com.objwww.pr.control.alert.domain.budget;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 死循环熔断门（AM4 M4-08，技术方案 v1.3 §6 DoomLoopGuard——独立组件，不并入裸 step counter）。
 *
 * <p>按 (taskId, tool, actionDigest) 签名累计<b>连续无进展</b>次数：达到阈值即熔断该签名。
 * 命中仍扣一次 step——触发熔断的那次调用本身已放行（先过 {@link #isOpen} 再执行、后
 * {@link #record}），从下一次调用起该签名零 LLM/tool 调用（isOpen=false，调用方产出
 * 确定性事件，reason code = DOOM_LOOP_TRIPPED；放行侧 = DOOM_LOOP_OPEN）。熔断粘滞：
 * 不自动解除，需人工/新代际介入（换签名即新计数）。
 *
 * <p>reconciler 轮询等合法重复调用走豁免集（Policy.pollingTools）——命中豁免的工具
 * 不计数、不熔断。阈值配置化 + 版本化（Policy.version 随事件落审计）。线程安全：
 * 计数与熔断面均为并发容器。
 */
public class DoomLoopGuard {

    /** 熔断策略（配置化+版本化；pollingTools = 轮询豁免工具名集） */
    public record Policy(long maxConsecutiveNoProgress, String version, Set<String> pollingTools) {
        public Policy {
            if (maxConsecutiveNoProgress < 1) {
                throw new IllegalArgumentException(
                        "连续无进展阈值必须 ≥1，实际: " + maxConsecutiveNoProgress);
            }
            pollingTools = Set.copyOf(pollingTools);
        }
    }

    private record Signature(UUID taskId, String tool, String actionDigest) {
    }

    private final Policy policy;
    private final ConcurrentHashMap<Signature, Long> consecutiveNoProgress =
            new ConcurrentHashMap<>();
    private final Set<Signature> tripped = ConcurrentHashMap.newKeySet();

    public DoomLoopGuard(Policy policy) {
        this.policy = policy;
    }

    /** 前置门：该签名是否允许发起调用（false = 已熔断，调用方必须零 LLM/tool 直接受理） */
    public boolean isOpen(UUID taskId, String tool, String actionDigest) {
        return !tripped.contains(new Signature(taskId, tool, actionDigest));
    }

    /** 后置记录：本次调用是否有进展。返回 true = 本次记录触发（或维持）熔断 */
    public boolean record(UUID taskId, String tool, String actionDigest, boolean progressed) {
        Signature key = new Signature(taskId, tool, actionDigest);
        if (policy.pollingTools().contains(tool)) {
            return false; // 轮询豁免：合法重复不计数
        }
        if (tripped.contains(key)) {
            return true; // 粘滞
        }
        if (progressed) {
            consecutiveNoProgress.remove(key);
            return false;
        }
        long count = consecutiveNoProgress.merge(key, 1L, Long::sum);
        if (count >= policy.maxConsecutiveNoProgress()) {
            tripped.add(key);
            return true;
        }
        return false;
    }

    public Policy policy() {
        return policy;
    }

    /** 该签名当前的连续无进展计数（观测用） */
    public long noProgressCount(UUID taskId, String tool, String actionDigest) {
        return consecutiveNoProgress.getOrDefault(
                new Signature(taskId, tool, actionDigest), 0L);
    }

    /** 已熔断签名清单（观测/事件面） */
    public List<Signature> trippedSignatures() {
        return List.copyOf(tripped);
    }
}
