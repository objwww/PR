package com.objwww.pr.control.ops.duty.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 轮换数学（M7-12 纯函数；AM7 §7 抄 Grafana OnCall/PagerDuty 语义）：
 * anchor_date + handoff_time 起，position = 已满周期数 mod 成员数——无状态、
 * 可离线复算（同输入恒同输出），不处理 override（那是 {@link DutyResolver} 的职责）。
 *
 * <p>不引入 cron 表达式排班（表达力过剩、排错困难，AM7 §7 裁定）。
 */
public final class RotationMath {

    public enum Rotation {DAILY, WEEKLY}

    private RotationMath() {
    }

    /**
     * @param memberCount 层内成员数（≥1；0 成员层由调用方先排除）
     * @return [0, memberCount)——now 时刻当班成员的下标
     */
    public static int position(Rotation rotation, LocalDate anchorDate, LocalTime handoff,
                               ZoneId zone, Instant now, int memberCount) {
        Objects.requireNonNull(rotation);
        Objects.requireNonNull(anchorDate);
        Objects.requireNonNull(handoff);
        Objects.requireNonNull(zone);
        Objects.requireNonNull(now);
        if (memberCount < 1) {
            throw new IllegalArgumentException("memberCount 从 1 起（空层由调用方排除）");
        }
        Instant anchor = anchorDate.atTime(handoff).atZone(zone).toInstant();
        Duration elapsed = Duration.between(anchor, now);
        long periods = elapsed.isNegative() ? 0L : switch (rotation) {
            case DAILY -> elapsed.toDays();
            case WEEKLY -> elapsed.toDays() / 7;
        };
        return Math.floorMod(periods, memberCount);
    }
}
