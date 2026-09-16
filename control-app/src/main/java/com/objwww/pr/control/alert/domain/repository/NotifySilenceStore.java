package com.objwww.pr.control.alert.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 通知静默窗口仓储（前端产品化波次4）：命中规则的告警在窗口内不产通知 outbox。
 * 规则两维可选匹配（null=匹配全部）；DISABLED 为人工停用终态（审计留存不删行）。
 */
public interface NotifySilenceStore {

    record SilenceRow(UUID id, String alertname, String service, String reason,
                      String createdBy, Instant createdAt, Instant expiresAt, String state) {
    }

    /** 现行生效（ACTIVE 且未过期）的静默规则 */
    List<SilenceRow> findActive(Instant now);

    void insert(SilenceRow row);

    /** 人工停用：state=DISABLED；返回 false=行不存在或已非 ACTIVE */
    boolean disable(UUID id, Instant at);
}
