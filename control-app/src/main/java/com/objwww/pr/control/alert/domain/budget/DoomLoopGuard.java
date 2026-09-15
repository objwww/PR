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

    /**
     * 熔断策略（配置化+版本化；pollingTools = 轮询豁免工具名集）。
     * PA-A4（B v2 L5-3 五模式表）双阈值：exact repeat warn/stop + ping-pong
     * （同任务两签名交替）warn/stop。兼容构造（3 参）映射 warn=stop、ping-pong
     * 关闭——既有装配语义零变化。
     */
    public record Policy(long warnAfterNoProgress, long stopAfterNoProgress,
            long pingPongWarnAfter, long pingPongStopAfter, String version,
            Set<String> pollingTools) {
        public Policy {
            if (stopAfterNoProgress < 1 || warnAfterNoProgress < 1
                    || warnAfterNoProgress > stopAfterNoProgress) {
                throw new IllegalArgumentException("exact repeat 阈值非法（须 1 ≤ warn ≤ stop）: "
                        + warnAfterNoProgress + "/" + stopAfterNoProgress);
            }
            if (pingPongStopAfter < 4 || pingPongWarnAfter < 4
                    || pingPongWarnAfter > pingPongStopAfter) {
                throw new IllegalArgumentException("ping-pong 阈值非法（须 4 ≤ warn ≤ stop）: "
                        + pingPongWarnAfter + "/" + pingPongStopAfter);
            }
            pollingTools = Set.copyOf(pollingTools);
        }

        /** 兼容构造（EX-A1 原形态）：单阈值 = 无预警区（warn=stop），ping-pong 关闭 */
        public Policy(long maxConsecutiveNoProgress, String version, Set<String> pollingTools) {
            this(maxConsecutiveNoProgress, maxConsecutiveNoProgress,
                    Long.MAX_VALUE, Long.MAX_VALUE, version, pollingTools);
        }
    }

    private record Signature(UUID taskId, String tool, String actionDigest) {
    }

    private final Policy policy;
    private final ConcurrentHashMap<Signature, Long> consecutiveNoProgress =
            new ConcurrentHashMap<>();
    private final Set<Signature> tripped = ConcurrentHashMap.newKeySet();
    /** PA-A4 ping-pong：per-task 无进展签名序列（定长窗口 = pingPongStopAfter） */
    private final ConcurrentHashMap<UUID, java.util.ArrayDeque<Signature>> noProgressSequence =
            new ConcurrentHashMap<>();

    public DoomLoopGuard(Policy policy) {
        this.policy = policy;
    }

    /**
     * 无熔断语义环境（单元测试假件/兼容装配）的缺省门：阈值取上限恒不触发。
     * 生产装配必须用带真实 Policy 的构造（EX-A1 §4）。
     */
    public static DoomLoopGuard permissive() {
        return new DoomLoopGuard(new Policy(Long.MAX_VALUE, "permissive", Set.of()));
    }

    /** 前置门：该签名是否允许发起调用（false = 已熔断，调用方必须零 LLM/tool 直接受理） */
    public boolean isOpen(UUID taskId, String tool, String actionDigest) {
        return !tripped.contains(new Signature(taskId, tool, actionDigest));
    }

    /** PA-A4：签名是否处于 exact-repeat 预警区（≥warn 且未熔断——观测面，不拦截） */
    public boolean inExactRepeatWarningZone(UUID taskId, String tool, String actionDigest) {
        Signature key = new Signature(taskId, tool, actionDigest);
        return !tripped.contains(key)
                && consecutiveNoProgress.getOrDefault(key, 0L) >= policy.warnAfterNoProgress();
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
            noProgressSequence.remove(taskId);
            return false;
        }
        long count = consecutiveNoProgress.merge(key, 1L, Long::sum);
        if (count >= policy.stopAfterNoProgress()) {
            tripped.add(key);
            return true;
        }
        // PA-A4 ping-pong：无进展签名序列（per-task）——两签名交替达 stop 窗口即双双熔断
        java.util.ArrayDeque<Signature> sequence =
                noProgressSequence.computeIfAbsent(taskId, k -> new java.util.ArrayDeque<>());
        sequence.addLast(key);
        while (sequence.size() > policy.pingPongStopAfter()) {
            sequence.removeFirst();
        }
        if (alternating(sequence, policy.pingPongStopAfter())) {
            tripped.add(sequence.getFirst());
            tripped.add(sequence.getLast());
            return true;
        }
        return false;
    }

    public Policy policy() {
        return policy;
    }

    /** 观测面：ping-pong 预警区（窗口达 pingWarn 且两签名两两交替） */
    public boolean inPingPongWarningZone(UUID taskId) {
        java.util.ArrayDeque<Signature> sequence = noProgressSequence.get(taskId);
        if (sequence == null || sequence.size() < policy.pingPongWarnAfter()) {
            return false;
        }
        return alternating(sequence, policy.pingPongWarnAfter());
    }

    /** 该签名当前的连续无进展计数（观测用） */
    public long noProgressCount(UUID taskId, String tool, String actionDigest) {
        return consecutiveNoProgress.getOrDefault(
                new Signature(taskId, tool, actionDigest), 0L);
    }

    /** 交替判定：窗口满 window 且相邻两两不同（A,B,A,B…）且恰两签名 → ping-pong */
    private boolean alternating(java.util.ArrayDeque<Signature> sequence, long window) {
        if (sequence.size() < window || window < 4) {
            return false;
        }
        java.util.Iterator<Signature> it = sequence.iterator();
        Signature prev = it.next();
        java.util.Set<Signature> distinct = new java.util.HashSet<>();
        distinct.add(prev);
        while (it.hasNext()) {
            Signature cur = it.next();
            if (cur.equals(prev)) {
                return false;
            }
            distinct.add(cur);
            prev = cur;
        }
        return distinct.size() == 2;
    }

    /** 已熔断签名清单（观测/事件面） */
    public List<Signature> trippedSignatures() {
        return List.copyOf(tripped);
    }
}
