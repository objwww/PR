package com.objwww.pr.control.release.domain.service;

import java.util.Locale;
import java.util.Objects;

/**
 * Canary 稳定分桶纯函数（M5-10；方案 §3.1/E-17）。不持有运行态（L0，release 域
 * 零框架规则覆盖）。
 *
 * <p>口径冻结（改动任一项必须升 ALGORITHM 版本并评审）：
 * <ul>
 *   <li><b>哈希</b>：murmur3_x86_32（seed=0）对归一化键 {@code groupId + ":" + id}
 *       ——归一化 = trim + {@link Locale#ROOT} 小写（同键异写法恒同桶，Locale 无关）；
 *       跨 JVM 规范确定（参考向量对拍独立实现 mmh3，见 CanaryBucketerTest）；</li>
 *   <li><b>分桶</b>：flagd 无模偏公式 {@code (hash_u32 * totalWeight) >>> 32}
 *       （fractional.go:196-207 同构）——低位乘法取高位，权重非 2^32 因子也无偏好性
 *       （naive modulo 在权重不整除 2^32 时对高位桶有可测偏好）；</li>
 *   <li><b>黏性</b>：同键输出恒定（纯函数，无随机源、无状态）——修 Unleash random
 *       回退坑；<b>缺 stickiness key = 拒绝放量</b>（IAE，CanaryRouter 上层转
 *       NO_STICKINESS_KEY 决策落 HOLMES 主路径）。</li>
 * </ul>
 */
public final class CanaryBucketer {

    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;
    private static final int SEED = 0;

    private CanaryBucketer() {
    }

    /**
     * 稳定分桶：返回值 ∈ [0, totalWeight)。
     *
     * @throws IllegalArgumentException groupId/id null 或 blank、totalWeight < 1
     */
    public static int bucketOf(String groupId, String id, int totalWeight) {
        String key = normalizedKey(groupId, id);
        if (totalWeight < 1) {
            throw new IllegalArgumentException("totalWeight 必须 ≥ 1: " + totalWeight);
        }
        long hashU32 = murmur3Utf8(key) & 0xFFFFFFFFL;
        return (int) ((hashU32 * totalWeight) >>> 32);
    }

    /** 归一化 stickiness key（trim + Locale.ROOT 小写；两段任一缺失即拒绝） */
    public static String normalizedKey(String groupId, String id) {
        String group = groupId == null ? null : groupId.trim().toLowerCase(Locale.ROOT);
        String ident = id == null ? null : id.trim().toLowerCase(Locale.ROOT);
        if (group == null || group.isEmpty() || ident == null || ident.isEmpty()) {
            throw new IllegalArgumentException(
                    "stickiness key 两段必填（无 key = 拒绝放量）: groupId=" + groupId + ", id=" + id);
        }
        return group + ":" + ident;
    }

    /** murmur3_x86_32（seed=0，标准算法；包内可见供参考向量测试对拍） */
    static int murmur3Utf8(String input) {
        Objects.requireNonNull(input, "input 不得为 null");
        byte[] data = input.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int h1 = SEED;
        int nblocks = data.length / 4;
        for (int i = 0; i < nblocks; i++) {
            int i4 = i * 4;
            int k1 = (data[i4] & 0xFF)
                    | (data[i4 + 1] & 0xFF) << 8
                    | (data[i4 + 2] & 0xFF) << 16
                    | (data[i4 + 3] & 0xFF) << 24;
            k1 *= C1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= C2;
            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }
        int k1 = 0;
        int tail = nblocks * 4;
        switch (data.length & 3) {
            case 3:
                k1 ^= (data[tail + 2] & 0xFF) << 16;
                // fall through
            case 2:
                k1 ^= (data[tail + 1] & 0xFF) << 8;
                // fall through
            case 1:
                k1 ^= data[tail] & 0xFF;
                k1 *= C1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= C2;
                h1 ^= k1;
                break;
            default:
                break;
        }
        h1 ^= data.length;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        return h1;
    }
}
