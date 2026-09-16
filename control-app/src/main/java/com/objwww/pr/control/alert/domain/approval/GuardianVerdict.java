package com.objwww.pr.control.alert.domain.approval;

/**
 * Guardian 预审三值（PC/E 面，设计基线 §2.3）：封闭三值，UNCERTAIN 转人工——
 * 权限单调：SAFE 只对低危白名单生效，无升级放行权（R3 永远人工双人）。
 */
public enum GuardianVerdict {
    SAFE,
    UNSAFE,
    UNCERTAIN
}
