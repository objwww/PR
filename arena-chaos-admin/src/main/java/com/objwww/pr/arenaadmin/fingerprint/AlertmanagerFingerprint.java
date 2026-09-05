package com.objwww.pr.arenaadmin.fingerprint;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Alertmanager 兼容指纹（M2-24，C-6 冻结）：与 prometheus/common 的
 * LabelSet.Fingerprint 完全一致——FNV-1a 64（offset 0xcbf29ce484222325、
 * prime 1099511628211、uint64 溢出算术），标签名典序遍历，
 * 逐对 hash(name) → hash(0xff) → hash(value) → hash(0xff)：
 * <b>每对尾随一个 0xff 分隔字节（含最后一对）</b>——即 ByteSerialize 形态
 * （name ff value ff name ff value ff …）。
 *
 * <p>算法以 195 真栈 ground truth 反推锁定：同一标签集 AM API 实测
 * 0d7404ae811ae84a，仅变体 B 复合（无尾随变体给 0815624c44921472，已被
 * E2E 指纹三重一致检查否决）；向量固化在单测中（AM2-24 验收）。
 * AM 的 alert fingerprint 就是该算法对<b>最终标签集</b>（含规则标签与
 * 抓取携带标签）的结果；scenario_map 以它单键直配告警→场景。
 */
public final class AlertmanagerFingerprint {

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L; // 14695981039346656037 (uint64)
    private static final long PRIME = 1099511628211L;
    private static final int SEPARATOR_BYTE = 0xff;

    private AlertmanagerFingerprint() {
    }

    /** @return 16 位十六进制小写（与 AM API 暴露的 fingerprint 同形） */
    public static String of(Map<String, String> labels) {
        long hash = OFFSET_BASIS;
        for (Map.Entry<String, String> e : new TreeMap<>(labels).entrySet()) {
            hash = hashAdd(hash, e.getKey().getBytes(StandardCharsets.UTF_8));
            hash = hashAddByte(hash, SEPARATOR_BYTE);
            hash = hashAdd(hash, e.getValue().getBytes(StandardCharsets.UTF_8));
            hash = hashAddByte(hash, SEPARATOR_BYTE);
        }
        return String.format("%016x", hash);
    }

    private static long hashAdd(long hash, byte[] bytes) {
        for (byte b : bytes) {
            hash = hashAddByte(hash, b & 0xff);
        }
        return hash;
    }

    private static long hashAddByte(long hash, int b) {
        hash ^= (long) b;
        return hash * PRIME;
    }
}
