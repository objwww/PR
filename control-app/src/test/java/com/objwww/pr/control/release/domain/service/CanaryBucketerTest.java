package com.objwww.pr.control.release.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * M5-10 CanaryBucketer 纯函数 UT（方案 §3.1/E-17）：murmur3_x86_32（seed=0，规范
 * 冻结跨 JVM 一致）+ flagd 无模偏分桶公式 `(hash_u32 * totalWeight) >>> 32`
 * （fractional.go:196-207 同构——低位乘法取高位，权重非 2^32 因子也无偏好性）+
 * 归一化（trim + Locale.ROOT 小写，同键异写法恒同桶）+ 缺参拒绝。
 * 参考向量取自独立实现 mmh3 5.3.0（seed=0）——跨实现等值即算法正确性锚。
 */
class CanaryBucketerTest {

    @Test
    @DisplayName("murmur3 参考向量：跨实现对拍（mmh3 seed=0）——空串/常规串/复合键")
    void murmur3MatchesIndependentReferenceVectors() {
        assertThat(CanaryBucketer.murmur3Utf8("")).isZero();
        assertThat(CanaryBucketer.murmur3Utf8("hello")).isEqualTo(613153351);
        assertThat(CanaryBucketer.murmur3Utf8("g:abc")).isEqualTo(1566941584);
    }

    @Test
    @DisplayName("无模偏公式逐点对拍：bucket == (hash_u32 * weight) >>> 32（非 naive modulo）")
    void bucketIsTopBitsOfHashTimesWeight() {
        for (String id : new String[]{"a", "b", "incident-x", "hello"}) {
            int weight = 7;   // 非 2 的幂——naive modulo 在此有偏好性，公式没有
            long hashU32 = CanaryBucketer.murmur3Utf8("g:" + id) & 0xFFFFFFFFL;
            assertThat(CanaryBucketer.bucketOf("g", id, weight))
                    .as("id %s", id)
                    .isEqualTo((int) ((hashU32 * weight) >>> 32));
        }
        // weight=1 恒 0 桶；weight=100 桶域 [0,100)
        assertThat(CanaryBucketer.bucketOf("g", "a", 1)).isZero();
        assertThat(CanaryBucketer.bucketOf("g", "a", 100))
                .isBetween(0, 99);
    }

    @Test
    @DisplayName("黏性：同键重复分桶 1000 次恒同桶（重跑一致锚）")
    void sameKeyAlwaysSameBucket() {
        int expected = CanaryBucketer.bucketOf("incident-7", "session-42", 100);
        for (int i = 0; i < 1000; i++) {
            assertThat(CanaryBucketer.bucketOf("incident-7", "session-42", 100))
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("归一化：大小写/首尾空白异写法 → 同键同桶")
    void normalizationMakesKeyFormsEquivalent() {
        String canonical = String.valueOf(CanaryBucketer.bucketOf("g", "abc", 100));
        assertThat(String.valueOf(CanaryBucketer.bucketOf(" G ", " ABC ", 100)))
                .isEqualTo(canonical);
        assertThat(String.valueOf(CanaryBucketer.bucketOf("g", "ABC", 100)))
                .isEqualTo(canonical);
    }

    @Test
    @DisplayName("分布均匀性：10000 键 × 100 桶，各桶计数落在均值 ±40%（随机性面非精确均匀）")
    void distributionIsSpreadAcrossBuckets() {
        int buckets = 100;
        int[] counts = new int[buckets];
        for (int i = 0; i < 10_000; i++) {
            counts[CanaryBucketer.bucketOf("g", "key-" + i, buckets)]++;
        }
        for (int b = 0; b < buckets; b++) {
            assertThat(counts[b])
                    .as("桶 %d 计数 %d（期望均值 100 的 ±40%%）", b, counts[b])
                    .isBetween(60, 140);
        }
    }

    @Test
    @DisplayName("缺参拒绝：groupId/id null 或 blank、weight<1 → IAE（无 stickiness key 的原始面）")
    void missingInputsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> CanaryBucketer.bucketOf(null, "x", 100));
        assertThatIllegalArgumentException().isThrownBy(() -> CanaryBucketer.bucketOf("g", null, 100));
        assertThatIllegalArgumentException().isThrownBy(() -> CanaryBucketer.bucketOf("  ", "x", 100));
        assertThatIllegalArgumentException().isThrownBy(() -> CanaryBucketer.bucketOf("g", " ", 100));
        assertThatIllegalArgumentException().isThrownBy(() -> CanaryBucketer.bucketOf("g", "x", 0));
        assertThatIllegalArgumentException().isThrownBy(() -> CanaryBucketer.bucketOf("g", "x", -5));
    }

    @Test
    @DisplayName("桶值域：任意键桶 ∈ [0, weight)，且 1000 键至少命中 90% 的桶（覆盖面粗检）")
    void bucketsStayInRange() {
        int weight = 50;
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            int bucket = CanaryBucketer.bucketOf("g", "spread-" + i, weight);
            assertThat(bucket).isBetween(0, weight - 1);
            seen.add(bucket);
        }
        assertThat(seen.size()).isGreaterThanOrEqualTo(weight * 9 / 10);
    }
}
