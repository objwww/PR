package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.AlertFiringStatus;

import java.time.Instant;
import java.util.Map;

/**
 * 投影输入：从 alert_inbox.payload_raw 重解析出的单条规范化告警（application 层 DTO，
 * 不进 domain——解析依赖 Jackson，AFT-A01 域零框架纪律）。
 *
 * <p>入口已做过结构校验（AlertIntakeService.validateAlert），此处只取投影所需字段子集；
 * 解析失败 = payload 被篡改/腐坏，整组 DEAD_LETTER（投影期防御，不静默吞）。
 */
public record ParsedAlert(
        AlertFiringStatus status,
        String fingerprint,
        Map<String, String> labels,
        Map<String, String> annotations,
        Instant startsAt,
        Instant endsAt
) {
    public ParsedAlert {
        Map.copyOf(labels);
        Map.copyOf(annotations);
    }
}
