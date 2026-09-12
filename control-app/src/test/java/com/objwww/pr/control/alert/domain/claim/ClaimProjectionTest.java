package com.objwww.pr.control.alert.domain.claim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-AM4-22：四分支判定矩阵（纯域函数，PG 仓储只执行这里的判定）——
 * fingerprint 不存在 → CREATED；fp 同+hash 同 → UNCHANGED；fp 同+hash 异 →
 * REVISED（CAS 守卫=priorHash）；新 generation → 新 fingerprint（旧记录只标
 * SUPERSEDED 不改内容，且仅同 proposition——claimKey+scope+timeRange 相同——才被取代）。
 */
class ClaimProjectionTest {

    private static final String FP = "fp-1";

    private static ClaimVerdict verdict(long generation, String scope, String key, String timeRange) {
        return new ClaimVerdict(key, scope, timeRange, generation, null,
                ClaimStatus.TRUE, EvidenceBasis.SINGLE_SOURCE,
                List.of("src-a"), "r-1", List.of("ev-1"), "policy-v1");
    }

    // ---------------- 四分支矩阵 ----------------

    @Test
    void 分支1_fingerprint不存在_CREATED() {
        ClaimProjection.Decision d = ClaimProjection.decide(null, "hash-new");
        assertThat(d.outcome()).isEqualTo(ClaimProjection.Outcome.CREATED);
        assertThat(d.priorHash()).isNull();
    }

    @Test
    void 分支2_fp同hash同_UNCHANGED() {
        ClaimProjection.Decision d = ClaimProjection.decide("hash-1", "hash-1");
        assertThat(d.outcome()).isEqualTo(ClaimProjection.Outcome.UNCHANGED);
        assertThat(d.priorHash()).isEqualTo("hash-1");
    }

    @Test
    void 分支3_fp同hash异_REVISED带CAS守卫() {
        ClaimProjection.Decision d = ClaimProjection.decide("hash-old", "hash-new");
        assertThat(d.outcome()).isEqualTo(ClaimProjection.Outcome.REVISED);
        assertThat(d.priorHash()).isEqualTo("hash-old"); // UPDATE ... WHERE claim_hash=:prior
    }

    @Test
    void 分支4_新代新fingerprint_由CREATED承载() {
        // 新 generation = 新 fingerprint → 仓储面查不到该 fingerprint → CREATED 落新行；
        // 旧记录的处置由 shouldSupersede 单独判定（不覆盖旧记录）
        assertThat(ClaimProjection.decide(null, "hash-gen1").outcome())
                .isEqualTo(ClaimProjection.Outcome.CREATED);
    }

    // ---------------- supersede 规则：只取代同 proposition 的严格更旧代际 ----------------

    @Test
    void 同proposition更旧代际_被取代() {
        ClaimVerdict newer = verdict(2, "scope", "k", "tr");
        assertThat(ClaimProjection.shouldSupersede(
                row(1, "scope", "k", "tr"), newer)).isTrue();
    }

    @Test
    void 同代不同快照_共存不取代() {
        ClaimVerdict newer = verdict(1, "scope", "k", "tr");
        assertThat(ClaimProjection.shouldSupersede(
                row(1, "scope", "k", "tr"), newer)).isFalse();
    }

    @Test
    void 不同scope键或时间窗_不同事实永不取代() {
        ClaimVerdict newer = verdict(2, "scope", "k", "tr");
        assertThat(ClaimProjection.shouldSupersede(row(1, "other", "k", "tr"), newer)).isFalse();
        assertThat(ClaimProjection.shouldSupersede(row(1, "scope", "other", "tr"), newer)).isFalse();
        assertThat(ClaimProjection.shouldSupersede(row(1, "scope", "k", "other"), newer)).isFalse();
    }

    @Test
    void 更旧或同代_不取代() {
        ClaimVerdict newer = verdict(1, "scope", "k", "tr");
        assertThat(ClaimProjection.shouldSupersede(row(2, "scope", "k", "tr"), newer)).isFalse();
        assertThat(ClaimProjection.shouldSupersede(row(1, "scope", "k", "tr"), newer)).isFalse();
    }

    @Test
    void scope空白归一后同proposition仍取代() {
        // 归一规则与裁决分组键一致（strip）
        ClaimVerdict newer = verdict(2, "scope", "k", "tr");
        assertThat(ClaimProjection.shouldSupersede(row(1, "  scope  ", "k", "tr"), newer)).isTrue();
    }

    private static ClaimStore.ClaimRow row(long generation, String scope, String key, String timeRange) {
        return new ClaimStore.ClaimRow(UUID.randomUUID(), UUID.randomUUID(), FP, "hash-old",
                key, ClaimStatus.TRUE, EvidenceBasis.SINGLE_SOURCE, ClaimLifecycle.ACTIVE,
                "r-0", scope.strip(), timeRange, generation, List.of("src-old"),
                List.of("ev-old"), "policy-v0", null, null);
    }
}
