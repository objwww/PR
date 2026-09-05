package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.NotifyOutboxEntry;

import java.util.List;
import java.util.UUID;

/**
 * notify_outbox 接口（M3-19 生产者面；领取/退避/终态迁移的 SQL 在 notify-app 投递侧）。
 */
public interface NotifyOutboxRepository {

    void insert(NotifyOutboxEntry entry);

    List<NotifyOutboxEntry> findByPublicationId(UUID publicationId);
}
