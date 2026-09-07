package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.repository.RcaEventReader;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SseStreamService 单测（M5-13）：stream ticket TTL/单次/绑 run+主体；增量排水
 * id:seq 面；seq 缺口/超窗 → resync 并停止增量（Unleash delta API 先例）。
 */
class SseStreamServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final UUID RUN = UUID.randomUUID();

    @Test
    void ticketIsSingleUseWithinTtlAndBoundToRunAndSubject() {
        RecordingSink sink = new RecordingSink();
        SseStreamService service = service(new EventQueryService(
                new EventQueryServiceTest.InMemoryEvents(List.of()),
                new EventPayloadSanitizer(200)), sink);

        String ticket = service.issueTicket(RUN, "operator", NOW);
        assertThat(service.consumeTicket(ticket, RUN, "operator", NOW)).isTrue();
        // 单次：第二次消费拒绝
        assertThat(service.consumeTicket(ticket, RUN, "operator", NOW)).isFalse();

        String expired = service.issueTicket(RUN, "operator", NOW);
        assertThat(service.consumeTicket(expired, RUN, "operator", NOW.plusSeconds(31)))
                .as("TTL 30s 过期拒绝").isFalse();

        String other = service.issueTicket(RUN, "operator", NOW);
        assertThat(service.consumeTicket(other, UUID.randomUUID(), "operator", NOW))
                .as("绑 run").isFalse();
        assertThat(service.consumeTicket(other, RUN, "sre-li", NOW))
                .as("绑主体").isFalse();
    }

    @Test
    void drainDeliversEachEventWithIdSeqAndAdvancesCursor() {
        EventQueryService events = new EventQueryService(new EventQueryServiceTest.InMemoryEvents(List.of(
                row(1, "RUN_CREATED", "{\"summary\":\"a\"}"),
                row(2, "TASK_STATE_CHANGED", "{\"summary\":\"b\"}"))),
                new EventPayloadSanitizer(200));
        RecordingSink sink = new RecordingSink();
        SseStreamService service = service(events, sink);

        long cursor = service.drain(RUN, 0, sink, NOW);

        assertThat(sink.resyncs).isEmpty();
        assertThat(sink.items).hasSize(2);
        assertThat(sink.items.get(0).id()).isEqualTo("1");
        assertThat(sink.items.get(0).event()).isEqualTo("rca_event");
        assertThat(sink.items.get(1).id()).isEqualTo("2");
        assertThat(cursor).isEqualTo(2);
    }

    @Test
    void gapStopsIncrementalAndEmitsResync() {
        // 游标 1 后从 3 起（缺口）→ resync + 停止增量（不投递 3/4 行）
        EventQueryService events = new EventQueryService(new EventQueryServiceTest.InMemoryEvents(List.of(
                row(3, "C", "{}"), row(4, "D", "{}"))), new EventPayloadSanitizer(200));
        RecordingSink sink = new RecordingSink();
        SseStreamService service = service(events, sink);

        long cursor = service.drain(RUN, 1, sink, NOW);

        assertThat(sink.resyncs).hasSize(1);
        assertThat(sink.resyncs.get(0).latestSeq()).isEqualTo(4);
        assertThat(sink.items).isEmpty();
        assertThat(cursor).isEqualTo(1);
    }

    @Test
    void emptyPollSendsHeartbeatAndKeepsCursor() {
        EventQueryService events = new EventQueryService(
                new EventQueryServiceTest.InMemoryEvents(List.of()), new EventPayloadSanitizer(200));
        RecordingSink sink = new RecordingSink();
        SseStreamService service = service(events, sink);

        long cursor = service.drain(RUN, 5, sink, NOW.plusSeconds(1));

        assertThat(sink.heartbeats).isEqualTo(1);
        assertThat(cursor).isEqualTo(5);
    }

    // ------------------------------------------------------------------

    private SseStreamService service(EventQueryService events, RecordingSink sink) {
        return new SseStreamService(events, Duration.ofSeconds(30));
    }

    private RcaEventReader.EventRow row(long seq, String type, String payload) {
        return new RcaEventReader.EventRow(seq, type, payload, NOW);
    }

    record SentItem(String id, String event, String data) {
    }

    record Resync(long latestSeq) {
    }

    static final class RecordingSink implements SseStreamService.Sink {
        final List<SentItem> items = new ArrayList<>();
        final List<Resync> resyncs = new ArrayList<>();
        int heartbeats;

        @Override
        public void accept(String id, String event, Map<String, Object> data) {
            items.add(new SentItem(id, event, String.valueOf(data)));
        }

        @Override
        public void resync(long latestSeq) {
            resyncs.add(new Resync(latestSeq));
        }

        @Override
        public void heartbeat() {
            heartbeats++;
        }
    }
}
