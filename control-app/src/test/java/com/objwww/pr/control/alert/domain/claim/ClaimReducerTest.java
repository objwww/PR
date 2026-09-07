package com.objwww.pr.control.alert.domain.claim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UT-AM4-22：ClaimReducer 矩阵——分组键五元组 / SINGLE_SOURCE / 同源去重 /
 * 权威源规则 / 双源佐证 / 对峙 UNKNOWN。v1.3 统一状态模型：输出<b>无独立 verdict 枚举</b>，
 * 只有三正交字段中的证据基础 EvidenceBasis（SINGLE_SOURCE / MULTI_SOURCE_CONSISTENT /
 * MULTI_SOURCE_CONFLICT）；胜出子集 ≥2 独立 source 即 CONSISTENT，权威源裁决按其
 * 真实来源数计基（权威定状态，非佐证计数），无法裁决 = UNKNOWN + MULTI_SOURCE_CONFLICT。
 * 全程确定性：同事实同输入顺序无关同 contentHash。
 */
class ClaimReducerTest {

    private static final String AUTH = "holmes-baseline";
    private final ClaimReducer reducer = new ClaimReducer(Set.of(AUTH), "policy-v1");

    private static Claim claim(String key, ClaimStatus status, String source, String... evidence) {
        return new Claim(key, status, "r-" + status, "scope", "2026-09-05/1h", 1L,
                List.of(evidence), source, null);
    }

    // ---------------- 分组键：claimKey + normalizedScope + timeRange + generation + snapshotDigest ----------------

