package com.objwww.pr.control.domain.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-12（方案 §11 L1）：StaleEventGuard 全分支。
 * 防什么：LWW 快筛误杀/漏杀——等于水印必须放行（同秒并发不误杀，ST-18 的前提），
 * 缺字段不判（UNKNOWN 转权威读，EX-18），水印 NULL 放行（首事件）。
 */
class StaleEventGuardTest {

    private static final Instant T1 = Instant.parse("2025-06-01T12:00:00Z");
    private static final Instant T2 = Instant.parse("2025-06-01T12:00:01Z");

    @Test
    void olderThanWatermarkIsStale() {
        assertThat(StaleEventGuard.screen(T1, T2)).isEqualTo(StaleEventGuard.Verdict.STALE);
    }

    @Test
    void equalToWatermarkPasses() {
        // 同秒不误杀（UT-12 核心断言）：同 updated_at 不同 head 的裁决权在权威读
        assertThat(StaleEventGuard.screen(T1, T1)).isEqualTo(StaleEventGuard.Verdict.PASS);
    }

    @Test
    void newerThanWatermarkPasses() {
        assertThat(StaleEventGuard.screen(T2, T1)).isEqualTo(StaleEventGuard.Verdict.PASS);
    }

    @Test
    void nullWatermarkPasses() {
        // 首事件（投影行无水印）：无从比较，放行
        assertThat(StaleEventGuard.screen(T1, null)).isEqualTo(StaleEventGuard.Verdict.PASS);
    }

    @Test
    void missingEventTimestampIsUnknownRegardlessOfWatermark() {
        // EX-18：缺 updated_at 不猜——有水印 UNKNOWN，无水印也 UNKNOWN（而非 PASS）
        assertThat(StaleEventGuard.screen(null, T1)).isEqualTo(StaleEventGuard.Verdict.UNKNOWN);
        assertThat(StaleEventGuard.screen(null, null)).isEqualTo(StaleEventGuard.Verdict.UNKNOWN);
    }
}
