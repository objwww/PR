package com.objwww.pr.control.eval.domain.model;

/**
 * 数据集来源分级（M5-01，INV-AM5-1）：PRIVATE = 订单域私有集（唯一主质量门，
 * 决定上线）；PUBLIC_BENCHMARK = 公共 Benchmark（RCA-100/RCAEval，外部一致性辅助门）。
 * 公共集不得冒充私有 HOLDOUT——分区归属决策在 M5-02 四分区权限面。
 */
public enum SourceClass {PRIVATE, PUBLIC_BENCHMARK}
