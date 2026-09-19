package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.model.EvalLaunchPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PAGE-03 能力闸门边界：模式白名单、覆盖项/限额闭面、并发与轮次上限、
 * 全支持面放行；拒绝异常携带同源支持范围描述。
 */
class EvalLaunchGateTest {

    private static EvalLaunchPlan plan(String mode, String dataset, String model,
                                       String prompt, Long budget, Integer concurrency,
                                       Long deadline, Integer rounds) {
        return new EvalLaunchPlan("n", mode, dataset, model, prompt, budget, concurrency,
                deadline, rounds);
    }

    @Test
    @DisplayName("闭面闸门（生产默认）：E/B、覆盖项、非空限额、并发2、轮次超限全拒")
    void closedGateRejectsUnsupported() {
        EvalLaunchGate gate = EvalLaunchGate.closed(Set.of("L"), "eval-ds-1", 1, 30);

        assertThatThrownBy(() -> gate.check(plan("E", "eval-ds-1", null, null, null, null, null, null)))
                .isInstanceOf(EvalLaunchGate.EvalLaunchUnsupportedException.class);
        assertThatThrownBy(() -> gate.check(plan("B", "eval-ds-1", null, null, null, null, null, null)))
                .isInstanceOf(EvalLaunchGate.EvalLaunchUnsupportedException.class);
        assertThatThrownBy(() -> gate.check(plan("L", "eval-ds-1", "m", null, null, null, null, null)))
                .isInstanceOf(EvalLaunchGate.EvalLaunchUnsupportedException.class);
        assertThatThrownBy(() -> gate.check(plan("L", "eval-ds-1", null, "p", null, null, null, null)))
                .isInstanceOf(EvalLaunchGate.EvalLaunchUnsupportedException.class);
        assertThatThrownBy(() -> gate.check(plan("L", "other-ds", null, null, null, null, null, null)))
                .isInstanceOf(EvalLaunchGate.EvalLaunchUnsupportedException.class);
        assertThatThrownBy(() -> gate.check(plan("L", "eval-ds-1", null, null, 1L, null, null, null)))
                .isInstanceOf(EvalLaunchGate.EvalLaunchUnsupportedException.class);
        assertThatThrownBy(() -> gate.check(plan("L", "eval-ds-1", null, null, null, null, 1L, null)))
                .isInstanceOf(EvalLaunchGate.EvalLaunchUnsupportedException.class);
        assertThatThrownBy(() -> gate.check(plan("L", "eval-ds-1", null, null, null, 2, null, null)))
                .isInstanceOf(EvalLaunchGate.EvalLaunchUnsupportedException.class);
        assertThatThrownBy(() -> gate.check(plan("L", "eval-ds-1", null, null, null, null, null, 31)))
                .isInstanceOf(EvalLaunchGate.EvalLaunchUnsupportedException.class);
    }

