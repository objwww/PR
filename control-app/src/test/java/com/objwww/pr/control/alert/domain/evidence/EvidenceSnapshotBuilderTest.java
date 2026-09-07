package com.objwww.pr.control.alert.domain.evidence;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EvidenceSnapshotBuilder 穷举单测（AM4 M4-20）：成员 digest 排序 canonical 后再哈希
 * （不依赖 id 列）——同事实同 digest（顺序/id 无关）；成员/generation/config/tool
 * registry 任一变化 digest 必变。
 */
class EvidenceSnapshotBuilderTest {

    private static EvidenceSnapshotBuilder.Member member(String type, String digest) {
        return new EvidenceSnapshotBuilder.Member(type, digest);
    }

    private static String digestOf(long generation, String configDigest,
            String toolRegistryDigest, List<EvidenceSnapshotBuilder.Member> members) {
        return EvidenceSnapshotBuilder.digest(new EvidenceSnapshotBuilder.SnapshotInput(
                generation, configDigest, toolRegistryDigest, members));
    }

    private static final String D1 = "a".repeat(64);
    private static final String D2 = "b".repeat(64);

    @Test
    void utS01_同事实同digest_成员顺序与id无关() {
        String a = digestOf(0, "cfg", "tools", List.of(member("METRIC_QUERY", D1),
                member("LOG_QUERY", D2)));
        String b = digestOf(0, "cfg", "tools", List.of(member("LOG_QUERY", D2),
                member("METRIC_QUERY", D1)));
        assertThat(a).isEqualTo(b).hasSize(64);
    }

    @Test
    void utS02_成员集合变化_digest必变() {
        String base = digestOf(0, "cfg", "tools", List.of(member("METRIC_QUERY", D1)));
        String added = digestOf(0, "cfg", "tools", List.of(member("METRIC_QUERY", D1),
                member("LOG_QUERY", D2)));
        String removed = digestOf(0, "cfg", "tools", List.of());
        assertThat(added).isNotEqualTo(base);
        assertThat(removed).isNotEqualTo(base);
    }

    @Test
    void utS03_generation变化_digest必变() {
        assertThat(digestOf(1, "cfg", "tools", List.of(member("T", D1))))
                .isNotEqualTo(digestOf(0, "cfg", "tools", List.of(member("T", D1))));
    }

    @Test
    void utS04_config与toolRegistry变化_digest必变() {
        String base = digestOf(0, "cfg", "tools", List.of(member("T", D1)));
        assertThat(digestOf(0, "cfg2", "tools", List.of(member("T", D1))))
                .isNotEqualTo(base);
        assertThat(digestOf(0, "cfg", "tools2", List.of(member("T", D1))))
                .isNotEqualTo(base);
    }

    @Test
    void utS05_空成员集合法_同空事实同digest() {
        assertThat(digestOf(0, "cfg", "tools", new ArrayList<>()))
                .isEqualTo(digestOf(0, "cfg", "tools", List.of()));
    }
}
