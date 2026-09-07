package com.objwww.pr.control.alert.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * rca_event 只读端口（M5-13；表+游标是真相源——读面与 M4-10 append 端口分离，
 * append-only 纪律不受读路径影响）。
 */
public interface RcaEventReader {

    /** seq > afterSeq 的前 limit 行（seq 升序）；limit 由调用方钳制 */
    List<EventRow> readAfter(UUID runId, long afterSeq, int limit);

    /** 该 run 当前最大 seq；无事件 → empty */
    OptionalLong latestSeq(UUID runId);

    record EventRow(long seq, String eventType, String payloadJson, Instant createdAt) {
    }
}
