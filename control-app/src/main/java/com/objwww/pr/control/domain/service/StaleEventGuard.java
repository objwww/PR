package com.objwww.pr.control.domain.service;

import java.time.Instant;

/**
 * LWW 快筛（M1-T05，方案 §4.3 第 1 步）：纯函数三值判定，**只是省钱的前置筛，不是权威判断**
 * （修正 #6：Run 创建/废弃一律以 GitHub 当前状态为准，本 Guard 永不直接决定建/废 Run）。
 *
 * <p>判定表（UT-12 逐格覆盖）：
 * <ul>
 *   <li>事件 updatedAt 有效且 < 水印 → STALE：明显乱序旧事件，零 API 直接 IGNORED（ST-11，
 *       省钱的唯一一层）；</li>
 *   <li>事件 updatedAt >= 水印 → PASS：<b>等于水印必须放行</b>（同秒并发/同秒乱序不误杀，
 *       同 updatedAt 不同 head 由权威读裁决，ST-18）；水印 NULL（首事件）放行；</li>
 *   <li>事件 updatedAt 缺失/非法（调用方已归为 null）→ UNKNOWN：不做任何投影判断，
 *       直接进权威读（EX-18）。</li>
 * </ul>
 * 不读库、不触网、零 Spring 零 SQL（AFT-12）。
 */
public final class StaleEventGuard {

    public enum Verdict {
        /** 明显陈旧：零 API 拦截（inbox → IGNORED） */
        STALE,
        /** 放行：进权威读 */
        PASS,
        /** 事件缺 updated_at：无法判定，同样进权威读（EX-18：不猜） */
        UNKNOWN
    }

    private StaleEventGuard() {
    }

    /**
     * @param eventUpdatedAt 事件载荷里的 pull_request.updated_at（null = 缺失/非法）
     * @param watermark      pr_subject.last_event_updated_at（null = 尚无水印，首事件）
     */
    public static Verdict screen(Instant eventUpdatedAt, Instant watermark) {
        if (eventUpdatedAt == null) {
            return Verdict.UNKNOWN;
        }
        if (watermark == null) {
            return Verdict.PASS;
        }
        // 早于水印才拦截；等于放行（UT-12：同秒不误杀）
        return eventUpdatedAt.isBefore(watermark) ? Verdict.STALE : Verdict.PASS;
    }
}
