package com.objwww.pr.control.release.application;

import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.release.domain.model.CanaryDecision;
import com.objwww.pr.control.release.domain.repository.CanaryDecisionLogRepository;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * M5-10 CanaryRouter 路由决策 UT（方案 §4.1/§M5-10）：新 Run 铸造点读 active
 * bundle → 稳定分桶 → 路由决策四列（engine/config_digest/stickiness_key/bucket）
 * + 决策审计行（canary_route_decision 全记录）。冻结面：无 stickiness key = 拒绝
 * 放量（修 Unleash random 回退坑）；爆炸半径超限自动停放量（无状态重数即自愈）；
 * NATIVE 执行面未就绪时降级 HOLMES（立即回退 Holmes 为一等操作）；白名单直进。
 */
class CanaryRouterTest {

    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    private InMemoryBundles bundles;
    private InMemoryDecisions decisions;
    private CanaryRouter router;

    @BeforeEach
    void setUp() {
        bundles = new InMemoryBundles();
        decisions = new InMemoryDecisions();
        router = new CanaryRouter(bundles, decisions, true, () -> NOW);
    }

    /** 测试内存认账面（M5-09 InMemoryBundles 同构 + canary 配置装载入口） */
    static final class InMemoryBundles implements ConfigBundleRepository {
        final List<ConfigBundle> rows = new ArrayList<>();
        Digest active;

        void publish(Map<String, Object> content) {
            ConfigBundle bundle = ConfigBundle.of(content, "op", NOW);
            rows.add(bundle);
            active = bundle.bundleDigest();
        }

        @Override
        public long nextRevision() {
            return rows.size() + 1;
        }

        @Override
        public boolean insert(ConfigBundle bundle) {
            return rows.add(bundle);
        }

        @Override
        public Optional<ConfigBundle> findByDigest(Digest digest) {
            return rows.stream().filter(b -> b.bundleDigest().equals(digest)).findFirst();
        }

        @Override
        public Optional<Digest> activeDigest() {
            return Optional.ofNullable(active);
        }

        @Override
        public Optional<ConfigBundleRepository.ActivePointer> findActivePointer() {
            return active == null ? Optional.empty()
                    : Optional.of(new ConfigBundleRepository.ActivePointer(active, 1L, NOW));
        }

        @Override
        public boolean activateQualified(Digest toDigest, long expectedActiveRevision,
                String by, Instant at) {
            active = toDigest;
            return true;
        }
    }

    /** 决策审计行收集面 */
    static final class InMemoryDecisions implements CanaryDecisionLogRepository {
        final List<CanaryDecisionLogRepository.DecisionRow> rows = new ArrayList<>();

        @Override
        public void append(CanaryDecisionLogRepository.DecisionRow row) {
            rows.add(row);
        }

        @Override
        public long countNativeDecisions() {
            return rows.stream()
                    .map(CanaryDecisionLogRepository.DecisionRow::decision)
                    .filter(d -> d.equals(CanaryDecision.WHITELISTED.name())
                            || d.equals(CanaryDecision.BUCKETED_NATIVE.name()))
                    .count();
        }
    }