    @Test
    void differentTimeRangeNeverMerged() {
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "a", "ev-1"),
                new Claim("k", ClaimStatus.FALSE, "r", "scope", "2026-09-05/2h", 1L,
                        List.of("ev-2"), "b", null)));
        assertThat(verdicts).hasSize(2);
        assertThat(verdicts).allMatch(v -> v.evidenceBasis() == EvidenceBasis.SINGLE_SOURCE);
    }

    @Test
    void differentGenerationNeverMerged() {
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "a", "ev-1"),
                new Claim("k", ClaimStatus.FALSE, "r", "scope", "2026-09-05/1h", 2L,
                        List.of("ev-2"), "b", null)));
        assertThat(verdicts).hasSize(2);
    }

    @Test
    void differentSnapshotDigestNeverMerged() {
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "a", "ev-1"), // snapshotDigest = null
                new Claim("k", ClaimStatus.TRUE, "r", "scope", "2026-09-05/1h", 1L,
                        List.of("ev-2"), "b", "snap-1")));
        assertThat(verdicts).hasSize(2);
    }

    @Test
    void scopeWhitespaceIsNormalizedNotRegrouped() {
        // " scope " 与 "scope" 同组（normalizedScope = strip），但 "other" 不同组
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "a", "ev-1"),
                new Claim("k", ClaimStatus.TRUE, "r", " scope ", "2026-09-05/1h", 1L,
                        List.of("ev-2"), "b", null),
                new Claim("k", ClaimStatus.TRUE, "r", "other", "2026-09-05/1h", 1L,
                        List.of("ev-3"), "c", null)));
        assertThat(verdicts).hasSize(2);
        ClaimVerdict merged = verdicts.stream()
                .filter(v -> v.scope().equals("scope")).findFirst().orElseThrow();
        assertThat(merged.scope()).isEqualTo("scope"); // 归一后输出
        assertThat(merged.evidenceBasis()).isEqualTo(EvidenceBasis.MULTI_SOURCE_CONSISTENT);
    }

    // ---------------- 单来源规则：不得判确认级别 ----------------

    @Test
    void singleNonAuthoritativeClaimIsSingleSourceNotCorroborated() {
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "metrics-agent", "ev-1")));
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.evidenceBasis()).isEqualTo(EvidenceBasis.SINGLE_SOURCE);
        assertThat(v.status()).isEqualTo(ClaimStatus.TRUE);
        assertThat(v.corroborated()).isFalse();
        assertThat(v.sources()).containsExactly("metrics-agent");
    }

    @Test
    void sameSourceDuplicatesCountAsOneVote() {
        // 同一 source 重复发声去重后只算一票 → 仍是 SINGLE_SOURCE，非 CONSISTENT
        ClaimReducer noAuth = new ClaimReducer(Set.of(), "policy-v1");
        List<ClaimVerdict> verdicts = noAuth.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "metrics-agent", "ev-1"),
                claim("k", ClaimStatus.TRUE, "metrics-agent", "ev-2")));
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.evidenceBasis()).isEqualTo(EvidenceBasis.SINGLE_SOURCE);
        assertThat(v.corroborated()).isFalse();
        assertThat(v.evidenceRefs()).containsExactly("ev-1", "ev-2");
        assertThat(v.sources()).containsExactly("metrics-agent");
    }

    @Test
    void singleAuthoritativeClaimCarriesItsStatusWithSingleSourceBasis() {
        // 权威源定状态；证据基按真实来源数计（权威即契约，非佐证计数）
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                claim("k", ClaimStatus.FALSE, AUTH, "ev-auth")));
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.status()).isEqualTo(ClaimStatus.FALSE);
        assertThat(v.evidenceBasis()).isEqualTo(EvidenceBasis.SINGLE_SOURCE);
        assertThat(v.sources()).containsExactly(AUTH);
    }

    // ---------------- 多源一致 / 权威源 / 双源佐证 ----------------

    @Test
    void twoOrMoreSourcesAgreeingIsMultiSourceConsistent() {
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "metrics-agent", "ev-1"),
                claim("k", ClaimStatus.TRUE, "logs-agent", "ev-2"),
                claim("k", ClaimStatus.TRUE, "change-agent", "ev-3")));
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.evidenceBasis()).isEqualTo(EvidenceBasis.MULTI_SOURCE_CONSISTENT);
        assertThat(v.corroborated()).isTrue();
        assertThat(v.evidenceRefs()).containsExactly("ev-1", "ev-2", "ev-3");
        assertThat(v.sources()).containsExactly("change-agent", "logs-agent", "metrics-agent");
    }

    @Test
    void authoritativeSourceSettlesConflict() {
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "metrics-agent", "ev-1"),
                claim("k", ClaimStatus.FALSE, AUTH, "ev-auth")));
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.status()).isEqualTo(ClaimStatus.FALSE);
        assertThat(v.evidenceBasis()).isEqualTo(EvidenceBasis.SINGLE_SOURCE);
        assertThat(v.evidenceRefs()).containsExactly("ev-auth");
    }

    @Test
    void twoAgreeingAuthoritativeSourcesAreConsistentBasis() {
        // 两个权威源一致 → 胜出子集 2 独立来源 → CONSISTENT（按来源数，不因权威降级）
        ClaimReducer twoAuth = new ClaimReducer(Set.of("a1", "a2"), "policy-v1");
        List<ClaimVerdict> verdicts = twoAuth.reduce(List.of(
                claim("k", ClaimStatus.FALSE, "a1", "ev-1"),
                claim("k", ClaimStatus.FALSE, "a2", "ev-2"),
                claim("k", ClaimStatus.TRUE, "metrics-agent", "ev-3")));
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.status()).isEqualTo(ClaimStatus.FALSE);
        assertThat(v.evidenceBasis()).isEqualTo(EvidenceBasis.MULTI_SOURCE_CONSISTENT);
        assertThat(v.sources()).containsExactly("a1", "a2");
        assertThat(v.evidenceRefs()).containsExactly("ev-1", "ev-2");
    }

    @Test
    void conflictingAuthoritativeSourcesAreUnknownConflict() {
        ClaimReducer twoAuth = new ClaimReducer(Set.of("a1", "a2"), "policy-v1");
        List<ClaimVerdict> verdicts = twoAuth.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "a1", "ev-1"),
                claim("k", ClaimStatus.FALSE, "a2", "ev-2")));
        assertThat(verdicts.get(0).evidenceBasis()).isEqualTo(EvidenceBasis.MULTI_SOURCE_CONFLICT);
        assertThat(verdicts.get(0).status()).isEqualTo(ClaimStatus.UNKNOWN);
    }

    @Test
    void twoIndependentSourcesCorroborateAgainstDissenter() {
        ClaimReducer noAuth = new ClaimReducer(Set.of(), "policy-v1");
        List<ClaimVerdict> verdicts = noAuth.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "metrics-agent", "ev-1"),
                claim("k", ClaimStatus.TRUE, "logs-agent", "ev-2"),
                claim("k", ClaimStatus.FALSE, "change-agent", "ev-3")));
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.status()).isEqualTo(ClaimStatus.TRUE);
        assertThat(v.evidenceBasis()).isEqualTo(EvidenceBasis.MULTI_SOURCE_CONSISTENT);
        assertThat(v.corroborated()).isTrue();
        assertThat(v.evidenceRefs()).containsExactly("ev-1", "ev-2"); // 胜出子集证据
        assertThat(v.sources()).containsExactly("logs-agent", "metrics-agent");
    }

    /** TRUE 与 FALSE 各有 ≥2 独立来源 → 对峙即 UNKNOWN+CONFLICT，禁枚举顺序优先 */
    @Test
    void standoffBetweenTwoCorroboratedStatusesIsConflict() {
        ClaimReducer noAuth = new ClaimReducer(Set.of(), "policy-v1");
        List<Claim> trueFirst = List.of(
                claim("k", ClaimStatus.TRUE, "a", "ev-t1"),
                claim("k", ClaimStatus.TRUE, "b", "ev-t2"),
                claim("k", ClaimStatus.FALSE, "c", "ev-f1"),
                claim("k", ClaimStatus.FALSE, "d", "ev-f2"));
        List<Claim> falseFirst = List.of(
                claim("k", ClaimStatus.FALSE, "c", "ev-f1"),
                claim("k", ClaimStatus.FALSE, "d", "ev-f2"),
                claim("k", ClaimStatus.TRUE, "a", "ev-t1"),
                claim("k", ClaimStatus.TRUE, "b", "ev-t2"));
        for (List<Claim> input : List.of(trueFirst, falseFirst)) {
            ClaimVerdict v = noAuth.reduce(input).get(0);
            assertThat(v.evidenceBasis()).isEqualTo(EvidenceBasis.MULTI_SOURCE_CONFLICT);
            assertThat(v.status()).isEqualTo(ClaimStatus.UNKNOWN);
            assertThat(v.corroborated()).isFalse();
        }
    }

    @Test
    void unresolvableConflictCarriesAllEvidence() {
        ClaimReducer noAuth = new ClaimReducer(Set.of(), "policy-v1");
        List<ClaimVerdict> verdicts = noAuth.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "a", "ev-1"),
                claim("k", ClaimStatus.FALSE, "b", "ev-2"),
                claim("k", ClaimStatus.UNKNOWN, "c", "ev-3")));
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.evidenceBasis()).isEqualTo(EvidenceBasis.MULTI_SOURCE_CONFLICT);
        assertThat(v.status()).isEqualTo(ClaimStatus.UNKNOWN);
        assertThat(v.evidenceRefs()).containsExactly("ev-1", "ev-2", "ev-3");
        assertThat(v.sources()).containsExactly("a", "b", "c");
    }

    // ---------------- 输出契约 ----------------

    @Test
    void verdictCarriesFullGroupIdentityAndPolicyVersion() {
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                new Claim("k", ClaimStatus.TRUE, "r", "s", "tr", 7L,
                        List.of("ev"), "a", "snap-9")));
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.claimKey()).isEqualTo("k");
        assertThat(v.scope()).isEqualTo("s");
        assertThat(v.timeRange()).isEqualTo("tr");
        assertThat(v.observedGeneration()).isEqualTo(7L);
        assertThat(v.snapshotDigest()).isEqualTo("snap-9");
        assertThat(v.policyVersion()).isEqualTo("policy-v1");
        assertThat(v.fingerprint()).hasSize(64);
        assertThat(v.contentHash()).hasSize(64);
    }

    @Test
    void contentHashIsOrderIndependentAcrossInputShuffles() {
        List<Claim> base = List.of(
                claim("k", ClaimStatus.TRUE, "a", "ev-1"),
                claim("k", ClaimStatus.TRUE, "b", "ev-2"),
                claim("k", ClaimStatus.FALSE, "c", "ev-3"));
        String forward = reducer.reduce(base).get(0).contentHash();
        List<Claim> shuffled = new ArrayList<>(base);
        java.util.Collections.reverse(shuffled);
        String backward = reducer.reduce(shuffled).get(0).contentHash();
        assertThat(forward).isEqualTo(backward);
    }

    @Test
    void reasonMergesWinningClaimsDistinctReasonsSorted() {
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                new Claim("k", ClaimStatus.TRUE, "zz-reason", "scope", "tr", 1L,
                        List.of("ev-1"), "a", null),
                new Claim("k", ClaimStatus.TRUE, "aa-reason", "scope", "tr", 1L,
                        List.of("ev-2"), "b", null)));
        assertThat(verdicts.get(0).reason()).isEqualTo("aa-reason; zz-reason");
    }

    @Test
    void outputIsSortedByGroupKey() {
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                claim("z-key", ClaimStatus.TRUE, "a", "ev-z"),
                claim("a-key", ClaimStatus.TRUE, "b", "ev-a"),
                new Claim("a-key", ClaimStatus.TRUE, "r", "scope", "2025-01-01/1h", 1L,
                        List.of("ev-c"), "c", null)));
        assertThat(verdicts).extracting(ClaimVerdict::claimKey)
                .containsExactly("a-key", "a-key", "z-key");
        assertThat(verdicts.get(0).timeRange()).isEqualTo("2025-01-01/1h");
    }

    @Test
    void emptyInputProducesEmptyOutput() {
        assertThat(reducer.reduce(List.of())).isEmpty();
    }

    @Test
    void blankPolicyVersionRejected() {
        assertThatThrownBy(() -> new ClaimReducer(Set.of(), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClaimReducer(null, "p"))
                .isInstanceOf(NullPointerException.class);
    }
}
