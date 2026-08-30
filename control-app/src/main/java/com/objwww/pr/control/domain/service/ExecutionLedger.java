package com.objwww.pr.control.domain.service;

import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.ExecutionEventType;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 执行账本（domain 服务）：append 唯一入口；事件只追加，不提供 update（I9）。
 * attempt start 不落账本（v2.2 E10）；模型流式 chunk 走内存不进账本（方案 §2.5 边界）。
 */
public final class ExecutionLedger {

    /** 当前账本 schema 版本（V1 ck_event_schema_version；演进时递增并过回放兼容测试，E9） */
    public static final int SCHEMA_VERSION = 1;

    private final ExecutionEventRepository repository;

    public ExecutionLedger(ExecutionEventRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    /** 追加一条事件；schemaVersion 必须等于当前版本 */
    public void append(ExecutionEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "schema_version 不匹配: 期望 " + SCHEMA_VERSION + "，实际 " + event.schemaVersion());
        }
        repository.append(event);
    }

    /** 构造一条当前 schema 版本的新事件（不落库；落库走 {@link #append}） */
    public ExecutionEvent newEvent(UUID reviewRunId, UUID prRevisionId,
                                   UUID stepId, UUID attemptId,
                                   ExecutionEventType eventType,
                                   UUID causationEventId, UUID correlationId,
                                   String producer, Map<String, Object> payload) {
        return new ExecutionEvent(UUID.randomUUID(), reviewRunId, prRevisionId, stepId, attemptId,
                eventType, SCHEMA_VERSION, causationEventId, correlationId,
                producer, payload, Instant.now());
    }
}
