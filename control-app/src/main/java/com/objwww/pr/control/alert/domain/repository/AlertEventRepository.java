package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.AlertEvent;
import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * alert_event 端口（不可变追加；INV-AM1-5 只 INSERT+SELECT）。
 *
 * <p>SQL 契约：uq_alert_event_dedup(fingerprint, payload_hash, starts_at) 是最终防线；
 * 投影事务持有 incident 行锁（findByKeyForUpdate），同 key 串行化使 existsByDedup
 * 预判与 append 之间无竞态——append 撞 uq 抛 DuplicateKeyException 属防御路径（理论不可达）。
 */
public interface AlertEventRepository {

    /** 追加事件行；uq 冲突抛 org.springframework.dao.DuplicateKeyException */
    void append(AlertEvent event);

    /** 去重键预判（uq 同键存在性）；重复通知仅累加计数不追加 */
    boolean existsByDedup(String fingerprint, Digest payloadHash, Instant startsAt);

    List<AlertEvent> findByIncidentId(UUID incidentId);
}
