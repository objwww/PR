package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.eval.domain.model.PartitionClass;
import com.objwww.pr.control.eval.infrastructure.identity.EvalIdentityGuard;

import java.util.List;
import java.util.Objects;

/**
 * 历史假设检索 FTS 实验门（M5-20；方案 §12.1 L2"历史检索门"）。三条铁律：
 * <ul>
 *   <li><b>默认关闭</b>——enabled=false 恒空集且后端零触达（feature flag
 *       {@code app.eval.hypothesis-search.enabled}，默认 false；无迁移，tsvector
 *       列/索引随实验开门评审冻结——落码方案 §M5-20②）；</li>
 *   <li><b>只产 UNTRUSTED_HYPOTHESIS</b>——历史命中永不为已证根因，不得仅凭历史
 *       命中发布（E2E-AM5-10 断言面）；</li>
 *   <li><b>跨租户/HOLDOUT 泄漏恒 0</b>（INV-AM5-9）——HOLDOUT 查询按
 *       EvalIdentityGuard 身份矩阵显式拒绝（fail-closed）；租户过滤在服务面做纵深
 *       （后端违约跨域返回也泄 0）。</li>
 * </ul>
 * zhparser 中文分词现状未核实（方案 §7 残余风险②）——spike 属实验开门前置项，
 * 本类只钉语义门不钉分词器。
 */
public class HypothesisSearchService {

    /** 信任级单值枚举：类型面钉死"历史检索只产此级"（新增级别 = 评审事件） */
    public enum TrustLevel {UNTRUSTED_HYPOTHESIS}

    /**
     * @param ref 历史报告/事件引用（仅标识符，不携带原文内容）
     */
    public record Hypothesis(String ref, double score, TrustLevel trustLevel) {
    }

    /**
     * @param tenant   检索域租户（跨租户过滤锚）
     * @param identity 检索身份（EvalIdentityGuard 矩阵管辖 HOLDOUT 可见面）
     * @param partition 检索目标分区（HOLDOUT/REDTEAM 对 Agent/RAG 身份恒拒）
     */
    public record SearchContext(String tenant,
                                EvalIdentityGuard.EvalIdentity identity,
                                PartitionClass partition) {
        public SearchContext {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(partition, "partition");
            if (tenant == null || tenant.isBlank()) {
                throw new IllegalArgumentException("tenant 不得为空");
            }
        }
    }

    /**
     * 历史面后端替身点（FTS 实验开门时落 PG tsvector 实现；命中行 tenant 为行事实
     * 字段——服务面按 ctx 租户过滤，后端违约不放大泄漏）。
     */
    @FunctionalInterface
    public interface HistoryBackend {

        record Hit(String tenant, String ref, double score) {
        }

        List<Hit> search(String query, SearchContext ctx);
    }

    private final boolean enabled;
    private final EvalIdentityGuard guard;
    private final HistoryBackend backend;

    public HypothesisSearchService(boolean enabled, EvalIdentityGuard guard,
                                   HistoryBackend backend) {
        this.enabled = enabled;
        this.guard = Objects.requireNonNull(guard);
        this.backend = Objects.requireNonNull(backend);
    }

    public List<Hypothesis> search(String query, SearchContext ctx) {
        // 门 0：默认关闭 = 零历史注入（后端零触达）
        if (!enabled || query == null || query.isBlank()) {
            return List.of();
        }
        // 门 1：HOLDOUT/越权分区显式拒绝（fail-closed，不降级为空集查询）
        guard.assertQueryAllowed(ctx.identity(), ctx.partition());
        // 门 2：服务面租户纵深过滤 + 信任级恒钉 UNTRUSTED_HYPOTHESIS
        return backend.search(query, ctx).stream()
                .filter(hit -> Objects.equals(hit.tenant(), ctx.tenant()))
                .map(hit -> new Hypothesis(hit.ref(), hit.score(),
                        TrustLevel.UNTRUSTED_HYPOTHESIS))
                .toList();
    }
}
