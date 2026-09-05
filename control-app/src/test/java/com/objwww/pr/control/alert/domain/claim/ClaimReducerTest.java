package com.objwww.pr.control.alert.domain.claim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-AM4-22：ClaimReducer 矩阵——分组键五元组 / SINGLE_SOURCE / 同源去重 /
 * 权威源规则 / 双源佐证 / TRUE-FALSE 对峙 NEEDS_REVIEW。
 * 全程断言语义来源是规则，无任何置信度投票。
 */
class ClaimReducerTest {

    private static final String AUTH = "holmes-baseline";
    private final ClaimReducer reducer = new ClaimReducer(Set.of(AUTH));

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
        assertThat(verdicts).allMatch(v -> v.basis() == ClaimVerdict.Basis.SINGLE_SOURCE);
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
        assertThat(merged.basis()).isEqualTo(ClaimVerdict.Basis.UNANIMOUS);
    }

    // ---------------- 单来源规则：不得判确认级别 ----------------

    @Test
    void singleNonAuthoritativeClaimIsSingleSourceNotCorroborated() {
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "metrics-agent", "ev-1")));
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.basis()).isEqualTo(ClaimVerdict.Basis.SINGLE_SOURCE);
        assertThat(v.status()).isEqualTo(ClaimStatus.TRUE);
        assertThat(v.corroborated()).isFalse();
    }

    @Test
    void sameSourceDuplicatesCountAsOneVote() {
        // 同一 source 重复发声去重后只算一票 → 仍是 SINGLE_SOURCE，非 UNANIMOUS
        ClaimReducer noAuth = new ClaimReducer(Set.of());
        List<ClaimVerdict> verdicts = noAuth.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "metrics-agent", "ev-1"),
                claim("k", ClaimStatus.TRUE, "metrics-agent", "ev-2")));
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.basis()).isEqualTo(ClaimVerdict.Basis.SINGLE_SOURCE);
        assertThat(v.corroborated()).isFalse();
        assertThat(v.evidenceRefs()).containsExactlyInAnyOrder("ev-1", "ev-2");
    }

    @Test
    void singleAuthoritativeClaimUsesAuthorityBasis() {
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                claim("k", ClaimStatus.FALSE, AUTH, "ev-auth")));
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.basis()).isEqualTo(ClaimVerdict.Basis.AUTHORITATIVE_SOURCE);
        assertThat(v.status()).isEqualTo(ClaimStatus.FALSE);
    }

    // ---------------- 多源一致 / 权威源 / 双源佐证 ----------------

    @Test
    void twoOrMoreSourcesAgreeingIsUnanimousAndCorroborated() {
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "metrics-agent", "ev-1"),
                claim("k", ClaimStatus.TRUE, "logs-agent", "ev-2"),
                claim("k", ClaimStatus.TRUE, "change-agent", "ev-3")));
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.basis()).isEqualTo(ClaimVerdict.Basis.UNANIMOUS);
        assertThat(v.corroborated()).isTrue();
        assertThat(v.evidenceRefs()).containsExactlyInAnyOrder("ev-1", "ev-2", "ev-3");
    }

    @Test
    void authoritativeSourceSettlesConflict() {
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "metrics-agent", "ev-1"),
                claim("k", ClaimStatus.FALSE, AUTH, "ev-auth")));
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.status()).isEqualTo(ClaimStatus.FALSE);
        assertThat(v.basis()).isEqualTo(ClaimVerdict.Basis.AUTHORITATIVE_SOURCE);
        assertThat(v.evidenceRefs()).containsExactly("ev-auth");
    }

    @Test
    void conflictingAuthoritativeSourcesNeedReview() {
        ClaimReducer twoAuth = new ClaimReducer(Set.of("a1", "a2"));
        List<ClaimVerdict> verdicts = twoAuth.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "a1", "ev-1"),
                claim("k", ClaimStatus.FALSE, "a2", "ev-2")));
        assertThat(verdicts.get(0).basis()).isEqualTo(ClaimVerdict.Basis.NEEDS_REVIEW);
        assertThat(verdicts.get(0).status()).isEqualTo(ClaimStatus.UNKNOWN);
    }

    @Test
    void twoIndependentSourcesCorroborateAgainstDissenter() {
        ClaimReducer noAuth = new ClaimReducer(Set.of());
        List<ClaimVerdict> verdicts = noAuth.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "metrics-agent", "ev-1"),
                claim("k", ClaimStatus.TRUE, "logs-agent", "ev-2"),
                claim("k", ClaimStatus.FALSE, "change-agent", "ev-3")));
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.status()).isEqualTo(ClaimStatus.TRUE);
        assertThat(v.basis()).isEqualTo(ClaimVerdict.Basis.MULTI_SOURCE_CORROBORATION);
        assertThat(v.corroborated()).isTrue();
        assertThat(v.evidenceRefs()).containsExactlyInAnyOrder("ev-1", "ev-2");
    }

    /** TRUE 与 FALSE 各有 ≥2 独立来源 → 对峙即 NEEDS_REVIEW，禁枚举顺序优先 */
    @Test
    void standoffBetweenTwoCorroboratedStatusesNeedsReview() {
        ClaimReducer noAuth = new ClaimReducer(Set.of());
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
            assertThat(v.basis()).isEqualTo(ClaimVerdict.Basis.NEEDS_REVIEW);
            assertThat(v.status()).isEqualTo(ClaimStatus.UNKNOWN);
            assertThat(v.corroborated()).isFalse();
        }
    }

    @Test
    void unresolvableConflictNeedsReviewWithAllEvidence() {
        ClaimReducer noAuth = new ClaimReducer(Set.of());
        List<ClaimVerdict> verdicts = noAuth.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "a", "ev-1"),
                claim("k", ClaimStatus.FALSE, "b", "ev-2"),
                claim("k", ClaimStatus.UNKNOWN, "c", "ev-3")));
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.basis()).isEqualTo(ClaimVerdict.Basis.NEEDS_REVIEW);
        assertThat(v.status()).isEqualTo(ClaimStatus.UNKNOWN);
        assertThat(v.evidenceRefs()).containsExactlyInAnyOrder("ev-1", "ev-2", "ev-3");
    }

    // ---------------- 输出契约 ----------------

    @Test
    void verdictCarriesFullGroupIdentity() {
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                new Claim("k", ClaimStatus.TRUE, "r", "s", "tr", 7L,
                        List.of("ev"), "a", "snap-9")));
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.claimKey()).isEqualTo("k");
        assertThat(v.scope()).isEqualTo("s");
        assertThat(v.timeRange()).isEqualTo("tr");
        assertThat(v.observedGeneration()).isEqualTo(7L);
        assertThat(v.snapshotDigest()).isEqualTo("snap-9");
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
}
