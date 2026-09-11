package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.domain.service.ExecutionEventRepository;
import com.objwww.pr.shared.ExecutionEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * RCA 侧模型网关事件汇（R7a-1）：把平台 ModelGateway 的 MODEL_* 决策事件映射进
 * rca_event（run 域账本）——execution_event.pr_revision_id 是 NOT NULL FK，RCA run
 * 无 PR 身份，直接复用会写不进（装配期以本汇构造 RCA 专用 ModelGateway 实例，
 * 事件语义不变、落点换域）。事件类型加 GATEWAY_ 前缀避免与 RCA 原生事件混淆。
 */
public class RcaModelEventSink implements ExecutionEventRepository {

    private final RcaEventAppender events;
    private final ObjectMapper mapper;

    public RcaModelEventSink(RcaEventAppender events, ObjectMapper mapper) {
        this.events = Objects.requireNonNull(events, "events");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void append(ExecutionEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (event.payload() != null) {
            payload.putAll(event.payload());
        }
        payload.put("gateway_event_type", event.eventType().name());
        payload.put("attempt_id", event.attemptId() == null ? null
                : event.attemptId().toString());
        events.append(event.reviewRunId(), new RcaEventAppender.EventDraft(
                event.eventId(), "GATEWAY_" + event.eventType().name(), jsonOf(payload)));
    }

    /**
     * 只写汇：ModelGateway 从不回读事件，rca_event 读背面由 RCA 域自有查询承担
     * （EX-A3 recover 面同款先例——端口方法无消费者时不实现假读）。
     */
    @Override
    public java.util.List<ExecutionEvent> findByRunIdOrdered(java.util.UUID reviewRunId) {
        throw new UnsupportedOperationException(
                "RcaModelEventSink 是只写事件汇（无回读面）");
    }

    private String jsonOf(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("网关事件载荷序列化失败: " + e.getMessage(), e);
        }
    }
}
