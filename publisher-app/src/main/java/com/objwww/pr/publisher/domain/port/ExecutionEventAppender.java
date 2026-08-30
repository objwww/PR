package com.objwww.pr.publisher.domain.port;

import com.objwww.pr.shared.ExecutionEvent;

/**
 * 账本追加端口（Publisher 侧最小面）：Publisher 可 INSERT execution_event，
 * 不提供任何读/update（I9；读投影是 Control 的事）。
 */
public interface ExecutionEventAppender {

    void append(ExecutionEvent event);
}
