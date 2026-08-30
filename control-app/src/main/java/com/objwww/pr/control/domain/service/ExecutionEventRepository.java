package com.objwww.pr.control.domain.service;

import com.objwww.pr.shared.ExecutionEvent;

import java.util.List;
import java.util.UUID;

/**
 * 账本事件存储端口（domain 端口，Postgres 实现在 infrastructure）。
 * 只追加 + 按 Run 顺序读；不提供 update/delete（I9，DB trigger 为第二道保险）。
 */
public interface ExecutionEventRepository {

    void append(ExecutionEvent event);

    /** 按 position（DB identity）升序返回某 Run 的全部事件，供 Projector fold */
    List<ExecutionEvent> findByRunIdOrdered(UUID reviewRunId);
}
