package com.objwww.pr.control.alert.domain.service;

import com.objwww.pr.control.alert.domain.model.RcaTask;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;

/**
 * 纯函数：SLA 晋升（§6.2，替换 v1.1 的错误 aging 公式——"info 100 分钟后超越 critical"自相矛盾）。
 *
 * <p>priority：critical=200 / warning=100 / info=0（未知级别按 info 收敛，安全侧）。
 * deadline = readySince + sla(priority)；critical 永不到期 = {@link Instant#MAX}（映射 PG 'infinity'）。
 * 领取排序（诚实语义 = 等待超 SLA 才允许越级）：overdue DESC, priority DESC, deadline, createdAt, id。
 * 重试从 readySince（重置 READY 的时刻）起算——退避结束不插队。不保证零饿死（显式承认）。
 */
public record SlaPolicy(Duration warningSla, Duration infoSla) {

    public static final int PRIORITY_CRITICAL = 200;
    public static final int PRIORITY_WARNING = 100;
    public static final int PRIORITY_INFO = 0;

    public SlaPolicy {
        if (warningSla == null || warningSla.isZero() || warningSla.isNegative()
                || infoSla == null || infoSla.isZero() || infoSla.isNegative()) {
            throw new IllegalArgumentException("SLA 时长必须为正");
        }
    }

    /** 默认：warning 10min / info 60min（自研判断，DP 观测修正，残余风险 #2） */
    public static SlaPolicy defaults() {
        return new SlaPolicy(Duration.ofMinutes(10), Duration.ofMinutes(60));
    }

    /** 级别标签 → priority（未知 → info） */
    public int priority(String severityLabel) {
        if (severityLabel == null) {
            return PRIORITY_INFO;
        }
        return switch (severityLabel.toLowerCase()) {
            case "critical" -> PRIORITY_CRITICAL;
            case "warning" -> PRIORITY_WARNING;
            default -> PRIORITY_INFO;
        };
    }

    /** priority → deadline；critical = Instant.MAX（永不到期，永远排最前的候选） */
    public Instant deadline(Instant readySince, int priority) {
        return switch (priority) {
            case PRIORITY_CRITICAL -> Instant.MAX;
            case PRIORITY_WARNING -> readySince.plus(warningSla);
            default -> readySince.plus(infoSla);
        };
    }

    /** now 是否已过 deadline（SLA 晋升判据） */
    public static boolean overdue(Instant now, Instant deadline) {
        return !now.isBefore(deadline);
    }

    /**
     * 领取排序比较器（§6.2 SQL ORDER BY 的内存镜像，InMemory fake 与测试共用）：
     * (now≥deadline) DESC → priority DESC → deadline ASC → createdAt ASC → id ASC。
     */
    public static Comparator<RcaTask> claimOrder(Instant now) {
        return Comparator
                .comparing((RcaTask t) -> overdue(now, t.deadlineAt()) ? 0 : 1)   // 到期在前
                .thenComparing(Comparator.comparingInt(RcaTask::priority).reversed())
                .thenComparing(RcaTask::deadlineAt)
                .thenComparing(RcaTask::createdAt)
                .thenComparing(RcaTask::id);
    }
}