    @Test
    @DisplayName("闭面放行：L + 部署版本 + 全缺省 + 并发 null/1 + 轮次 null/上限值")
    void closedGateAcceptsDefaults() {
        EvalLaunchGate gate = EvalLaunchGate.closed(Set.of("L"), "eval-ds-1", 1, 30);

        assertThatCode(() -> gate.check(plan("L", "eval-ds-1", null, null, null, null, null, null)))
                .doesNotThrowAnyException();
        assertThatCode(() -> gate.check(plan("L", "eval-ds-1", null, null, null, 1, null, 30)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("P6-G8 panel 值域：SMOKE 放行；未知 panel 拒绝且 supported 同源透出")
    void panelValueDomainEnforced() {
        EvalLaunchGate gate = EvalLaunchGate.closed(Set.of("L"), "eval-ds-1", 1, 30);
        EvalLaunchPlan smoke = new EvalLaunchPlan("n", "L", "eval-ds-1", null, null,
                null, null, null, null, "SMOKE");
        EvalLaunchPlan unknown = new EvalLaunchPlan("n", "L", "eval-ds-1", null, null,
                null, null, null, null, "NIGHTLY");

        assertThatCode(() -> gate.check(smoke)).doesNotThrowAnyException();
        assertThatThrownBy(() -> gate.check(unknown))
                .isInstanceOf(EvalLaunchGate.EvalLaunchUnsupportedException.class)
                .hasMessageContaining("NIGHTLY")
                .hasMessageContaining("SMOKE")
                .satisfies(e -> assertThat(
                        ((EvalLaunchGate.EvalLaunchUnsupportedException) e).code())
                        .isEqualTo("PANEL_NOT_SUPPORTED"));
    }

    @Test
    @DisplayName("全支持面闸门：E/B/覆盖项/预算/截止/高并发按构造参数放行")
    void permissiveGateAcceptsConfiguredScope() {
        EvalLaunchGate gate = new EvalLaunchGate(Set.of("E", "B", "L"), Set.of("ds-1", "ds-2"),
                8, 20, true, true, true, true, true);

        assertThatCode(() -> gate.check(plan("E", "ds-2", "m", "p", 5L, 8, 60L, 20)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("describe 与拒绝异常 supported 同源；modes/datasetVersions 不可变")
    void describeMatchesExceptionPayload() {
        EvalLaunchGate gate = EvalLaunchGate.closed(Set.of("L"), "eval-ds-1", 1, 30);

        EvalLaunchGate.EvalLaunchUnsupportedException e =
                new EvalLaunchGate.EvalLaunchUnsupportedException("X", "msg", gate.describe());
        assertThat(e.supported())
                .containsEntry("modes", java.util.List.of("L"))
                .containsEntry("datasetVersions", java.util.List.of("eval-ds-1"))
                .containsEntry("maxConcurrency", 1)
                .containsEntry("maxRoundsPerScenario", 30)
                .containsEntry("modelOverride", false)
                .containsEntry("budgetMaxTokens", false)
                .containsEntry("deadlineSeconds", false);
        // 描述面只读：调用方改不动支持范围（防前端读面被篡改语义）
        assertThatThrownBy(() -> gate.describe().put("modes", java.util.List.of("E")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("SAFE-02 launchDisabled：任何计划（即便支持面内的 L）一律 LAUNCH_DISABLED 拒绝，"
            + "describe 暴露 launchEnabled=false")
    void launchDisabledRejectsEverythingAndDescribesItself() {
        EvalLaunchGate gate = EvalLaunchGate.launchDisabled(Set.of("L"), "eval-ds-1", 1, 30);

        EvalLaunchGate.EvalLaunchUnsupportedException e =
                new EvalLaunchGate.EvalLaunchUnsupportedException("X", "msg", gate.describe());
        assertThat(e.supported()).containsEntry("launchEnabled", false);
        assertThatThrownBy(() -> gate.check(plan("L", "eval-ds-1", null, null, null, null, null, null)))
                .isInstanceOfSatisfying(EvalLaunchGate.EvalLaunchUnsupportedException.class,
                        ex -> assertThat(ex.code()).isEqualTo("LAUNCH_DISABLED"));
        assertThatThrownBy(() -> gate.check(plan("E", "eval-ds-1", null, null, null, null, null, null)))
                .isInstanceOfSatisfying(EvalLaunchGate.EvalLaunchUnsupportedException.class,
                        ex -> assertThat(ex.code()).isEqualTo("LAUNCH_DISABLED"));
    }

    @Test
    @DisplayName("SAFE-02 describe：closed 闸门 launchEnabled=true（发起开放面与拒绝面可区分）")
    void closedGateDescribesLaunchEnabled() {
        assertThat(EvalLaunchGate.closed(Set.of("L"), "eval-ds-1", 1, 30).describe())
                .containsEntry("launchEnabled", true);
    }

    @Test
    @DisplayName("构造边界：空 modes / 非法上限拒绝装配（fail-fast）")
    void constructionGuards() {
        assertThatThrownBy(() -> EvalLaunchGate.closed(Set.of(), "ds", 1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvalLaunchGate.closed(Set.of("L"), "ds", 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvalLaunchGate.closed(Set.of("L"), "ds", 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
