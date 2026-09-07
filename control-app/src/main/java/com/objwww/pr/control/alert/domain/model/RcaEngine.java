package com.objwww.pr.control.alert.domain.model;

/**
 * RCA 调查引擎值域（M5-10；rca_run.engine check 约束同源，V25）。
 * HOLMES = 主路径（HolmesGPT 常驻容器）；NATIVE = AM4 Java agent 候选路径
 * （CanaryRouter 分桶放量）。
 */
public enum RcaEngine {

    HOLMES,

    NATIVE
}
