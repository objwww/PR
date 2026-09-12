package com.objwww.pr.control.release.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Skill 匹配器 L0 面（S06 正确选用/弃选 + S07 冲突确定性 + S08 生产读面只吃
 * ACTIVE + 首期每角色 ≤1 Skill）。纯函数零框架。
 */
class SkillMatcherTest {

    private static Map<String, Object> skill(String name, List<String> alertnames,
            List<String> services) {
        return Map.of("name", name, "asset_digest", "d".repeat(64),
                "manifest", Map.of("alertnames", alertnames, "services", services));
    }

    @Test
    @DisplayName("S06：相似告警只一者符合 selector——命中选用、未命中弃选（退回通用调查）")
    void s06_selectAndSkip() {
        var candidates = List.of(skill("heap-skill",
                List.of("JvmHeapHigh"), List.of("svc-a")));

        // 命中：alertname+service 双维符合
        SkillMatcher.Match hit = SkillMatcher.match("JvmHeapHigh", "svc-a", candidates);
        assertThat(hit).isNotNull();
        assertThat(hit.name()).isEqualTo("heap-skill");
        assertThat(hit.conflictSuppressed()).isFalse();

        // service 不符 → 弃选（返回空 = 通用调查，不造占位）
        assertThat(SkillMatcher.match("JvmHeapHigh", "svc-b", candidates)).isNull();
        // alertname 不符 → 弃选
        assertThat(SkillMatcher.match("DiskFull", "svc-a", candidates)).isNull();
        // 空 ACTIVE 集 → 空
        assertThat(SkillMatcher.match("JvmHeapHigh", "svc-a", List.of())).isNull();
    }

    @Test
    @DisplayName("S07：两 Skill 匹配冲突 → 名字典序取一（不同时执行冲突步骤），冲突可观测")
    void s07_deterministicConflictResolution() {
        var conflicting = List.of(skill("zeta-skill",
                List.of("JvmHeapHigh"), List.of("svc-a")),
                skill("alpha-skill",
                        List.of("JvmHeapHigh"), List.of("svc-a")));

        SkillMatcher.Match match = SkillMatcher.match("JvmHeapHigh", "svc-a", conflicting);
        assertThat(match.name()).as("字典序确定性取一").isEqualTo("alpha-skill");
        assertThat(match.conflictSuppressed()).as("冲突面可观测（误选指标）").isTrue();
    }

    @Test
    @DisplayName("S08/通配：空 selector 集=通配；manifest 缺失=永不匹配（fail-closed）")
    void wildcardsAndFailClosed() {
        var wildcard = List.of(skill("wild-skill", List.of(), List.of()));
        assertThat(SkillMatcher.match("Anything", "any-svc", wildcard))
                .isNotNull().extracting(SkillMatcher.Match::name).isEqualTo("wild-skill");

        var broken = List.<Map<String, Object>>of(
                Map.of("name", "broken", "asset_digest", "d".repeat(64)));
        assertThat(SkillMatcher.match("Anything", "any-svc", broken))
                .as("无 manifest 段的候选不参与匹配（fail-closed）").isNull();
    }
}
