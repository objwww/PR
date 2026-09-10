package com.objwww.pr.control.ops.duty.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M7-12 轮换数学锚点测试（AM7 §11 单元面）：anchor_date + handoff_time 无状态取模——
 * 跨 handoff 边界、日/周轮换、pre-anchor、成员数变化的数学钉死。
 */
class RotationMathTest {

    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");
    private static final LocalDate ANCHOR = LocalDate.of(2026, 9, 7); // 周一
    private static final LocalTime HANDOFF = LocalTime.of(9, 0);

    private static Instant at(int dayOffset, String time) {
        return ANCHOR.plusDays(dayOffset).atTime(LocalTime.parse(time)).atZone(CST).toInstant();
    }

    @Test
    void anchorDayBeforeHandoffIsPeriodZero() {
        // 锚点日 08:59（handoff 前 1 分钟）→ 第 0 班
        assertThat(RotationMath.position(RotationMath.Rotation.DAILY,
                ANCHOR, HANDOFF, CST, at(0, "08:59"), 3)).isZero();
    }

    @Test
    void dailyHandoffBoundaryFlipsPeriod() {
        // day1 08:59 = 23h59m 未满一天 → 0；day1 09:00 整 → 1
        assertThat(RotationMath.position(RotationMath.Rotation.DAILY,
                ANCHOR, HANDOFF, CST, at(1, "08:59"), 3)).isZero();
        assertThat(RotationMath.position(RotationMath.Rotation.DAILY,
                ANCHOR, HANDOFF, CST, at(1, "09:00"), 3)).isEqualTo(1);
    }

    @Test
    void dailyPositionWrapsModuloMemberCount() {
        // 7 整天 → 7 mod 3 = 1；6 整天 → 0（回归锚）
        assertThat(RotationMath.position(RotationMath.Rotation.DAILY,
                ANCHOR, HANDOFF, CST, at(7, "10:00"), 3)).isEqualTo(1);
        assertThat(RotationMath.position(RotationMath.Rotation.DAILY,
                ANCHOR, HANDOFF, CST, at(6, "10:00"), 3)).isZero();
    }

    @Test
    void weeklyCountsFullWeeksOnly() {
        // 次周一 08:59 = 6d23h59m → 0 周；09:00 → 1 周
        assertThat(RotationMath.position(RotationMath.Rotation.WEEKLY,
                ANCHOR, HANDOFF, CST, at(7, "08:59"), 2)).isZero();
        assertThat(RotationMath.position(RotationMath.Rotation.WEEKLY,
                ANCHOR, HANDOFF, CST, at(7, "09:00"), 2)).isEqualTo(1);
        // 两周一档：14 天 → 2 mod 2 = 0
        assertThat(RotationMath.position(RotationMath.Rotation.WEEKLY,
                ANCHOR, HANDOFF, CST, at(14, "10:00"), 2)).isZero();
    }

    @Test
    void beforeAnchorClampsToPeriodZero() {
        // 早于 anchor 的时刻不产生负周期（防御：时钟回拨/错配 anchor）
        assertThat(RotationMath.position(RotationMath.Rotation.DAILY,
                ANCHOR, HANDOFF, CST, at(-1, "12:00"), 3)).isZero();
    }

    @Test
    void singleMemberAlwaysPositionZero() {
        assertThat(RotationMath.position(RotationMath.Rotation.DAILY,
                ANCHOR, HANDOFF, CST, at(30, "23:00"), 1)).isZero();
    }

    @Test
    void nonPositiveMemberCountIsRejected() {
        assertThatThrownBy(() -> RotationMath.position(RotationMath.Rotation.DAILY,
                ANCHOR, HANDOFF, CST, at(0, "10:00"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
