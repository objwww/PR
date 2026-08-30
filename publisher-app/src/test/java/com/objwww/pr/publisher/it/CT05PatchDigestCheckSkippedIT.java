package com.objwww.pr.publisher.it;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * CT-05 条件 digest CHECK（INC-04 回归）：**M0 不适用**。
 * 该约束落在 patch_proposal 表上，而 patch_proposal 属 M5（V1 schema 不含此表，
 * 见 docs/M0-技术方案.md §12 L2 表注与 V1 头部 M0 子集声明）。本类仅占位锚定编号，
 * M5 建表时补真实现。
 */
@Disabled("CT-05 不适用：patch 表 M5 才建（M0 的 V1/V2 无 patch_proposal），跳过并留锚")
class CT05PatchDigestCheckSkippedIT extends PostgresITBase {

    @Test
    void placeholder() {
        // M5 补实现：VERIFIED 且 digest 不等 → 拒；VERIFICATION_FAILED 记录失配 digest → 允许
    }
}
