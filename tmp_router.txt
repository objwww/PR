package com.objwww.pr.control.release.application;

import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.release.domain.model.CanaryDecision;
import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.control.release.domain.repository.CanaryDecisionLogRepository;
import com.objwww.pr.control.release.domain.repository.CanaryDecisionLogRepository.DecisionRow;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.domain.service.CanaryBucketer;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Canary 路由决策（M5-10；方案 §4.1）：新 Run 铸造点调用——读 active bundle →
 * 稳定分桶（{@link CanaryBucketer}）→ 路由决策四列 + 审计行。冻结面：
 * <ul>
 *   <li><b>无 stickiness key = 拒绝放量</b>（NO_STICKINESS_KEY 落 HOLMES 主路径，
 *       run 照常创建——拒绝的是放量不是调查）；</li>
 *   <li><b>爆炸半径上限</b>：NATIVE 实跑计数（WHITELISTED+BUCKETED_NATIVE）达
 *       max_native_runs → 自动停放量（BLAST_RADIUS_STOPPED）+ WARN 告警日志；
 *       重数源在审计表，无状态重查即自愈、无开关行可被绕过；</li>
 *   <li><b>立即回退 Holmes 为一等操作</b>：nativeReady=false（NATIVE 执行面未接线，
 *       O-3/M6-01）时 NATIVE 意愿降级 HOLMES（NATIVE_DEFERRED，桶位照记）；</li>
 *   <li><b>每次路由必落审计行</b>（决策/比例/bucket/digest 全记录，E2E-AM5-05 面）。</li>
 * </ul>
 *
 * <p>canary 配置面（bundle content.canary）：percent（0..100）、whitelist[]、
 * max_native_runs（>0）；段缺失 = CANARY_DISABLED。生产 1% Canary 属 M6-01
 * （方案 §12.3 原文），本类只提供决策面不持有放量开关。
 */
public class CanaryRouter {

    private static final Logger log = LoggerFactory.getLogger(CanaryRouter.class);

    private static final String CANARY_SECTION = "canary";
    private static final String PERCENT_KEY = "percent";
    private static final String WHITELIST_KEY = "whitelist";
    private static final String MAX_NATIVE_RUNS_KEY = "max_native_runs";
    private static final int TOTAL_WEIGHT = 100;

    private final ConfigBundleRepository bundles;
    private final CanaryDecisionLogRepository decisions;
    private final boolean nativeReady;
    private final Supplier<Instant> clock;

    public CanaryRouter(ConfigBundleRepository bundles,
                        CanaryDecisionLogRepository decisions,
                        boolean nativeReady,
                        Supplier<Instant> clock) {
        this.bundles = Objects.requireNonNull(bundles, "bundles 不得为 null");
        this.decisions = Objects.requireNonNull(decisions, "decisions 不得为 null");
        this.nativeReady = nativeReady;
        this.clock = Objects.requireNonNull(clock, "clock 不得为 null");
    }

    /**
     * AM4 冻结面接线用降级路由器：恒 NO_ACTIVE_BUNDLE 全 HOLMES、零仓储触达、
     * 零审计行（无 ConfigBundle 装配的测试/轻量上下文）。
     */
    public static CanaryRouter holmesOnly() {
        return new CanaryRouter(new NullBundles(), new NullDecisions(), false, Instant::now);
    }

