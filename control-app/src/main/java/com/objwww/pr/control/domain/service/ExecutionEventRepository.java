package com.objwww.pr.control.domain.service;

import com.objwww.pr.shared.ExecutionEvent;

/**
 * 账本事件存储端口（domain 端口，Postgres 实现在 infrastructure）。
 * 只追加；不提供 update/delete（I9，DB trigger 为第二道保险）。
 */
public interface ExecutionEventRepository {

    void append(ExecutionEvent event);
}
