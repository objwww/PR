package com.objwww.pr.control.domain.ai;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * per-quota-scope 冷却登记（§4.5/附录 A G5）：进程内存态，与熔断器同生命周期（R-M3）。
 *
 * <p>账号级故障（QuotaTemporary/QuotaExhausted/账号级通用 Throttling）终态落账时写入冷却，
 * 冷却期内任何同 quota_scope 路由的触网决策降级为 Defer/快败。
 * 时间为墙钟 Instant（notBefore 语义本就来自供应商的墙钟承诺）；
 * now 由调用方注入，测试可控。
 */
public final class QuotaCooldownRegistry {

    private final Map<String, Instant> cooldownUntil = new ConcurrentHashMap<>();

    /** 标记配额域进入冷却期（取更晚者，不被早的覆盖）。 */
    public void markCoolingUntil(String quotaScope, Instant until) {
        Objects.requireNonNull(quotaScope, "quotaScope");
        Objects.requireNonNull(until, "until");
        cooldownUntil.merge(quotaScope, until, (a, b) -> a.isAfter(b) ? a : b);
    }

    /** true = 冷却中（禁止触网）；过期条目顺手清理。 */
    public boolean isCooling(String quotaScope, Instant now) {
        return coolingUntil(quotaScope, now) != null;
    }

    /**
     * 冷却截止时间（G5 的 Defer notBefore 取值处——不得硬编码）；
     * 未冷却/已过期返回 null。
     */
    public Instant coolingUntil(String quotaScope, Instant now) {
        Objects.requireNonNull(quotaScope, "quotaScope");
        Objects.requireNonNull(now, "now");
        Instant until = cooldownUntil.get(quotaScope);
        if (until == null) {
            return null;
        }
        if (now.isBefore(until)) {
            return until;
        }
        cooldownUntil.remove(quotaScope, until);
        return null;
    }
}
