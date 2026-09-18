package com.objwww.pr.control.eval.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** FUP-03：launch_plan 快照 caseKeys 注入与读取（对比门冻结计划真分母）。 */
class EvalLaunchPlanCaseKeysTest {

    private static final String PAYLOAD =
            "{\"displayName\":\"n\",\"mode\":\"L\",\"datasetVersion\":\"eval-ds-1\","
                    + "\"roundsPerScenario\":null,\"panel\":\"SMOKE\"}";

    @Test
    @DisplayName("withCaseKeys：注入有序去重键集，原字段保留")
    void injectsSortedDistinctKeys() {
        String out = EvalLaunchExecutor.withCaseKeys(PAYLOAD,
                List.of("S5", "S3", "S3", "S4"));

        assertThat(out).contains("\"caseKeys\":[\"S3\",\"S4\",\"S5\"]");
        assertThat(out).contains("\"panel\":\"SMOKE\"");
        assertThat(out).contains("\"datasetVersion\":\"eval-ds-1\"");
        // 幂等：重复注入不叠加
        String again = EvalLaunchExecutor.withCaseKeys(out, List.of("S3", "S4", "S5"));
        assertThat(again).isEqualTo(out);
    }

    @Test
    @DisplayName("解析失败/非对象 payload → 原样返回（快照增强失败不阻断跑批）")
    void malformedPayloadReturnsAsIs() {
        assertThat(EvalLaunchExecutor.withCaseKeys("not-json", List.of("S3")))
                .isEqualTo("not-json");
        assertThat(EvalLaunchExecutor.withCaseKeys("[1,2]", List.of("S3")))
                .isEqualTo("[1,2]");
    }

    @Test
    @DisplayName("snapshotCaseKeys：双侧全等 → 冻结键集；缺席/不全等 → null（回退旧行为）")
    void snapshotCaseKeysPreference() {
        List<String> a = List.of("S3", "S4", "S5");
        String ja = "{\"caseKeys\":[\"S3\",\"S4\",\"S5\"],\"panel\":\"SMOKE\"}";
        String jb = "{\"caseKeys\":[\"S3\",\"S4\",\"S5\"],\"panel\":\"SMOKE\"}";
        String jd = "{\"caseKeys\":[\"S3\",\"S4\"],\"panel\":\"SMOKE\"}";

        assertThat(EvalCompareService.snapshotCaseKeys(ja, jb)).isEqualTo(a);
        assertThat(EvalCompareService.snapshotCaseKeys(ja, jd)).isNull();
        assertThat(EvalCompareService.snapshotCaseKeys(ja, null)).isNull();
        assertThat(EvalCompareService.snapshotCaseKeys("{\"panel\":\"SMOKE\"}", jb)).isNull();
    }
}
