package com.objwww.pr.control.alert.domain.claim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UT-AM4-21：ClaimVerdict 双哈希契约——claim_fingerprint（身份）与 claim_hash（内容）
 * <b>正交</b>：改内容不动身份、换代际/快照不动内容哈希（与 Evidence 四正交同思想）。
 * 另锚定 sources/evidenceRefs 排序去重（内容哈希与输入顺序无关）与 corroborated 判定。
 */
class ClaimVerdictTest {

    private static ClaimVerdict verdict(String reason, List<String> refs, List<String> sources) {
        return new ClaimVerdict("k", "scope", "2026-09-05/1h", 1L, null,
                ClaimStatus.TRUE, EvidenceBasis.MULTI_SOURCE_CONSISTENT,
                sources, reason, refs, "policy-v1");
    }

    @Test
    void 同值两实例_双哈希全等() {
        ClaimVerdict a = verdict("r-1", List.of("ev-1", "ev-2"), List.of("src-a", "src-b"));
        ClaimVerdict b = verdict("r-1", List.of("ev-1", "ev-2"), List.of("src-a", "src-b"));
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint()).hasSize(64);
        assertThat(a.contentHash()).isEqualTo(b.contentHash()).hasSize(64);
    }

    @Test
    void 内容变_fingerprint不变_hash必变() {
        ClaimVerdict base = verdict("r-1", List.of("ev-1"), List.of("src-a"));
        ClaimVerdict revised = verdict("r-2", List.of("ev-1"), List.of("src-a"));
        assertThat(revised.fingerprint()).isEqualTo(base.fingerprint());
        assertThat(revised.contentHash()).isNotEqualTo(base.contentHash());
    }

    @Test
    void 身份变_换快照_hash不变_fingerprint必变() {
        ClaimVerdict base = verdict("r-1", List.of("ev-1"), List.of("src-a"));
        ClaimVerdict nextSnapshot = new ClaimVerdict("k", "scope", "2026-09-05/1h", 1L, "snap-1",
                ClaimStatus.TRUE, EvidenceBasis.MULTI_SOURCE_CONSISTENT,
                List.of("src-a"), "r-1", List.of("ev-1"), "policy-v1");
        assertThat(nextSnapshot.fingerprint()).isNotEqualTo(base.fingerprint());
        assertThat(nextSnapshot.contentHash()).isEqualTo(base.contentHash());
    }

    @Test
    void 代际变_fingerprint变_内容hash不变() {
        ClaimVerdict base = verdict("r-1", List.of("ev-1"), List.of("src-a"));
        ClaimVerdict nextGen = new ClaimVerdict("k", "scope", "2026-09-05/1h", 2L, null,
                ClaimStatus.TRUE, EvidenceBasis.MULTI_SOURCE_CONSISTENT,
                List.of("src-a"), "r-1", List.of("ev-1"), "policy-v1");
        assertThat(nextGen.fingerprint()).isNotEqualTo(base.fingerprint());
        assertThat(nextGen.contentHash()).isEqualTo(base.contentHash());
    }

    @Test
    void null快照与显式null同指纹() {
        ClaimVerdict withNull = verdict("r-1", List.of("ev-1"), List.of("src-a"));
        ClaimVerdict explicitNull = new ClaimVerdict("k", "scope", "2026-09-05/1h", 1L, null,
                ClaimStatus.TRUE, EvidenceBasis.SINGLE_SOURCE,
                List.of("src-a"), "r-1", List.of("ev-1"), "policy-v1");
        assertThat(withNull.fingerprint()).isEqualTo(explicitNull.fingerprint());
    }

    @Test
    void sources与证据引用排序去重_哈希与输入顺序无关() {
        ClaimVerdict shuffled = verdict("r-1", List.of("ev-2", "ev-1", "ev-2"),
                List.of("src-b", "src-a", "src-b"));
        assertThat(shuffled.evidenceRefs()).containsExactly("ev-1", "ev-2");
        assertThat(shuffled.sources()).containsExactly("src-a", "src-b");
        ClaimVerdict ordered = verdict("r-1", List.of("ev-1", "ev-2"), List.of("src-a", "src-b"));
        assertThat(shuffled.contentHash()).isEqualTo(ordered.contentHash());
    }

    @Test
    void corroborated只认MULTI_SOURCE_CONSISTENT() {
        assertThat(verdict("r", List.of("e"), List.of("s")).corroborated()).isTrue();
        for (EvidenceBasis basis : List.of(EvidenceBasis.SINGLE_SOURCE,
                EvidenceBasis.MULTI_SOURCE_CONFLICT)) {
            ClaimVerdict v = new ClaimVerdict("k", "scope", "tr", 1L, null,
                    ClaimStatus.UNKNOWN, basis, List.of("s"), "r", List.of("e"), "policy-v1");
            assertThat(v.corroborated()).isFalse();
        }
    }

    @Test
    void 非法构造拒绝() {
        ClaimVerdict ok = verdict("r-1", List.of("ev-1"), List.of("src-a"));
        assertThatThrownBy(() -> new ClaimVerdict("", "scope", "tr", 1L, null,
                ClaimStatus.TRUE, EvidenceBasis.SINGLE_SOURCE, List.of("s"), "r",
                List.of("e"), "p")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClaimVerdict("k", " ", "tr", 1L, null,
                ClaimStatus.TRUE, EvidenceBasis.SINGLE_SOURCE, List.of("s"), "r",
                List.of("e"), "p")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClaimVerdict("k", "scope", "tr", -1L, null,
                ClaimStatus.TRUE, EvidenceBasis.SINGLE_SOURCE, List.of("s"), "r",
                List.of("e"), "p")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClaimVerdict("k", "scope", "tr", 1L, null,
                ClaimStatus.TRUE, EvidenceBasis.SINGLE_SOURCE, List.of("s"), "r",
                List.of(), "p")).isInstanceOf(IllegalArgumentException.class); // 无证据不成断言
        assertThatThrownBy(() -> new ClaimVerdict("k", "scope", "tr", 1L, null,
                ClaimStatus.TRUE, EvidenceBasis.SINGLE_SOURCE, List.of("s"), "r",
                List.of("e"), " ")).isInstanceOf(IllegalArgumentException.class); // 策略版本必填
        assertThat(ok.fingerprint()).isNotNull();
    }
}