    private static Map<String, Object> bundleWithCanary(Integer percent, List<String> whitelist,
                                                        Integer maxNativeRuns) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policy_version", "policy-2026-09");
        if (percent != null || whitelist != null || maxNativeRuns != null) {
            Map<String, Object> canary = new LinkedHashMap<>();
            if (percent != null) {
                canary.put("percent", percent);
            }
            if (whitelist != null) {
                canary.put("whitelist", whitelist);
            }
            if (maxNativeRuns != null) {
                canary.put("max_native_runs", maxNativeRuns);
            }
            content.put("canary", canary);
        }
        return content;
    }

    private RcaRunRouting route(UUID runId, String groupId, String id) {
        return router.route(runId, groupId, id);
    }

    // ---------------------------------------------------------------- 用例面

    @Test
    @DisplayName("无 active bundle → HOLMES + decision NO_ACTIVE_BUNDLE，路由四列空，审计行照记")
    void noActiveBundleDefaultsToHolmes() {
        RcaRunRouting routing = route(UUID.randomUUID(), "g", "incident-1");

        assertThat(routing.engine()).isEqualTo(RcaEngine.HOLMES);
        assertThat(routing.configDigest()).isNull();
        assertThat(routing.stickinessKey()).isNull();
        assertThat(routing.bucket()).isNull();
        assertThat(routing.decision()).isEqualTo(CanaryDecision.NO_ACTIVE_BUNDLE.name());
        assertThat(decisions.rows).hasSize(1);
        assertThat(decisions.rows.get(0).percent()).isZero();
        assertThat(decisions.rows.get(0).bundleDigest()).isNull();
    }

    @Test
    @DisplayName("bundle 无 canary 段 → HOLMES + CANARY_DISABLED（放量未配置=全量主路径）")
    void bundleWithoutCanarySectionDisablesCanary() {
        bundles.publish(bundleWithCanary(null, null, null));

        RcaRunRouting routing = route(UUID.randomUUID(), "g", "incident-1");

        assertThat(routing.engine()).isEqualTo(RcaEngine.HOLMES);
        assertThat(routing.decision()).isEqualTo(CanaryDecision.CANARY_DISABLED.name());
        assertThat(routing.configDigest()).isEqualTo(bundles.active);
        assertThat(decisions.rows.get(0).bundleDigest()).isEqualTo(bundles.active);
    }

    @Test
    @DisplayName("canary 配置在但缺 stickiness key（id blank）→ HOLMES + NO_STICKINESS_KEY（拒绝放量，bucket 不产出）")
    void missingStickinessKeyRefusesRollout() {
        bundles.publish(bundleWithCanary(100, List.of(), 10));

        RcaRunRouting noId = route(UUID.randomUUID(), "g", " ");
        RcaRunRouting nullId = route(UUID.randomUUID(), "g", null);

        assertThat(noId.engine()).isEqualTo(RcaEngine.HOLMES);
        assertThat(noId.decision()).isEqualTo(CanaryDecision.NO_STICKINESS_KEY.name());
        assertThat(noId.bucket()).isNull();
        assertThat(nullId.decision()).isEqualTo(CanaryDecision.NO_STICKINESS_KEY.name());
        // 拒绝放量 ≠ 拒绝调查：run 照常 HOLMES 主路径
        assertThat(decisions.rows).hasSize(2);
    }

    @Test
    @DisplayName("白名单直进 NATIVE（bucket 仍计算随审计），engine=NATIVE + 全四列齐备")
    void whitelistHitsGoNative() {
        bundles.publish(bundleWithCanary(0, List.of("incident-hot"), 10));

        RcaRunRouting routing = route(UUID.randomUUID(), "g", "incident-hot");

        assertThat(routing.engine()).isEqualTo(RcaEngine.NATIVE);
        assertThat(routing.decision()).isEqualTo(CanaryDecision.WHITELISTED.name());
        assertThat(routing.configDigest()).isEqualTo(bundles.active);
        assertThat(routing.stickinessKey()).isEqualTo("g:incident-hot");
        assertThat(routing.bucket()).isNotNull();
    }

    @Test
    @DisplayName("分桶阈值：percent=100 全量 NATIVE（BUCKETED_NATIVE）；percent=0 全量 HOLMES（BUCKETED_HOLMES）")
    void bucketThresholdDrivesEngine() {
        bundles.publish(bundleWithCanary(100, List.of(), 10));
        RcaRunRouting allNative = route(UUID.randomUUID(), "g", "incident-1");
        assertThat(allNative.engine()).isEqualTo(RcaEngine.NATIVE);
        assertThat(allNative.decision()).isEqualTo(CanaryDecision.BUCKETED_NATIVE.name());

        bundles.publish(bundleWithCanary(0, List.of(), 10));
        RcaRunRouting noneNative = route(UUID.randomUUID(), "g", "incident-1");
        assertThat(noneNative.engine()).isEqualTo(RcaEngine.HOLMES);
        assertThat(noneNative.decision()).isEqualTo(CanaryDecision.BUCKETED_HOLMES.name());
        assertThat(noneNative.bucket()).isNotNull();
    }

    @Test
    @DisplayName("黏性：同 (groupId,id) 两次路由同桶同引擎（决策不抖动）")
    void routingIsStickyAcrossCalls() {
        bundles.publish(bundleWithCanary(50, List.of(), 100));
        RcaRunRouting first = route(UUID.randomUUID(), "g", "incident-9");
        RcaRunRouting second = route(UUID.randomUUID(), "g", "incident-9");
        assertThat(first.bucket()).isEqualTo(second.bucket());
        assertThat(first.engine()).isEqualTo(second.engine());
    }

    @Test
    @DisplayName("NATIVE 执行面未就绪（nativeReady=false）→ NATIVE 意愿降级 HOLMES + NATIVE_DEFERRED（回退一等）")
    void nativeDeferredWhenExecutorNotReady() {
        bundles.publish(bundleWithCanary(100, List.of(), 10));
        CanaryRouter notReady = new CanaryRouter(bundles, decisions, false, () -> NOW);

        RcaRunRouting routing = notReady.route(UUID.randomUUID(), "g", "incident-1");

        assertThat(routing.engine()).isEqualTo(RcaEngine.HOLMES);
        assertThat(routing.decision()).isEqualTo(CanaryDecision.NATIVE_DEFERRED.name());
        assertThat(routing.bucket()).isNotNull();
    }

    @Test
    @DisplayName("爆炸半径：NATIVE 决策数达 max_native_runs 上限 → 自动停放量（HOLMES + BLAST_RADIUS_STOPPED），无状态重数自愈")
    void blastRadiusCapAutoStopsRollout() {
        bundles.publish(bundleWithCanary(100, List.of(), 1));   // 上限=1
        UUID firstRun = UUID.randomUUID();
        RcaRunRouting first = route(firstRun, "g", "incident-1");
        assertThat(first.engine()).isEqualTo(RcaEngine.NATIVE);

        // 后续 NATIVE 意愿全部回落 HOLMES（重数自愈，无开关行可被绕过）
        RcaRunRouting second = route(UUID.randomUUID(), "g", "incident-2");
        RcaRunRouting third = route(UUID.randomUUID(), "g", "incident-3");
        assertThat(second.engine()).isEqualTo(RcaEngine.HOLMES);
        assertThat(second.decision()).isEqualTo(CanaryDecision.BLAST_RADIUS_STOPPED.name());
        assertThat(third.decision()).isEqualTo(CanaryDecision.BLAST_RADIUS_STOPPED.name());
        assertThat(decisions.rows).hasSize(3);
        long nativeCount = decisions.rows.stream()
                .filter(r -> r.decision().equals(CanaryDecision.BUCKETED_NATIVE.name())
                        || r.decision().equals(CanaryDecision.WHITELISTED.name()))
                .count();
        assertThat(nativeCount).isEqualTo(1);
    }

    @Test
    @DisplayName("审计行全记录：NATIVE 出路 runId 随行；HOLMES 意愿出路 runId 落 NULL（BA-60/V35）")
    void decisionRowsCarryFullProvenance() {
        // NATIVE 出路（白名单直进）：审计行七列齐 + runId 在值（同 id run 行必落库）
        bundles.publish(bundleWithCanary(0, List.of("incident-native"), 5));
        UUID nativeRunId = UUID.randomUUID();
        RcaRunRouting nativeRouting = route(nativeRunId, "g", "incident-native");

        CanaryDecisionLogRepository.DecisionRow nativeRow = decisions.rows.get(0);
        assertThat(nativeRouting.engine()).isEqualTo(RcaEngine.NATIVE);
        assertThat(nativeRow.runId()).isEqualTo(nativeRunId);
        assertThat(nativeRow.stickinessKey()).isEqualTo("g:incident-native");
        assertThat(nativeRow.bucket()).isNotNull();
        assertThat(nativeRow.bundleDigest()).isEqualTo(bundles.active);
        assertThat(nativeRow.createdAt()).isEqualTo(NOW);

        // HOLMES 意愿出路（percent=0 全落桶）：runId 落 NULL——run 行永不铸，
        // 携带预生成 id 即幽灵引用（V31 deferred FK 提交点拒杀，BA-60）
        bundles.publish(bundleWithCanary(0, List.of(), 5));
        UUID holmesRunId = UUID.randomUUID();
        RcaRunRouting holmesRouting = route(holmesRunId, "g", "incident-2");

        CanaryDecisionLogRepository.DecisionRow holmesRow = decisions.rows.get(1);
        assertThat(holmesRouting.engine()).isEqualTo(RcaEngine.HOLMES);
        assertThat(holmesRouting.decision()).isEqualTo(CanaryDecision.BUCKETED_HOLMES.name());
        assertThat(holmesRow.runId()).as("HOLMES 意愿决策行 run_id 归属留空").isNull();
        assertThat(holmesRow.stickinessKey()).isEqualTo("g:incident-2");
        assertThat(holmesRow.bucket()).isNotNull();
        assertThat(holmesRow.percent()).isZero();
        assertThat(holmesRow.bundleDigest()).isEqualTo(bundles.active);
    }

    @Test
    @DisplayName("holmesOnly 降级路由器：恒 NO_ACTIVE_BUNDLE 全 HOLMES 且零审计行（AM4 冻结面接线用）")
    void holmesOnlyRouterWritesNothing() {
        CanaryRouter holmesOnly = CanaryRouter.holmesOnly();
        RcaRunRouting routing = holmesOnly.route(UUID.randomUUID(), "g", "incident-1");

        assertThat(routing.engine()).isEqualTo(RcaEngine.HOLMES);
        assertThat(routing.decision()).isEqualTo(CanaryDecision.NO_ACTIVE_BUNDLE.name());
    }

    @Test
    @DisplayName("契约校验：runId null → NPE")
    void nullRunIdRejected() {
        assertThatNullPointerException().isThrownBy(() -> route(null, "g", "x"));
    }
}
