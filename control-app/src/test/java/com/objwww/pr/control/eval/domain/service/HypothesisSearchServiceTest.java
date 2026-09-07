package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.eval.domain.model.PartitionClass;
import com.objwww.pr.control.eval.infrastructure.identity.EvalIdentityGuard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M5-20 FTS 历史假设检索实验门（落码方案 §M5-20④）：off 态零历史注入（后端零触达）、
 * on 态命中仅产 UNTRUSTED_HYPOTHESIS、跨租户泄漏恒 0（后端违约也泄 0 的服务面纵深）、
 * HOLDOUT 查询按身份矩阵显式拒绝（INV-AM5-9）。
 */
class HypothesisSearchServiceTest {

    /** 可编程替身：记录触达次数，按脚本返回命中行事实 */
    private static final class ScriptedBackend implements HypothesisSearchService.HistoryBackend {
        int invocations;
        List<HypothesisSearchService.HistoryBackend.Hit> script = List.of();

        @Override
        public List<HypothesisSearchService.HistoryBackend.Hit> search(String query,
                HypothesisSearchService.SearchContext ctx) {
            invocations++;
            return script;
        }
    }

    private static HypothesisSearchService.SearchContext ctx(String tenant,
            EvalIdentityGuard.EvalIdentity identity, PartitionClass partition) {
        return new HypothesisSearchService.SearchContext(tenant, identity, partition);
    }

    @Test
    void offStateInjectsZeroHistoryAndNeverTouchesBackend() {
        ScriptedBackend backend = new ScriptedBackend();
        HypothesisSearchService service = new HypothesisSearchService(false,
                new EvalIdentityGuard(), backend);

        assertThat(service.search("磁盘打满", ctx("t1",
                EvalIdentityGuard.EvalIdentity.RAG, PartitionClass.TUNING))).isEmpty();
        assertThat(backend.invocations).isZero();
    }

    @Test
    void onStateYieldsOnlyUntrustedHypothesisWithContractFields() {
        ScriptedBackend backend = new ScriptedBackend();
        backend.script = List.of(
                new HypothesisSearchService.HistoryBackend.Hit("t1", "report-1", 0.91),
                new HypothesisSearchService.HistoryBackend.Hit("t1", "report-2", 0.42));
        HypothesisSearchService service = new HypothesisSearchService(true,
                new EvalIdentityGuard(), backend);

        List<HypothesisSearchService.Hypothesis> hits = service.search("磁盘打满",
                ctx("t1", EvalIdentityGuard.EvalIdentity.RAG, PartitionClass.TUNING));

        assertThat(hits).hasSize(2);
        assertThat(hits).allSatisfy(h ->
                assertThat(h.trustLevel())
                        .isEqualTo(HypothesisSearchService.TrustLevel.UNTRUSTED_HYPOTHESIS));
        assertThat(hits).extracting(HypothesisSearchService.Hypothesis::ref)
                .containsExactly("report-1", "report-2");
        assertThat(hits).extracting(HypothesisSearchService.Hypothesis::score)
                .containsExactly(0.91, 0.42);
    }

    @Test
    void crossTenantLeakIsZeroEvenWhenBackendMisbehaves() {
        ScriptedBackend backend = new ScriptedBackend();
        // 后端无视 ctx 租户跨域返回（违约面）——服务面租户过滤兜底
        backend.script = List.of(
                new HypothesisSearchService.HistoryBackend.Hit("t1", "own-1", 0.9),
                new HypothesisSearchService.HistoryBackend.Hit("t2", "foreign-1", 0.8),
                new HypothesisSearchService.HistoryBackend.Hit("t3", "foreign-2", 0.7));
        HypothesisSearchService service = new HypothesisSearchService(true,
                new EvalIdentityGuard(), backend);

        List<HypothesisSearchService.Hypothesis> hits = service.search("缓存穿透",
                ctx("t1", EvalIdentityGuard.EvalIdentity.RAG, PartitionClass.VALIDATION));

        assertThat(hits).extracting(HypothesisSearchService.Hypothesis::ref)
                .containsExactly("own-1");
    }

    @Test
    void holdoutQueryByIdentityIsExplicitlyRefusedFailClosed() {
        ScriptedBackend backend = new ScriptedBackend();
        HypothesisSearchService service = new HypothesisSearchService(true,
                new EvalIdentityGuard(), backend);

        // RAG 身份对 HOLDOUT 恒 0 可见（EvalIdentityGuard 矩阵）——显式拒绝非静默空集
        assertThatThrownBy(() -> service.search("泄漏试探", ctx("t1",
                EvalIdentityGuard.EvalIdentity.RAG, PartitionClass.HOLDOUT)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HOLDOUT");
        assertThat(backend.invocations).isZero();
    }

    @Test
    void blankQueryYieldsZeroWithoutBackendRoundTrip() {
        ScriptedBackend backend = new ScriptedBackend();
        HypothesisSearchService service = new HypothesisSearchService(true,
                new EvalIdentityGuard(), backend);

        assertThat(service.search("   ", ctx("t1",
                EvalIdentityGuard.EvalIdentity.RAG, PartitionClass.TUNING))).isEmpty();
        assertThat(service.search(null, ctx("t1",
                EvalIdentityGuard.EvalIdentity.RAG, PartitionClass.TUNING))).isEmpty();
        assertThat(backend.invocations).isZero();
    }
}
