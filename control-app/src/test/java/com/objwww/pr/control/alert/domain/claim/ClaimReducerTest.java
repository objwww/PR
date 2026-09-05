package com.objwww.pr.control.alert.domain.claim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-AM4-22：ClaimReducer 矩阵——冲突 / 支持 / 反证 / NEEDS_REVIEW。
 * 全程断言语义来源是规则（UNANIMOUS/权威源/双源佐证），无任何置信度投票。
 */
class ClaimReducerTest {

    private static final String AUTH = "holmes-baseline";
    private final ClaimReducer reducer = new ClaimReducer(Set.of(AUTH));

    private static Claim claim(String key, ClaimStatus status, String source, String... evidence) {
        return new Claim(key, status, "r-" + status, "scope", "2026-09-05/1h", 1L,
                List.of(evidence), source);
    }

    @Test
    void unanimousAgreementIsCorroborated() {
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "metrics-agent", "ev-1"),
                claim("k", ClaimStatus.TRUE, "logs-agent", "ev-2"),
                claim("k", ClaimStatus.TRUE, "change-agent", "ev-3")));

        assertThat(verdicts).hasSize(1);
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.status()).isEqualTo(ClaimStatus.TRUE);
        assertThat(v.basis()).isEqualTo(ClaimVerdict.Basis.UNANIMOUS);
        assertThat(v.evidenceRefs()).containsExactlyInAnyOrder("ev-1", "ev-2", "ev-3");
    }

    @Test
    void unanimousFalseIsCorroboratedToo() {
        // 反证全一致同样 corroborated（规则只看一致性，不偏好 TRUE）
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                claim("k", ClaimStatus.FALSE, "metrics-agent", "ev-1"),
                claim("k", ClaimStatus.FALSE, "logs-agent", "ev-2")));
        assertThat(verdicts.get(0).status()).isEqualTo(ClaimStatus.FALSE);
        assertThat(verdicts.get(0).basis()).isEqualTo(ClaimVerdict.Basis.UNANIMOUS);
    }

    @Test
    void authoritativeSourceSettlesConflict() {
        // 支持 vs 反证冲突：权威源一锤定音
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
        // 权威源互不一致 → 不擅自取舍
        ClaimReducer twoAuth = new ClaimReducer(Set.of("a1", "a2"));
        List<ClaimVerdict> verdicts = twoAuth.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "a1", "ev-1"),
                claim("k", ClaimStatus.FALSE, "a2", "ev-2")));
        assertThat(verdicts.get(0).basis()).isEqualTo(ClaimVerdict.Basis.NEEDS_REVIEW);
        assertThat(verdicts.get(0).status()).isEqualTo(ClaimStatus.UNKNOWN);
    }

    @Test
    void twoIndependentSourcesCorroborate() {
        // 无权威源在场：≥2 独立 source 同状态 → 双源佐证（冲突方单源不敌）
        ClaimReducer noAuth = new ClaimReducer(Set.of());
        List<ClaimVerdict> verdicts = noAuth.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "metrics-agent", "ev-1"),
                claim("k", ClaimStatus.TRUE, "logs-agent", "ev-2"),
                claim("k", ClaimStatus.FALSE, "change-agent", "ev-3")));
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.status()).isEqualTo(ClaimStatus.TRUE);
        assertThat(v.basis()).isEqualTo(ClaimVerdict.Basis.MULTI_SOURCE_CORROBORATION);
        assertThat(v.evidenceRefs()).containsExactlyInAnyOrder("ev-1", "ev-2");
    }

    @Test
    void sameSourceTwiceIsNotCorroboration() {
        // 同一 source 重复发声不算双源（防刷票）
        ClaimReducer noAuth = new ClaimReducer(Set.of());
        List<ClaimVerdict> verdicts = noAuth.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "metrics-agent", "ev-1"),
                claim("k", ClaimStatus.TRUE, "metrics-agent", "ev-2"),
                claim("k", ClaimStatus.FALSE, "logs-agent", "ev-3")));
        assertThat(verdicts.get(0).basis()).isEqualTo(ClaimVerdict.Basis.NEEDS_REVIEW);
    }

    @Test
    void unresolvableConflictNeedsReview() {
        // 1v1v1 三方三态，无权威无佐证
        ClaimReducer noAuth = new ClaimReducer(Set.of());
        List<ClaimVerdict> verdicts = noAuth.reduce(List.of(
                claim("k", ClaimStatus.TRUE, "a", "ev-1"),
                claim("k", ClaimStatus.FALSE, "b", "ev-2"),
                claim("k", ClaimStatus.UNKNOWN, "c", "ev-3")));
        ClaimVerdict v = verdicts.get(0);
        assertThat(v.basis()).isEqualTo(ClaimVerdict.Basis.NEEDS_REVIEW);
        assertThat(v.status()).isEqualTo(ClaimStatus.UNKNOWN);
        // NEEDS_REVIEW 带上全部证据供人工裁决
        assertThat(v.evidenceRefs()).containsExactlyInAnyOrder("ev-1", "ev-2", "ev-3");
    }

    @Test
    void groupsByClaimKeyAndOutputIsSorted() {
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                claim("z-key", ClaimStatus.TRUE, "a", "ev-z"),
                claim("a-key", ClaimStatus.TRUE, "b", "ev-a")));
        assertThat(verdicts).extracting(ClaimVerdict::claimKey)
                .containsExactly("a-key", "z-key");
    }

    @Test
    void emptyInputProducesEmptyOutput() {
        assertThat(reducer.reduce(List.of())).isEmpty();
    }

    @Test
    void singleClaimIsUnanimousByItself() {
        List<ClaimVerdict> verdicts = reducer.reduce(List.of(
                claim("k", ClaimStatus.UNKNOWN, "a", "ev-1")));
        assertThat(verdicts.get(0).basis()).isEqualTo(ClaimVerdict.Basis.UNANIMOUS);
        assertThat(verdicts.get(0).status()).isEqualTo(ClaimStatus.UNKNOWN);
    }
}
