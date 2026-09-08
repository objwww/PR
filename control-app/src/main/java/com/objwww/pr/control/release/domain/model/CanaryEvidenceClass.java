package com.objwww.pr.control.release.domain.model;

/**
 * Canary 证据分级（M6-01，V30 ck_ces_class / ck_cwv_class 同源）：LIVE_CANARY =
 * 生产流量采集（晋升资格唯一分级）；DRILL = 故障演练注入；REPLAY = 历史回放。
 * INV-AM6-5：DRILL/REPLAY 证据只记录不晋升——即使指标全优也不产生 promotion
 * eligibility（Evaluator 连续 K 窗仅数 LIVE）。L1 静态边界：LIVE_CANARY 只由
 * 生产采集适配器构造，test/replay 包不得引用（ArchUnit 锁定）。
 *
 * @author wanghua
 * @date 2026-09-08
 */
public enum CanaryEvidenceClass {

    LIVE_CANARY,

    DRILL,

    REPLAY
}
