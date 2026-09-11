package com.objwww.pr.control.release.domain.model;

import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * EN-02 发布资格 UT（增强线方案 §8.2 评测证明契约）：证明至少绑定候选/基线 digest、
 * dataset manifest、runner/grader 版本、质量判定与费用对账状态、允许发布范围、
 * 审批/撤销记录。<b>MATCHED 只用于费用对账语义，不能代替质量 PASS</b>（E05/S09）：
 * FAIL+MATCHED 行必须可记录（费用与质量分开记账），但 {@link #passQualified()}
 * 恒 false。撤销三件套（at/by/reason）要么全空要么全在。
 */
class ReleaseQualificationTest {

    private static final Digest CANDIDATE = new Digest("ab".repeat(32));
    private static final Digest BASELINE = new Digest("cd".repeat(32));
    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");

    private static ReleaseQualification proof(String verdict, String usage) {
        return new ReleaseQualification(UUID.randomUUID(), CANDIDATE, BASELINE,
                "ef".repeat(32), "runner-v1", "grader-v1", verdict, usage,
                "scope:alert-primary", "grader-1", NOW, null, null, null);
    }

    @Test
    @DisplayName("PASS 未撤销 = passQualified；FAIL/INCONCLUSIVE 或已撤销 = 恒 false（S09 门）")
    void passQualifiedSemantics() {
        assertThat(proof("PASS", "MATCHED").passQualified()).isTrue();
        assertThat(proof("FAIL", "MATCHED").passQualified()).isFalse();
        assertThat(proof("INCONCLUSIVE", "UNKNOWN").passQualified()).isFalse();
        ReleaseQualification revoked = proof("PASS", "MATCHED")
                .revoked("safety-officer", "基线漂移", NOW.plusSeconds(60));
        assertThat(revoked.passQualified()).isFalse();
    }

    @Test
    @DisplayName("E05/S09：FAIL+MATCHED 可记录（费用对账≠质量）——行合法但永不合格")
    void usageMatchedCannotSubstituteQualityPass() {
        ReleaseQualification mismatched = proof("FAIL", "MATCHED");
        assertThat(mismatched.qualityVerdict()).isEqualTo("FAIL");
        assertThat(mismatched.usageStatus()).isEqualTo("MATCHED");
        assertThat(mismatched.passQualified()).isFalse();
    }

    @Test
    @DisplayName("枚举契约：quality_verdict ∈ {PASS,FAIL,INCONCLUSIVE}，usage_status ∈ {MATCHED,MISMATCH,UNKNOWN}")
    void verdictAndUsageEnums() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> proof("GREEN", "MATCHED"))
                .withMessageContaining("quality_verdict");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> proof("PASS", "PENDING"))
                .withMessageContaining("usage_status");
    }

    @Test
    @DisplayName("digest 契约：candidate/baseline 由 Digest 类型面强制 64 hex；dataset manifest 域内校验（baseline 可缺席）")
    void digestContract() {
        // candidate/baseline 是 Digest 类型：非法 hex 在类型边界即拒（record 内零重复校验）
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Digest("短"))
                .withMessageContaining("64 位");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReleaseQualification(UUID.randomUUID(), CANDIDATE,
                        BASELINE, "nothex", "r", "g", "PASS", "MATCHED",
                        "scope", "by", NOW, null, null, null))
                .withMessageContaining("dataset");
        ReleaseQualification noBaseline = new ReleaseQualification(UUID.randomUUID(),
                CANDIDATE, null, "ef".repeat(32), "runner-v1", "grader-v1",
                "PASS", "MATCHED", "scope", "by", NOW, null, null, null);
        assertThat(noBaseline.baselineDigest()).isNull();
    }

    @Test
    @DisplayName("撤销三件套一致性：revokedAt 单独在场 / 缺 reason → 拒绝；三件齐合法")
    void revocationTripleContract() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReleaseQualification(UUID.randomUUID(), CANDIDATE,
                        BASELINE, "ef".repeat(32), "r", "g", "PASS", "MATCHED",
                        "scope", "by", NOW, NOW.plusSeconds(1), null, null))
                .withMessageContaining("撤销");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReleaseQualification(UUID.randomUUID(), CANDIDATE,
                        BASELINE, "ef".repeat(32), "r", "g", "PASS", "MATCHED",
                        "scope", "by", NOW, NOW.plusSeconds(1), "officer", " "))
                .withMessageContaining("撤销");
        ReleaseQualification revoked = proof("PASS", "MATCHED")
                .revoked("safety-officer", "基线漂移", NOW.plusSeconds(1));
        assertThat(revoked.revokedBy()).isEqualTo("safety-officer");
    }

    @Test
    @DisplayName("必填契约：runner/grader 版本、scope、grantedBy 非 blank；不可变 helper 返回新记录不改原行")
    void requiredFieldsAndImmutability() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReleaseQualification(UUID.randomUUID(), CANDIDATE,
                        BASELINE, "ef".repeat(32), " ", "g", "PASS", "MATCHED",
                        "scope", "by", NOW, null, null, null))
                .withMessageContaining("runner");
        ReleaseQualification original = proof("PASS", "MATCHED");
        ReleaseQualification revoked = original.revoked("o", "r", NOW);
        assertThat(original.revokedAt()).isNull();
        assertThat(revoked.id()).isEqualTo(original.id());
        assertThat(revoked).isNotEqualTo(original);
    }
}