    /**
     * 路由决策（run 铸造点；runId 先于 insert 生成故随请求携带）。
     *
     * <p>BA-60 / V35：审计行 run_id 只随 NATIVE 出路（WHITELISTED/BUCKETED_NATIVE）
     * 落值——这些出路必有同 id 的 run 行落库（V31 deferred FK 提交点原子对）；
     * HOLMES 意愿/止损类出路（BUCKETED_HOLMES 等）M6-07 起永不铸 run，run_id 落
     * NULL——若照抄预生成 id 即幽灵引用，提交点必 23503。审计其余六列照记不变。
     *
     * @param groupId stickiness 键组段（如 incident groupKey）；id 为会话/实例段
     */
    public RcaRunRouting route(UUID runId, String groupId, String id) {
        Objects.requireNonNull(runId, "runId 不得为 null");
        Instant now = clock.get();

        // ① 无 active bundle：全量主路径
        Optional<Digest> active = bundles.activeDigest();
        if (active.isEmpty()) {
            return record(null, null, null, 0, null, CanaryDecision.NO_ACTIVE_BUNDLE, now);
        }
        Digest configDigest = active.get();

        // ② bundle 无 canary 段：放量未启用（percent 显式 0 不是"未配置"——
        //    照常分桶记录，恒 BUCKETED_HOLMES）
        Map<String, Object> canary = canarySection(configDigest);
        Integer percent = intOf(canary.get(PERCENT_KEY));
        if (canary.isEmpty() || percent == null || percent < 0) {
            return record(null, null, null, 0, configDigest,
                    CanaryDecision.CANARY_DISABLED, now);
        }
        if (percent > TOTAL_WEIGHT) {
            throw new IllegalStateException("canary.percent 超域 [0,100]: " + percent);
        }

        // ③ 缺 stickiness key：拒绝放量（修 Unleash random 回退坑）
        String stickinessKey;
        try {
            stickinessKey = CanaryBucketer.normalizedKey(groupId, id);
        } catch (IllegalArgumentException e) {
            return record(null, null, null, percent, configDigest,
                    CanaryDecision.NO_STICKINESS_KEY, now);
        }

        // ④ 分桶（HOLMES/白名单/降级场景 bucket 照记随审计）+ 白名单直进
        int bucket = CanaryBucketer.bucketOf(groupId, id, TOTAL_WEIGHT);
        boolean whitelisted = whitelistOf(canary).contains(id.trim());
        boolean nativeWish = whitelisted || bucket < percent;

        // ⑤ NATIVE 执行面未就绪：降级 HOLMES（立即回退为一等操作）
        if (nativeWish && !nativeReady) {
            return record(null, stickinessKey, bucket, percent, configDigest,
                    CanaryDecision.NATIVE_DEFERRED, now);
        }

        // ⑥ 爆炸半径上限：NATIVE 实跑计数达上限 → 自动停放量 + 告警
        if (nativeWish) {
            int cap = positiveIntOrMax(canary.get(MAX_NATIVE_RUNS_KEY));
            long nativeRuns = decisions.countNativeDecisions();
            if (nativeRuns >= cap) {
                log.warn("canary 爆炸半径达上限，自动停放量: nativeRuns={} cap={} bundle={}",
                        nativeRuns, cap, configDigest.hex());
                return record(null, stickinessKey, bucket, percent, configDigest,
                        CanaryDecision.BLAST_RADIUS_STOPPED, now);
            }
        }

        // ⑦ 终裁：白名单/桶命中 → NATIVE；否则 HOLMES 主路径。
        //    BA-60：审计行 run_id 只随 NATIVE 出路落值（HOLMES 意愿永不铸 run，
        //    幽灵引用会被 V31 deferred FK 在提交点拒杀）
        CanaryDecision decision = whitelisted ? CanaryDecision.WHITELISTED
                : nativeWish ? CanaryDecision.BUCKETED_NATIVE
                : CanaryDecision.BUCKETED_HOLMES;
        RcaEngine engine = nativeWish ? RcaEngine.NATIVE : RcaEngine.HOLMES;
        record(nativeWish ? runId : null, stickinessKey, bucket, percent, configDigest,
                decision, now);
        return new RcaRunRouting(engine, configDigest, stickinessKey, bucket, decision.name());
    }

    // ------------------------------------------------------------------ 内部

    /** 审计行必落 + HOLMES 投影组装（NATIVE 投影由调用点 ⑦ 显式构造） */
    private RcaRunRouting record(UUID runId, String stickinessKey, Integer bucket,
                                 int percent, Digest configDigest, CanaryDecision decision,
                                 Instant now) {
        decisions.append(new DecisionRow(runId, stickinessKey, bucket, percent,
                configDigest, decision.name(), now));
        return new RcaRunRouting(RcaEngine.HOLMES, configDigest, stickinessKey, bucket,
                decision.name());
    }

    private Map<String, Object> canarySection(Digest digest) {
        ConfigBundle bundle = bundles.findByDigest(digest).orElseThrow();
        return bundle.content().get(CANARY_SECTION) instanceof Map<?, ?> section
                ? asStringKeyMap(section) : Map.of();
    }

    private static Map<String, Object> asStringKeyMap(Map<?, ?> raw) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private List<String> whitelistOf(Map<String, Object> canary) {
        if (!(canary.get(WHITELIST_KEY) instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream().map(String::valueOf).toList();
    }

    private static Integer intOf(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    /** max_native_runs 缺省 = Integer.MAX_VALUE（未设上限时不拦截，仅比例生效） */
    private static int positiveIntOrMax(Object value) {
        Integer n = intOf(value);
        return n == null || n < 1 ? Integer.MAX_VALUE : n;
    }

    /** 无仓储空实现（holmesOnly 工厂用） */
    private static final class NullBundles implements ConfigBundleRepository {
        @Override
        public long nextRevision() {
            throw new UnsupportedOperationException("holmesOnly 路由器不触仓储");
        }

        @Override
        public boolean insert(ConfigBundle bundle) {
            throw new UnsupportedOperationException("holmesOnly 路由器不触仓储");
        }

        @Override
        public Optional<ConfigBundle> findByDigest(Digest digest) {
            return Optional.empty();
        }

        @Override
        public Optional<Digest> activeDigest() {
            return Optional.empty();
        }

        @Override
        public Optional<ConfigBundleRepository.ActivePointer> findActivePointer() {
            return Optional.empty();
        }

        @Override
        public java.util.List<BundleSummary> listRecent(int limit) {
            return java.util.List.of();
        }

        @Override
        public boolean activateQualified(Digest toDigest, long expectedActiveRevision,
                                         String by, Instant at) {
            throw new UnsupportedOperationException("holmesOnly 路由器不触仓储");
        }
    }

    private static final class NullDecisions implements CanaryDecisionLogRepository {
        @Override
        public long countNativeDecisions() {
            return 0;
        }

        @Override
        public void append(DecisionRow row) {
            // holmesOnly：零审计行（无持久化面可落）
        }
    }
}
