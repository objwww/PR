package com.objwww.pr.arenaadmin.fingerprint;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AM 兼容指纹向量（M2-24/C-6）：算法 = FNV-1a 64 over 排序标签、
 * 每对 name ff value ff（尾随分隔，含末对）——以 195 真栈 ground truth
 * 反推锁定（同一标签集 AM API 实测 0d7404ae811ae84a，见类注释），
 * 向量固化后算法漂移即测试红。
 */
class AlertmanagerFingerprintTest {

    @Test
    void 冻结标签集向量() {
        assertThat(AlertmanagerFingerprint.of(Map.of(
                "alertname", "ArenaOrderStuck",
                "fault_type", "F3",
                "service", "order-arena"))).isEqualTo("49a92b3596b9161e");

        assertThat(AlertmanagerFingerprint.of(Map.of(
                "alertname", "ArenaDuplicateOrders",
                "fault_type", "F1",
                "service", "order-arena"))).isEqualTo("426ea6fc9dbc64b4");

        assertThat(AlertmanagerFingerprint.of(Map.of(
                "alertname", "ArenaIllegalTransitions",
                "fault_type", "F2",
                "service", "order-arena"))).isEqualTo("71616c120c3ea46d");

        assertThat(AlertmanagerFingerprint.of(Map.of(
                "alertname", "ArenaDomainProbeDown",
                "service", "order-arena"))).isEqualTo("9c3c63d67ab4494e");
    }

    @Test
    void E2E最终标签集向量_含抓取标签与规则标签() {
        Map<String, String> f3 = new HashMap<>();
        f3.put("alertname", "ArenaOrderStuck");
        f3.put("fault_type", "F3");
        f3.put("service", "order-arena");
        f3.put("job", "order-arena");
        f3.put("instance", "order-arena:8080");
        f3.put("severity", "page");
        assertThat(AlertmanagerFingerprint.of(f3)).isEqualTo("f95e79c26f0e7b4c");
    }

    @Test
    void AM真栈ground_truth向量_195实测() {
        // 2026-09-05 195 真栈实测：AM 对下述标签集返回 fingerprint=0d7404ae811ae84a
        Map<String, String> f1 = new HashMap<>();
        f1.put("alertname", "ArenaDuplicateOrders");
        f1.put("fault_type", "F1");
        f1.put("service", "order-arena");
        f1.put("job", "order-arena");
        f1.put("instance", "order-arena:8080");
        f1.put("severity", "page");
        assertThat(AlertmanagerFingerprint.of(f1)).isEqualTo("0d7404ae811ae84a");

        Map<String, String> f2 = new HashMap<>(f1);
        f2.put("alertname", "ArenaIllegalTransitions");
        f2.put("fault_type", "F2");
        assertThat(AlertmanagerFingerprint.of(f2)).isEqualTo("653693464eea7e9b");
    }

    @Test
    void 标签顺序无关() {
        Map<String, String> a = new HashMap<>();
        a.put("alertname", "ArenaOrderStuck");
        a.put("fault_type", "F3");
        a.put("service", "order-arena");
        Map<String, String> b = new HashMap<>();
        b.put("service", "order-arena");
        b.put("fault_type", "F3");
        b.put("alertname", "ArenaOrderStuck");
        assertThat(AlertmanagerFingerprint.of(a))
                .isEqualTo(AlertmanagerFingerprint.of(b));
    }

    @Test
    void 任一标签值变化即指纹变化() {
        Map<String, String> base = new HashMap<>();
        base.put("alertname", "ArenaOrderStuck");
        base.put("fault_type", "F3");
        Map<String, String> drifted = new HashMap<>(base);
        drifted.put("fault_type", "F1");
        assertThat(AlertmanagerFingerprint.of(drifted))
                .isNotEqualTo(AlertmanagerFingerprint.of(base));
    }
}
