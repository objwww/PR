package com.objwww.pr.control.domain.review;

/**
 * 模型产出的单条原始 finding（结构化 JSON 的一条记录）。
 * line 是模型自报行号——不可信，仅作参考；精确定位以 existingCode 片段为准（FindingMapper，UT-05）。
 */
public record ModelFinding(String file, Integer line, String existingCode,
                           String rule, String severity, String message) {
}
