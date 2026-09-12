package com.objwww.pr.control.release.domain.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Skill 匹配器（EN-08 §四：首期每角色最多一个方法 Skill，匹配失败退回通用调查）：
 * 只吃 ACTIVE 候选（S08 生产/评测身份隔离——EVALUATING 候选不在生产读面）。
 *
 * <p>selector 语义（manifest.selector）：{@code alertnames}/{@code services} 封闭
 * 集合，空集=通配；两维都必须命中才算匹配（S06：相似告警只一者符合 selector →
 * 正确选用/弃选）。冲突（S07）：多 Skill 同告警匹配 → 名字典序取一，不同时执行
 * 冲突步骤；冲突计数随结果返回（误选指标面）。
 *
 * <p>纯函数零框架（L0：release.domain.service 零框架规则）。
 */
public final class SkillMatcher {

    /** 匹配结果：至多一个 Skill（首期每角色 ≤1 红线）+ 冲突/弃选指标 */
    public record Match(String name, String assetDigest, boolean conflictSuppressed) {
    }

    /**
     * 从 ACTIVE 候选中确定性选一：selector 双维命中过滤 → 名字典序唯一化。
     * @param activeCandidates 只含 status=ACTIVE 的候选（调用方职责，S08）
     * @return 空 = 无匹配（退回通用调查，不造占位 Skill）
     */
    public static Match match(String alertname, String service,
            List<Map<String, Object>> activeCandidates) {
        Objects.requireNonNull(alertname, "alertname");
        Objects.requireNonNull(service, "service");
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Map<String, Object> candidate : activeCandidates) {
            if (matches(candidate.get("manifest"), alertname, service)) {
                hits.add(candidate);
            }
        }
        if (hits.isEmpty()) {
            return null;
        }
        hits.sort(Comparator.comparing(c -> String.valueOf(c.get("name"))));
        Map<String, Object> chosen = hits.get(0);
        boolean suppressed = hits.size() > 1;
        return new Match(String.valueOf(chosen.get("name")),
                String.valueOf(chosen.get("asset_digest")), suppressed);
    }

    /** selector 双维命中：空集/缺键=通配；非空列表则必须包含（S06 正确弃选面） */
    static boolean matches(Object manifest, String alertname, String service) {
        if (!(manifest instanceof Map<?, ?> m)) {
            return false;
        }
        return contains(m.get("alertnames"), alertname)
                && contains(m.get("services"), service);
    }

    static boolean contains(Object raw, String value) {
        if (raw == null) {
            return true;
        }
        if (raw instanceof List<?> list) {
            return list.isEmpty() || list.stream().map(String::valueOf)
                    .anyMatch(item -> item.equals(value));
        }
        return false;
    }

    private SkillMatcher() {
    }
}
