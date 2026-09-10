package com.objwww.pr.control.alert.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 控制面告警组摘要（M7-13）：ROUTED_ONCALL 决策的随行载荷——值班派发只消费组级
 * 标识字段（标识符纪律同 ControlAlertRouter 的 CONTROL_ALERT_ONCALL 事件：不含
 * 内容正文）。startsAt 取组内最早告警时刻（firing/resolved 同 startsAt → 同剧集）。
 */
public record AlertGroupSummary(
        String receiver,
        String groupKey,
        String status,
        Map<String, String> commonLabels,
        List<String> alertnames,
        Instant startsAt,
        int alertCount) {

    /** severity 取组 label（缺省 null——值班通知不伪造严重度） */
    public String severityOf() {
        return commonLabels.get("severity");
    }
}
